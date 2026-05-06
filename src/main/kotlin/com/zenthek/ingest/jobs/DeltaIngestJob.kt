package com.zenthek.ingest.jobs

import com.zenthek.ingest.DeltaFile
import com.zenthek.ingest.OffDeltaIndexParser
import com.zenthek.ingest.OffJsonlStreamReader
import com.zenthek.ingest.OffMirrorMapper
import com.zenthek.ingest.OffMirrorRow
import com.zenthek.ingest.writer.OffMirrorWriter
import com.zenthek.ingest.writer.OffSyncStateGateway
import org.slf4j.LoggerFactory

/**
 * Daily delta job. Processes every OFF delta file newer than the last
 * successful checkpoint, batched into the bulk upsert RPC. Aborts (status
 * `FAILED`) when the checkpoint sits outside OFF's ~14-day delta window —
 * a full reconcile must run first to recover.
 */
class DeltaIngestJob(
    private val indexParser: OffDeltaIndexParser,
    private val streamReader: OffJsonlStreamReader,
    private val writer: OffMirrorWriter,
    private val state: OffSyncStateGateway,
    private val dryRun: Boolean,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val deltaWindowSeconds: Long = DEFAULT_WINDOW_SECONDS,
) {
    private val log = LoggerFactory.getLogger(DeltaIngestJob::class.java)

    suspend fun run(): JobOutcome {
        state.activeRunningRow()?.let { active ->
            log.warn(
                "[OFF-INGEST] delta cancelled: another run is active id={} kind={} startedAt={}",
                active.id, active.jobKind, active.startedAt,
            )
            return JobOutcome.Cancelled("Another ingest run is already active")
        }

        val runId = state.beginRun(jobKind = "DELTA", dryRun = dryRun)
        log.info("[OFF-INGEST] delta started runId={} dryRun={}", runId, dryRun)

        try {
            val checkpoint = state.lastSuccessfulRun()?.lastModifiedTMax ?: 0L
            val nowSeconds = System.currentTimeMillis() / 1000L

            if (checkpoint > 0 && nowSeconds - checkpoint > deltaWindowSeconds) {
                val msg = "checkpoint outside delta window; full reconcile required " +
                    "(checkpoint=$checkpoint, now=$nowSeconds, window=${deltaWindowSeconds}s)"
                log.error("[OFF-INGEST] {}", msg)
                state.finishRun(runId, "FAILED", errorMessage = msg)
                return JobOutcome.Failed(msg)
            }

            val index = indexParser.fetchIndex()
            val pending = indexParser.filterNew(index, checkpoint)
            log.info(
                "[OFF-INGEST] delta plan checkpoint={} indexSize={} pending={}",
                checkpoint, index.size, pending.size,
            )

            if (pending.isEmpty()) {
                state.finishRun(
                    runId, "OK",
                    lastModifiedTMax = checkpoint.takeIf { it > 0 },
                    deltaFilesProcessed = emptyList(),
                )
                return JobOutcome.Ok(filesProcessed = 0, inserted = 0, updated = 0, softDeleted = 0)
            }

            var totalInserted = 0L
            var totalUpdated = 0L
            var newCheckpoint = checkpoint
            val processedFiles = mutableListOf<String>()

            for (file in pending) {
                val fileResult = ingestFile(file)
                totalInserted += fileResult.inserted
                totalUpdated += fileResult.updated
                if (fileResult.maxLastModified > newCheckpoint) newCheckpoint = fileResult.maxLastModified
                if (file.toTs > newCheckpoint) newCheckpoint = file.toTs
                processedFiles += file.fileName
            }

            state.finishRun(
                runId, "OK",
                rowsInserted = totalInserted,
                rowsUpdated = totalUpdated,
                lastModifiedTMax = newCheckpoint.takeIf { it > 0 },
                deltaFilesProcessed = processedFiles,
            )
            log.info(
                "[OFF-INGEST] delta finished runId={} files={} inserted={} updated={} checkpoint={}",
                runId, processedFiles.size, totalInserted, totalUpdated, newCheckpoint,
            )
            return JobOutcome.Ok(
                filesProcessed = processedFiles.size,
                inserted = totalInserted,
                updated = totalUpdated,
                softDeleted = 0,
            )
        } catch (t: Throwable) {
            log.error("[OFF-INGEST] delta failed runId={} msg={}", runId, t.message, t)
            state.finishRun(runId, "FAILED", errorMessage = t.message)
            throw t
        }
    }

    private suspend fun ingestFile(file: DeltaFile): FileResult {
        val url = indexParser.urlFor(file)
        val buffer = ArrayList<OffMirrorRow>(batchSize)
        var inserted = 0L
        var updated = 0L
        var maxLastModified = 0L

        suspend fun flush() {
            if (buffer.isEmpty()) return
            if (!dryRun) {
                val counts = writer.upsertBatch(buffer)
                inserted += counts.inserted
                updated += counts.updated
            }
            buffer.clear()
        }

        streamReader.streamRows(
            url = url,
            onProgress = { rows -> log.debug("[OFF-INGEST] delta {} rows={}", file.fileName, rows) },
        ) { obj ->
            val row = OffMirrorMapper.mapJsonlLine(obj) ?: return@streamRows
            if (row.lastModifiedT > maxLastModified) maxLastModified = row.lastModifiedT
            buffer.add(row)
            if (buffer.size >= batchSize) flush()
        }
        flush()
        log.info(
            "[OFF-INGEST] delta file done name={} inserted={} updated={} maxModified={}",
            file.fileName, inserted, updated, maxLastModified,
        )
        return FileResult(inserted, updated, maxLastModified)
    }

    private data class FileResult(val inserted: Long, val updated: Long, val maxLastModified: Long)

    sealed interface JobOutcome {
        data class Ok(
            val filesProcessed: Int,
            val inserted: Long,
            val updated: Long,
            val softDeleted: Long,
        ) : JobOutcome
        data class Cancelled(val reason: String) : JobOutcome
        data class Failed(val reason: String) : JobOutcome
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val DEFAULT_WINDOW_SECONDS = 13L * 24 * 3600 // 13 days, 1-day safety under OFF's 14
    }
}
