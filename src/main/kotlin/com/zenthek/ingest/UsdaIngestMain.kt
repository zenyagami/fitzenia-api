package com.zenthek.ingest

import com.zenthek.config.AppConfig
import com.zenthek.config.ConfigLoader
import com.zenthek.ingest.usda.UsdaArchiveDownloader
import com.zenthek.ingest.usda.UsdaMirrorWriter
import com.zenthek.ingest.usda.UsdaReleaseDetector
import com.zenthek.ingest.usda.UsdaSyncStateGateway
import com.zenthek.ingest.usda.jobs.UsdaFullReconcileJob
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
 * USDA mirror ingest entrypoint. Cloud Run Job containers boot here.
 *
 * CLI:
 *   --kind=full         (required — FDC ships only full snapshots, no deltas)
 *   --dry-run           (optional, overrides USDA_MIRROR_WRITE_ENABLED=true)
 *
 * Env-derived (via ConfigLoader):
 *   APP_ENVIRONMENT, SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
 *   USDA_MIRROR_WRITE_ENABLED — when false, every run is forced into dry-run mode
 *
 * Exit codes:
 *   0 = OK, NO_NEW_RELEASE, or CANCELLED (non-fatal — another run is in progress)
 *   1 = FAILED (anything thrown out of the job)
 *   2 = bad CLI args
 */
private val log = LoggerFactory.getLogger("com.zenthek.ingest.UsdaIngestMain")

fun main(args: Array<String>) {
    val parsed = parseArgs(args) ?: run {
        System.err.println("usage: usda-ingest --kind=full [--dry-run]")
        exitProcess(2)
    }

    val config = ConfigLoader.loadConfig()
    val dryRun = parsed.dryRun || !config.usdaMirror.writeEnabled

    log.info(
        "[USDA-INGEST] booting kind={} dryRun={} environment={} writeEnabled={} batchSize={}",
        parsed.kind, dryRun, config.environment, config.usdaMirror.writeEnabled, config.usdaMirror.batchSize,
    )

    val httpClient = buildIngestHttpClient()
    try {
        runBlocking {
            when (parsed.kind) {
                UsdaIngestKind.FULL -> runFull(httpClient, config, dryRun)
            }
        }
    } catch (t: Throwable) {
        log.error("[USDA-INGEST] unhandled error: {}", t.message, t)
        exitProcess(1)
    } finally {
        httpClient.close()
    }
    exitProcess(0)
}

private suspend fun runFull(httpClient: HttpClient, config: AppConfig, dryRun: Boolean) {
    val supabaseUrl = config.supabase.normalizedUrl
    val key = config.apiKeys.supabaseServiceRoleKey
    val job = UsdaFullReconcileJob(
        detector = UsdaReleaseDetector(httpClient),
        downloader = UsdaArchiveDownloader(httpClient),
        writer = UsdaMirrorWriter(httpClient, supabaseUrl, key),
        state = UsdaSyncStateGateway(httpClient, supabaseUrl, key),
        dryRun = dryRun,
        batchSize = config.usdaMirror.batchSize,
    )
    val outcome = job.run()
    log.info("[USDA-INGEST] full outcome={}", outcome)
    if (outcome is UsdaFullReconcileJob.JobOutcome.Failed) exitProcess(1)
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

private enum class UsdaIngestKind { FULL }

private data class UsdaParsedArgs(val kind: UsdaIngestKind, val dryRun: Boolean)

private fun parseArgs(args: Array<String>): UsdaParsedArgs? {
    var kind: UsdaIngestKind? = null
    var dryRun = false
    for (arg in args) {
        when {
            arg.startsWith("--kind=") -> kind = when (arg.substringAfter('=').lowercase()) {
                "full" -> UsdaIngestKind.FULL
                else -> return null
            }
            arg == "--dry-run" -> dryRun = true
        }
    }
    return UsdaParsedArgs(kind ?: return null, dryRun)
}
