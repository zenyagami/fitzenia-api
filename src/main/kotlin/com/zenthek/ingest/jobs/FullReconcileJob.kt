package com.zenthek.ingest.jobs

import com.zenthek.ingest.OffJsonlStreamReader
import com.zenthek.ingest.OffMirrorMapper
import com.zenthek.ingest.OffMirrorRow
import com.zenthek.ingest.writer.OffMirrorWriter
import com.zenthek.ingest.writer.OffSyncStateGateway
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Weekly full reconcile. Streams the entire OFF JSONL dump, batches upserts
 * via the bulk RPC, then soft-deletes any row whose `synced_at` is older than
 * the run-start cutoff (rows missing from the dump = candidates for deletion).
 *
 * Soft-delete correctness depends on the upsert RPC always refreshing
 * `synced_at` on conflict. See `db/migrations/002_off_mirror.sql`.
 */
class FullReconcileJob(
    private val streamReader: OffJsonlStreamReader,
    private val writer: OffMirrorWriter,
    private val state: OffSyncStateGateway,
    private val dryRun: Boolean,
    private val dumpUrl: String = DEFAULT_DUMP_URL,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val log = LoggerFactory.getLogger(FullReconcileJob::class.java)

    suspend fun run(): DeltaIngestJob.JobOutcome {
        state.activeRunningRow()?.let { active ->
            log.warn(
                "[OFF-INGEST] full cancelled: another run is active id={} kind={}",
                active.id, active.jobKind,
            )
            return DeltaIngestJob.JobOutcome.Cancelled("Another ingest run is already active")
        }

        val runId = state.beginRun(jobKind = "FULL", dryRun = dryRun)
        log.info("[OFF-INGEST] full started runId={} dryRun={} url={}", runId, dryRun, dumpUrl)

        // Capture run-start BEFORE the first upsert. Anything not refreshed by
        // this run (i.e. synced_at < runStart at the end) is a candidate for
        // soft-delete. Use the wall clock the writer will use, with a safety
        // cushion to absorb minor server/client clock drift.
        val runStart = Instant.now().minusSeconds(60)

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

        try {
            streamReader.streamRows(
                url = dumpUrl,
                onProgress = { rows -> log.info("[OFF-INGEST] full progress rows={}", rows) },
            ) { obj ->
                val row = OffMirrorMapper.mapJsonlLine(obj) ?: return@streamRows
                if (row.lastModifiedT > maxLastModified) maxLastModified = row.lastModifiedT
                buffer.add(row)
                if (buffer.size >= batchSize) flush()
            }
            flush()

            val softDeleted = if (!dryRun) {
                writer.softDeleteUnseen(runStart.toString())
            } else {
                log.info("[OFF-INGEST] full dryRun: skipping soft-delete pass (would have used cutoff={})", runStart)
                0L
            }

            state.finishRun(
                runId, "OK",
                rowsInserted = inserted,
                rowsUpdated = updated,
                rowsSoftDeleted = softDeleted,
                lastModifiedTMax = maxLastModified.takeIf { it > 0 },
            )
            log.info(
                "[OFF-INGEST] full finished runId={} inserted={} updated={} softDeleted={} maxModified={}",
                runId, inserted, updated, softDeleted, maxLastModified,
            )
            return DeltaIngestJob.JobOutcome.Ok(
                filesProcessed = 1,
                inserted = inserted,
                updated = updated,
                softDeleted = softDeleted,
            )
        } catch (t: Throwable) {
            log.error("[OFF-INGEST] full failed runId={} msg={}", runId, t.message, t)
            state.finishRun(runId, "FAILED", errorMessage = t.message)
            throw t
        }
    }

    companion object {
        const val DEFAULT_DUMP_URL = "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz"
        const val DEFAULT_BATCH_SIZE = 500
    }
}
