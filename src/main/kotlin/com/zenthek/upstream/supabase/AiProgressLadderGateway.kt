package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.AiProgressLadderInsertPayload
import com.zenthek.model.AiProgressLadderRow
import com.zenthek.model.AiProgressLadderRungInsertPayload
import com.zenthek.model.AiProgressLadderRungRow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * Service-role gateway for the AI Progress Projections feature. All calls bypass RLS,
 * so every method that takes a user_id explicitly scopes its query / write to that user
 * as defense in depth — even though service-role itself is unrestricted.
 */
class AiProgressLadderGateway(
    private val httpClient: HttpClient,
    private val supabaseConfig: SupabaseConfig,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(AiProgressLadderGateway::class.java)
    private val baseUrl = supabaseConfig.url.trimEnd('/')

    /** Cache lookup. Returns null if no row exists for (user_id, request_key). */
    suspend fun findByCacheKey(userId: String, requestKey: String): AiProgressLadderRow? {
        val response = httpClient.get("$baseUrl/rest/v1/ai_progress_ladder") {
            applyServiceRoleAuth()
            parameter("user_id", "eq.$userId")
            parameter("request_key", "eq.$requestKey")
            parameter("select", LADDER_SELECT)
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) {
            log.warn("[LADDER] findByCacheKey status={}", response.status.value)
            return null
        }
        return response.body<List<AiProgressLadderRow>>().firstOrNull()
    }

    /** Returns the rung rows for a ladder, ordered by step_index. */
    suspend fun findRungs(ladderId: String): List<AiProgressLadderRungRow> {
        val response = httpClient.get("$baseUrl/rest/v1/ai_progress_ladder_rung") {
            applyServiceRoleAuth()
            parameter("ladder_id", "eq.$ladderId")
            parameter("select", "*")
            parameter("order", "step_index.asc")
        }
        if (!response.status.isSuccess()) {
            log.warn("[LADDER] findRungs status={} ladderId={}", response.status.value, ladderId)
            return emptyList()
        }
        return response.body()
    }

    /**
     * INSERT ... ON CONFLICT (user_id, request_key) DO NOTHING. Returns the inserted row
     * on success or null if the conflict path was taken (a parallel request already won
     * the race). Caller falls back to [findByCacheKey] in the null case.
     */
    suspend fun insertLadderIfAbsent(payload: AiProgressLadderInsertPayload): AiProgressLadderRow? {
        val response = httpClient.post("$baseUrl/rest/v1/ai_progress_ladder") {
            applyServiceRoleAuth()
            // PostgREST: Prefer header to control insert behavior
            // - return=representation : return the inserted row
            // - resolution=ignore-duplicates : ON CONFLICT DO NOTHING
            header("Prefer", "return=representation,resolution=ignore-duplicates")
            // `on_conflict` tells PostgREST which constraint to evaluate
            parameter("on_conflict", "user_id,request_key")
            parameter("select", LADDER_SELECT)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<unreadable>")
            log.warn("[LADDER] insertLadderIfAbsent status={} userId={} key={} body={}", response.status.value, payload.userId, payload.requestKey, body)
            throw IllegalStateException("ai_progress_ladder insert failed with ${response.status.value}")
        }
        // PostgREST returns an array; empty when the conflict was hit and DO NOTHING ran.
        val rows = response.body<List<AiProgressLadderRow>>()
        return rows.firstOrNull()
    }

    /** PATCH a single ladder row by id. Status / failure_code / gatekeeper_verdict are the
     *  fields that change after insert; the rest is immutable per cache contract. */
    suspend fun updateLadder(
        ladderId: String,
        status: String? = null,
        failureCode: String? = null,
        gatekeeperVerdict: JsonElement? = null,
    ) {
        val patch = LadderPatch(
            status = status,
            failureCode = failureCode,
            gatekeeperVerdict = gatekeeperVerdict,
        )
        val response = httpClient.patch("$baseUrl/rest/v1/ai_progress_ladder") {
            applyServiceRoleAuth()
            header("Prefer", "return=minimal")
            parameter("id", "eq.$ladderId")
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<unreadable>")
            log.warn("[LADDER] updateLadder status={} ladderId={} body={}", response.status.value, ladderId, body)
            throw IllegalStateException("ai_progress_ladder patch failed with ${response.status.value}")
        }
    }

    suspend fun insertRung(payload: AiProgressLadderRungInsertPayload): AiProgressLadderRungRow {
        val response = httpClient.post("$baseUrl/rest/v1/ai_progress_ladder_rung") {
            applyServiceRoleAuth()
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<unreadable>")
            log.warn("[LADDER] insertRung status={} ladderId={} step={} body={}", response.status.value, payload.ladderId, payload.stepIndex, body)
            throw IllegalStateException("ai_progress_ladder_rung insert failed with ${response.status.value}")
        }
        val rows = response.body<List<AiProgressLadderRungRow>>()
        return rows.firstOrNull() ?: error("Empty INSERT response for ai_progress_ladder_rung")
    }

    /**
     * Read rung storage paths for a ladder owned by [userId]. Used by the DELETE endpoint
     * to (a) verify ownership and (b) collect blob paths to wipe. Returns empty list when
     * the ladder doesn't exist or doesn't belong to the caller — the endpoint translates
     * that to 404.
     */
    suspend fun findRungStoragePathsForOwnedLadder(userId: String, ladderId: String): List<String> {
        val response = httpClient.get("$baseUrl/rest/v1/ai_progress_ladder_rung") {
            applyServiceRoleAuth()
            parameter("user_id", "eq.$userId")
            parameter("ladder_id", "eq.$ladderId")
            parameter("select", "storage_path")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("rung storage_path lookup failed with ${response.status.value}")
        }
        return response.body<List<StoragePathRow>>().mapNotNull { it.storagePath }
    }

    /** Hard-delete a ladder owned by [userId]. Cascade FK wipes the rung rows. */
    suspend fun deleteOwnedLadder(userId: String, ladderId: String) {
        val response = httpClient.delete("$baseUrl/rest/v1/ai_progress_ladder") {
            applyServiceRoleAuth()
            header("Prefer", "return=minimal")
            parameter("id", "eq.$ladderId")
            parameter("user_id", "eq.$userId")
        }
        if (!response.status.isSuccess()) {
            log.warn("[LADDER] deleteOwnedLadder status={} ladderId={}", response.status.value, ladderId)
            throw IllegalStateException("ai_progress_ladder delete failed with ${response.status.value}")
        }
    }

    /** Latest weight_entry row for [userId], used to backfill currentWeightKg when the
     *  request omits it. Returns null if the user has no weight history. */
    suspend fun findLatestWeightEntry(userId: String): WeightEntryReadRow? {
        val response = httpClient.get("$baseUrl/rest/v1/weight_entry") {
            applyServiceRoleAuth()
            parameter("user_id", "eq.$userId")
            parameter("select", "weight_kg,body_fat_percent,date,created_at")
            parameter("order", "date.desc,created_at.desc")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) {
            log.warn("[LADDER] findLatestWeightEntry status={} userId={}", response.status.value, userId)
            return null
        }
        return response.body<List<WeightEntryReadRow>>().firstOrNull()
    }

    /** user_goal row for [userId]. Used to backfill targetWeightKg / targetBodyFatPercent. */
    suspend fun findUserGoal(userId: String): UserGoalReadRow? {
        val response = httpClient.get("$baseUrl/rest/v1/user_goal") {
            applyServiceRoleAuth()
            parameter("user_id", "eq.$userId")
            parameter("select", "goal_weight_kg,body_fat_percent")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) {
            log.warn("[LADDER] findUserGoal status={} userId={}", response.status.value, userId)
            return null
        }
        return response.body<List<UserGoalReadRow>>().firstOrNull()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
    }

    companion object {
        private const val LADDER_SELECT =
            "id,user_id,source_content_hash,source_width,source_height,base_weight_kg,base_body_fat_percent," +
                "target_weight_kg,target_body_fat_percent,body_fat_source,step_body_fat_percent,num_steps," +
                "model,quality,size,prompt_version,request_key,gatekeeper_verdict,status,failure_code,created_at,updated_at"
    }
}

@Serializable
private data class LadderPatch(
    val status: String? = null,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("gatekeeper_verdict") val gatekeeperVerdict: JsonElement? = null,
)

@Serializable
private data class StoragePathRow(
    @SerialName("storage_path") val storagePath: String? = null,
)

@Serializable
data class WeightEntryReadRow(
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("body_fat_percent") val bodyFatPercent: Double? = null,
)

@Serializable
data class UserGoalReadRow(
    @SerialName("goal_weight_kg") val goalWeightKg: Double? = null,
    @SerialName("body_fat_percent") val bodyFatPercent: Double? = null,
)
