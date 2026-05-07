package com.zenthek.ingest.usda.jobs

import com.zenthek.ingest.usda.UsdaArchiveDownloader
import com.zenthek.ingest.usda.UsdaDataType
import com.zenthek.ingest.usda.UsdaMirrorMapper
import com.zenthek.ingest.usda.UsdaMirrorRow
import com.zenthek.ingest.usda.UsdaMirrorWriter
import com.zenthek.ingest.usda.UsdaReleaseDetector
import com.zenthek.ingest.usda.UsdaSyncStateGateway
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Full reconcile: probe FDC for the latest release, stream Branded +
 * Foundation zips, batch-upsert via the bulk RPC, soft-delete rows missing
 * from the dump.
 *
 * Monthly cron drives this. Bi-annual real ingest (April + December);
 * 10 cheap NO_NEW_RELEASE no-ops per year.
 */
class UsdaFullReconcileJob(
    private val detector: UsdaReleaseDetector,
    private val downloader: UsdaArchiveDownloader,
    private val writer: UsdaMirrorWriter,
    private val state: UsdaSyncStateGateway,
    private val dryRun: Boolean,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val log = LoggerFactory.getLogger(UsdaFullReconcileJob::class.java)

    sealed class JobOutcome {
        data class Ok(val inserted: Long, val updated: Long, val softDeleted: Long, val releaseDate: String) : JobOutcome()
        data class NoNewRelease(val releaseDate: String) : JobOutcome()
        data class Cancelled(val reason: String) : JobOutcome()
        data class Failed(val message: String) : JobOutcome()
    }

    suspend fun run(): JobOutcome {
        state.activeRunningRow()?.let { active ->
            log.warn("[USDA-INGEST] cancelled: another run is active id={} kind={}", active.id, active.jobKind)
            return JobOutcome.Cancelled("Another USDA ingest run is already active")
        }

        val manifest = detector.fetchManifest() ?: run {
            // Couldn't resolve the latest release URLs — record FAILED so the
            // next monthly cron retries. We still need a sync_state row so
            // ops can see the failure in the audit table.
            val runId = state.beginRun(jobKind = "FULL", dryRun = dryRun)
            state.finishRun(runId, "FAILED", errorMessage = "Could not resolve FDC release URLs from index page")
            return JobOutcome.Failed("Release detection failed — no manifest")
        }
        log.info(
            "[USDA-INGEST] release manifest brandedDate={} foundationDate={} maxDate={}",
            manifest.brandedDate, manifest.foundationDate, manifest.maxDate,
        )

        val lastReleaseDate = state.lastSuccessfulReleaseDate()
        if (lastReleaseDate != null && !manifest.maxDate.isAfter(lastReleaseDate)) {
            // Same release already mirrored. Cheap no-op month.
            val runId = state.beginRun(jobKind = "FULL", dryRun = dryRun)
            state.finishRun(runId, "NO_NEW_RELEASE", releaseDate = manifest.maxDate)
            log.info("[USDA-INGEST] no new release (last={} current={})", lastReleaseDate, manifest.maxDate)
            return JobOutcome.NoNewRelease(manifest.maxDate.toString())
        }

        val runId = state.beginRun(jobKind = "FULL", dryRun = dryRun)
        log.info(
            "[USDA-INGEST] full started runId={} dryRun={} brandedUrl={} foundationUrl={}",
            runId, dryRun, manifest.brandedUrl, manifest.foundationUrl,
        )

        // Capture run-start BEFORE the first upsert. Anything not refreshed by
        // this run (synced_at < runStart at the end) is a candidate for
        // soft-delete. Safety cushion absorbs minor server/client clock drift.
        val runStart = Instant.now().minusSeconds(60)

        val buffer = ArrayList<UsdaMirrorRow>(batchSize)
        var inserted = 0L
        var updated = 0L

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
            // Branded first — it's the bulk of the data and the more impactful
            // dataset for users (most barcodes resolve here). If branded
            // streaming fails partway, the soft-delete pass is skipped so we
            // don't tombstone rows the foundation pass would have refreshed.
            log.info("[USDA-INGEST] streaming Branded url={}", manifest.brandedUrl)
            downloader.streamFoods(
                url = manifest.brandedUrl,
                onProgress = { rows ->
                    log.info("[USDA-INGEST] branded progress rows={} inserted={} updated={}", rows, inserted, updated)
                },
            ) { node ->
                val row = UsdaMirrorMapper.mapBrandedFood(node) ?: return@streamFoods
                buffer.add(row)
                if (buffer.size >= batchSize) flush()
            }
            flush()

            log.info("[USDA-INGEST] streaming Foundation url={}", manifest.foundationUrl)
            downloader.streamFoods(
                url = manifest.foundationUrl,
                onProgress = { rows ->
                    log.info("[USDA-INGEST] foundation progress rows={} inserted={} updated={}", rows, inserted, updated)
                },
            ) { node ->
                val row = UsdaMirrorMapper.mapFoundationFood(node) ?: return@streamFoods
                buffer.add(row)
                if (buffer.size >= batchSize) flush()
            }
            flush()

            val softDeleted = if (!dryRun) {
                writer.softDeleteUnseen(runStart.toString())
            } else {
                log.info("[USDA-INGEST] dryRun: skipping soft-delete pass (would have used cutoff={})", runStart)
                0L
            }

            state.finishRun(
                runId, "OK",
                rowsInserted = inserted,
                rowsUpdated = updated,
                rowsSoftDeleted = softDeleted,
                releaseDate = manifest.maxDate,
            )
            log.info(
                "[USDA-INGEST] full finished runId={} inserted={} updated={} softDeleted={} releaseDate={}",
                runId, inserted, updated, softDeleted, manifest.maxDate,
            )
            return JobOutcome.Ok(inserted, updated, softDeleted, manifest.maxDate.toString())
        } catch (t: Throwable) {
            log.error("[USDA-INGEST] full failed runId={} msg={}", runId, t.message, t)
            state.finishRun(runId, "FAILED", errorMessage = t.message)
            throw t
        }
    }

    @Suppress("unused")
    private fun UsdaMirrorRow.isFoundation(): Boolean = dataType == UsdaDataType.FOUNDATION

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
    }
}
