package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.service.UpstreamFailureException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val storageJson = Json { ignoreUnknownKeys = true }

/**
 * Backend-only admin operations against Supabase using the service-role key:
 *   - recursive storage wipe for a user prefix,
 *   - Postgres RPC to delete all user-scoped rows,
 *   - auth.admin user deletion.
 *
 * Every operation is idempotent so that a retry after partial failure is safe.
 * The service-role key bypasses RLS — never use this gateway on a user-scoped
 * request path.
 */
class SupabaseAdminGateway(
    private val httpClient: HttpClient,
    private val supabaseConfig: SupabaseConfig,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(SupabaseAdminGateway::class.java)
    private val baseUrl = supabaseConfig.url.trimEnd('/')

    /**
     * Lists every object under `<userIdPrefix>/` in the given bucket and removes
     * them in batches until the listing is empty. A missing/empty prefix exits
     * cleanly (the user never uploaded anything).
     */
    suspend fun deleteAllUserStorageObjects(bucket: String, userIdPrefix: String) {
        val prefix = "$userIdPrefix/"
        var batch = 0
        var totalRemoved = 0
        while (true) {
            val names = listStorageObjects(bucket, prefix)
            log.info(
                "[ACCOUNT-ADMIN] storage list bucket={} prefix={} batch={} count={}",
                bucket, prefix, batch, names.size
            )
            if (names.isEmpty()) {
                log.info(
                    "[ACCOUNT-ADMIN] storage wipe done bucket={} prefix={} totalRemoved={} batches={}",
                    bucket, prefix, totalRemoved, batch
                )
                return
            }
            val fullPaths = names.map { prefix + it }
            removeStorageObjects(bucket, fullPaths)
            totalRemoved += fullPaths.size
            batch += 1
            log.info(
                "[ACCOUNT-ADMIN] storage remove bucket={} prefix={} batch={} removed={} runningTotal={}",
                bucket, prefix, batch, fullPaths.size, totalRemoved
            )
        }
    }

    private suspend fun listStorageObjects(bucket: String, prefix: String): List<String> {
        val response = runStage(stage = "storage-list") {
            httpClient.post("$baseUrl/storage/v1/object/list/$bucket") {
                applyServiceRoleAuth()
                contentType(ContentType.Application.Json)
                // supabase-js always sends sortBy; sending it matches the reference client
                // and avoids edge cases where the server rejects the minimal shape.
                setBody(
                    StorageListRequest(
                        prefix = prefix,
                        limit = 1000,
                        offset = 0,
                        sortBy = StorageSortBy(column = "name", order = "asc"),
                    )
                )
            }
        }
        if (response.status == HttpStatusCode.NotFound) {
            log.info("[ACCOUNT-ADMIN] storage list not found bucket={} prefix={} (treating as empty)", bucket, prefix)
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            fail(stage = "storage-list", response)
        }
        val raw = runStage(stage = "storage-list-parse") { response.bodyAsText() }
        return parseStorageListResponse(raw)
    }

    private fun parseStorageListResponse(raw: String): List<String> = try {
        storageJson.decodeFromString<List<StorageListItem>>(raw).map { it.name }
    } catch (cause: Throwable) {
        log.error(
            "[ACCOUNT-ADMIN] storage list response parse failed bodyPreview={}",
            raw.take(500),
            cause,
        )
        throw UpstreamFailureException("Account deletion failed at storage-list-parse")
    }

    private suspend fun removeStorageObjects(bucket: String, fullPaths: List<String>) {
        // Supabase multi-delete: DELETE /storage/v1/object/{bucket} with body { "prefixes": [...] }
        // (matches what supabase-js `.remove()` sends; there is no `/remove` sub-path).
        val response = runStage(stage = "storage-remove") {
            httpClient.delete("$baseUrl/storage/v1/object/$bucket") {
                applyServiceRoleAuth()
                contentType(ContentType.Application.Json)
                setBody(StorageRemoveRequest(prefixes = fullPaths))
            }
        }
        if (!response.status.isSuccess()) {
            fail(stage = "storage-remove", response)
        }
    }

    /**
     * Deletes every user-scoped row via the `public.delete_user_data` RPC
     * (single server-side transaction). Idempotent: rerunning after a partial
     * Postgres failure simply re-issues `DELETE WHERE user_id = $1` against
     * already-empty rows.
     */
    suspend fun deleteAllUserRows(userId: String) {
        log.info("[ACCOUNT-ADMIN] postgres rpc delete_user_data request userId={}", userId)
        val response = runStage(stage = "postgres-rpc") {
            httpClient.post("$baseUrl/rest/v1/rpc/delete_user_data") {
                applyServiceRoleAuth()
                contentType(ContentType.Application.Json)
                setBody(DeleteUserDataRpcRequest(userId))
            }
        }
        if (!response.status.isSuccess()) {
            fail(stage = "postgres-rpc", response)
        }
        log.info("[ACCOUNT-ADMIN] postgres rpc delete_user_data ok userId={} status={}", userId, response.status.value)
    }

    /**
     * Deletes the Supabase auth user. Treats 404 as success so a retry after
     * an Auth 5xx doesn't block on an already-deleted row.
     */
    suspend fun deleteAuthUser(userId: String) {
        log.info("[ACCOUNT-ADMIN] auth admin delete request userId={}", userId)
        val response = runStage(stage = "auth-admin-delete") {
            httpClient.delete("$baseUrl/auth/v1/admin/users/$userId") {
                applyServiceRoleAuth()
            }
        }
        if (response.status == HttpStatusCode.NotFound) {
            log.info("[ACCOUNT-ADMIN] auth admin delete 404 userId={} (already deleted — treated as success)", userId)
            return
        }
        if (!response.status.isSuccess()) {
            fail(stage = "auth-admin-delete", response)
        }
        log.info("[ACCOUNT-ADMIN] auth admin delete ok userId={} status={}", userId, response.status.value)
    }

    private suspend fun fail(stage: String, response: HttpResponse): Nothing {
        val status = response.status.value
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        log.error("[ACCOUNT-ADMIN] stage={} status={} body={}", stage, status, body)
        throw UpstreamFailureException("Account deletion failed at $stage")
    }

    /**
     * Wraps a stage-scoped suspend block so any non-tolerated exception (network I/O,
     * serialization, etc.) is surfaced as an `UpstreamFailureException` with the
     * stage name logged, instead of a generic "Unhandled error" 500.
     */
    private suspend inline fun <T> runStage(stage: String, block: () -> T): T = try {
        block()
    } catch (cause: UpstreamFailureException) {
        throw cause
    } catch (cause: Throwable) {
        log.error("[ACCOUNT-ADMIN] stage={} threw {}: {}", stage, cause::class.java.simpleName, cause.message, cause)
        throw UpstreamFailureException("Account deletion failed at $stage")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
    }
}

@Serializable
private data class StorageListRequest(
    val prefix: String,
    val limit: Int,
    val offset: Int,
    val sortBy: StorageSortBy,
)

@Serializable
private data class StorageSortBy(
    val column: String,
    val order: String,
)

@Serializable
private data class StorageListItem(
    val name: String,
)

@Serializable
private data class StorageRemoveRequest(
    val prefixes: List<String>,
)

@Serializable
private data class DeleteUserDataRpcRequest(
    @SerialName("p_user_id") val userId: String,
)
