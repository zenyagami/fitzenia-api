package com.zenthek.ingest

import com.zenthek.config.AppConfig
import com.zenthek.config.ConfigLoader
import com.zenthek.ingest.jobs.DeltaIngestJob
import com.zenthek.ingest.jobs.FullReconcileJob
import com.zenthek.ingest.writer.OffMirrorWriter
import com.zenthek.ingest.writer.OffSyncStateGateway
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * OFF mirror ingest entrypoint. Cloud Run Job containers boot here.
 *
 * CLI:
 *   --kind=delta|full   (required)
 *   --dry-run           (optional, overrides OFF_MIRROR_WRITE_ENABLED=true)
 *
 * Env-derived:
 *   APP_ENVIRONMENT, SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (via ConfigLoader)
 *   OFF_MIRROR_WRITE_ENABLED — when false, the run is forced into dry-run mode
 *
 * Exit codes:
 *   0 = OK or CANCELLED (cancelled is non-fatal — another run is in progress)
 *   1 = FAILED (anything thrown out of the job)
 *   2 = bad CLI args
 */
private val log = LoggerFactory.getLogger("com.zenthek.ingest.IngestMain")

fun main(args: Array<String>) {
    val parsed = parseArgs(args) ?: run {
        System.err.println("usage: off-ingest --kind=delta|full [--dry-run]")
        exitProcess(2)
    }

    val config = ConfigLoader.loadConfig()
    val dryRun = parsed.dryRun || !config.offMirror.writeEnabled

    log.info(
        "[OFF-INGEST] booting kind={} dryRun={} environment={} writeEnabled={} batchSize={}",
        parsed.kind, dryRun, config.environment, config.offMirror.writeEnabled, config.offMirror.batchSize,
    )

    val httpClient = buildIngestHttpClient()
    try {
        runBlocking {
            when (parsed.kind) {
                IngestKind.DELTA -> runDelta(httpClient, config, dryRun)
                IngestKind.FULL -> runFull(httpClient, config, dryRun)
            }
        }
    } catch (t: Throwable) {
        log.error("[OFF-INGEST] unhandled error: {}", t.message, t)
        exitProcess(1)
    } finally {
        httpClient.close()
    }
    exitProcess(0)
}

private suspend fun runDelta(httpClient: HttpClient, config: AppConfig, dryRun: Boolean) {
    val supabaseUrl = config.supabase.normalizedUrl
    val key = config.apiKeys.supabaseServiceRoleKey
    val job = DeltaIngestJob(
        indexParser = OffDeltaIndexParser(httpClient),
        streamReader = OffJsonlStreamReader(httpClient),
        writer = OffMirrorWriter(httpClient, supabaseUrl, key),
        state = OffSyncStateGateway(httpClient, supabaseUrl, key),
        dryRun = dryRun,
        batchSize = config.offMirror.batchSize,
    )
    val outcome = job.run()
    log.info("[OFF-INGEST] delta outcome={}", outcome)
    if (outcome is DeltaIngestJob.JobOutcome.Failed) exitProcess(1)
}

private suspend fun runFull(httpClient: HttpClient, config: AppConfig, dryRun: Boolean) {
    val supabaseUrl = config.supabase.normalizedUrl
    val key = config.apiKeys.supabaseServiceRoleKey
    val job = FullReconcileJob(
        streamReader = OffJsonlStreamReader(httpClient),
        writer = OffMirrorWriter(httpClient, supabaseUrl, key),
        state = OffSyncStateGateway(httpClient, supabaseUrl, key),
        dryRun = dryRun,
        batchSize = config.offMirror.batchSize,
    )
    val outcome = job.run()
    log.info("[OFF-INGEST] full outcome={}", outcome)
    if (outcome is DeltaIngestJob.JobOutcome.Failed) exitProcess(1)
}

private fun buildIngestHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 10_000
    }
}

private enum class IngestKind { DELTA, FULL }

private data class ParsedArgs(val kind: IngestKind, val dryRun: Boolean)

private fun parseArgs(args: Array<String>): ParsedArgs? {
    var kind: IngestKind? = null
    var dryRun = false
    for (arg in args) {
        when {
            arg.startsWith("--kind=") -> kind = when (arg.substringAfter('=').lowercase()) {
                "delta" -> IngestKind.DELTA
                "full" -> IngestKind.FULL
                else -> return null
            }
            arg == "--dry-run" -> dryRun = true
        }
    }
    return ParsedArgs(kind ?: return null, dryRun)
}
