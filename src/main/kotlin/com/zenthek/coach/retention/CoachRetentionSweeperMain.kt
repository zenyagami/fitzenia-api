package com.zenthek.coach.retention

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("CoachRetentionSweeper")

// Archived coach chats are hard-deleted 12 months after archival. Deleting the
// coach_chat row cascades coach_message / coach_summary (FK ON DELETE CASCADE), and
// coach_trace cascades via coach_message — so one DELETE on coach_chat clears the turn.
private const val RETENTION_MONTHS = 12L

/**
 * Daily Cloud Run Job (`-PtargetService=coach-retention`). Hard-deletes coach chats that
 * were archived (user-deleted) more than 12 months ago, plus their cascaded messages.
 *
 * The cutoff is scoped to archived chats older than 12 months, so the filter is
 * `archived_at IS NOT NULL AND archived_at < cutoff`.
 *
 * Stateless and idempotent — a re-run simply finds fewer (or zero) eligible rows.
 */
fun main() {
    log.info("[COACH-RETENTION] starting (retention={} months)", RETENTION_MONTHS)

    val exitCode = runBlocking {
        try {
            val dotenv = dotenv { ignoreIfMissing = true }
            val supabaseUrl = dotenv["SUPABASE_URL"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_URL")
            val serviceRoleKey = dotenv["SUPABASE_SERVICE_ROLE_KEY"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_SERVICE_ROLE_KEY")

            val httpClient = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 10_000 }
            }
            httpClient.use { client ->
                val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant().toString()
                log.info("[COACH-RETENTION] deleting archived chats with archived_at < {}", cutoff)

                val encodedCutoff = URLEncoder.encode(cutoff, Charsets.UTF_8)
                val response = client.delete(
                    "$supabaseUrl/rest/v1/coach_chat?archived_at=not.is.null&archived_at=lt.$encodedCutoff"
                ) {
                    header("apikey", serviceRoleKey)
                    header("Authorization", "Bearer $serviceRoleKey")
                    // count=exact returns the deleted-row count in the Content-Range header
                    // (e.g. "*/12"); return=minimal avoids streaming the deleted rows back.
                    header("Prefer", "return=minimal,count=exact")
                }
                if (!response.status.isSuccess()) {
                    val bodyText: String = response.body()
                    log.error("[COACH-RETENTION] delete failed status={} body={}", response.status, bodyText)
                    error("retention delete failed: ${response.status}")
                }
                val deleted = response.headers["Content-Range"]?.substringAfter('/')?.toIntOrNull()
                log.info("[COACH-RETENTION] done deletedChats={}", deleted ?: "unknown")
            }
            0
        } catch (e: Exception) {
            log.error("[COACH-RETENTION] fatal error", e)
            1
        }
    }
    exitProcess(exitCode)
}
