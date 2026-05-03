package com.zenthek.model

import com.zenthek.ai.GatekeeperVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* =====================================================================================
 * Database row shapes (service-role inserts/selects via PostgREST).
 * snake_case column names mapped via @SerialName.
 * ===================================================================================== */

@Serializable
data class AiProgressLadderRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("source_content_hash") val sourceContentHash: String,
    @SerialName("source_width") val sourceWidth: Int? = null,
    @SerialName("source_height") val sourceHeight: Int? = null,
    @SerialName("base_weight_kg") val baseWeightKg: Double,
    @SerialName("base_body_fat_percent") val baseBodyFatPercent: Double,
    @SerialName("target_weight_kg") val targetWeightKg: Double,
    @SerialName("target_body_fat_percent") val targetBodyFatPercent: Double,
    @SerialName("body_fat_source") val bodyFatSource: String? = null,
    @SerialName("step_body_fat_percent") val stepBodyFatPercent: Double,
    @SerialName("num_steps") val numSteps: Int,
    val model: String,
    val quality: String,
    val size: String,
    @SerialName("prompt_version") val promptVersion: Int,
    @SerialName("request_key") val requestKey: String,
    @SerialName("gatekeeper_verdict") val gatekeeperVerdict: GatekeeperVerdict? = null,
    val status: String,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class AiProgressLadderRungRow(
    val id: String,
    @SerialName("ladder_id") val ladderId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("step_index") val stepIndex: Int,
    val kind: String,
    @SerialName("projected_body_fat_percent") val projectedBodyFatPercent: Double,
    @SerialName("projected_weight_kg") val projectedWeightKg: Double,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("openai_model") val openAiModel: String? = null,
    @SerialName("usage_input_tokens") val usageInputTokens: Int? = null,
    @SerialName("usage_output_tokens") val usageOutputTokens: Int? = null,
    @SerialName("usage_cached_input_tokens") val usageCachedInputTokens: Int? = null,
    @SerialName("cost_micros") val costMicros: Long? = null,
    val status: String,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/* =====================================================================================
 * Insert payloads — what the orchestrator sends to PostgREST.
 * ===================================================================================== */

@Serializable
data class AiProgressLadderInsertPayload(
    @SerialName("user_id") val userId: String,
    @SerialName("source_content_hash") val sourceContentHash: String,
    @SerialName("source_width") val sourceWidth: Int?,
    @SerialName("source_height") val sourceHeight: Int?,
    @SerialName("base_weight_kg") val baseWeightKg: Double,
    @SerialName("base_body_fat_percent") val baseBodyFatPercent: Double,
    @SerialName("target_weight_kg") val targetWeightKg: Double,
    @SerialName("target_body_fat_percent") val targetBodyFatPercent: Double,
    @SerialName("body_fat_source") val bodyFatSource: String,
    @SerialName("step_body_fat_percent") val stepBodyFatPercent: Double,
    @SerialName("num_steps") val numSteps: Int,
    val model: String,
    val quality: String,
    val size: String,
    @SerialName("prompt_version") val promptVersion: Int,
    @SerialName("request_key") val requestKey: String,
    val status: String = "PENDING",
)

@Serializable
data class AiProgressLadderRungInsertPayload(
    @SerialName("ladder_id") val ladderId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("step_index") val stepIndex: Int,
    val kind: String,
    @SerialName("projected_body_fat_percent") val projectedBodyFatPercent: Double,
    @SerialName("projected_weight_kg") val projectedWeightKg: Double,
    @SerialName("storage_path") val storagePath: String?,
    @SerialName("openai_model") val openAiModel: String?,
    @SerialName("usage_input_tokens") val usageInputTokens: Int?,
    @SerialName("usage_output_tokens") val usageOutputTokens: Int?,
    @SerialName("usage_cached_input_tokens") val usageCachedInputTokens: Int?,
    @SerialName("cost_micros") val costMicros: Long?,
    val status: String,
    @SerialName("failure_code") val failureCode: String?,
)

/* =====================================================================================
 * Domain enums (string-typed in the DB; Kotlin enums in app code).
 * ===================================================================================== */

enum class LadderStatus { PENDING, RUNNING, SUCCEEDED, FAILED }
enum class RungStatus { PENDING, SUCCEEDED, FAILED }
enum class RungKind { SOURCE, PROJECTION }

enum class BodyFatSource(val wireValue: String) {
    REQUEST("request"),
    GATEKEEPER_ESTIMATE("gatekeeper_estimate"),
}

/* =====================================================================================
 * Resolved-input bundle: what the orchestrator computed before generating.
 * Persisted on the ladder row and surfaced to the client in the SSE `status` event so
 * the user sees "we used these inputs" — particularly the photo-AI body-fat estimate
 * when they didn't provide one.
 * ===================================================================================== */

data class ResolvedLadderInputs(
    val currentWeightKg: Double,
    val currentWeightSource: ResolvedFieldSource,
    val currentBodyFatPercent: Double,
    val currentBodyFatSource: BodyFatSource,
    val currentBodyFatConfidence: Double?,
    val targetWeightKg: Double,
    val targetWeightSource: ResolvedFieldSource,
    val targetBodyFatPercent: Double,
    val targetBodyFatSource: ResolvedFieldSource,
)

enum class ResolvedFieldSource(val wireValue: String) {
    REQUEST("request"),
    WEIGHT_ENTRY("weight_entry"),
    USER_GOAL("user_goal"),
    GATEKEEPER_ESTIMATE("gatekeeper_estimate"),
}

/* =====================================================================================
 * Public DTOs for SSE events and any JSON responses.
 * ===================================================================================== */

@Serializable
data class ProjectionRungDto(
    val id: String,
    val ladderId: String,
    val stepIndex: Int,
    val kind: String,
    val projectedBodyFatPercent: Double,
    val projectedWeightKg: Double,
    val storagePath: String?,
    val status: String,
    val failureCode: String? = null,
)

@Serializable
data class LadderResolvedFieldDto(
    val value: Double,
    val source: String,
    val confidence: Double? = null,
)

@Serializable
data class LadderResolvedDto(
    val currentWeightKg: LadderResolvedFieldDto,
    val currentBodyFatPercent: LadderResolvedFieldDto,
    val targetWeightKg: LadderResolvedFieldDto,
    val targetBodyFatPercent: LadderResolvedFieldDto,
)

@Serializable
data class LadderStatusEventDto(
    val phase: String,                       // "validating" | "generating"
    val rungsTotal: Int,
    val rungsReady: Int,
    val ladderId: String,
    val resolved: LadderResolvedDto? = null, // null on the first "validating" event
)

@Serializable
data class LadderDoneEventDto(
    val ladderId: String,
    val rungsCount: Int,
)

@Serializable
data class LadderErrorEventDto(
    val message: String,
    val code: String,
)

fun AiProgressLadderRungRow.toDto(): ProjectionRungDto = ProjectionRungDto(
    id = id,
    ladderId = ladderId,
    stepIndex = stepIndex,
    kind = kind,
    projectedBodyFatPercent = projectedBodyFatPercent,
    projectedWeightKg = projectedWeightKg,
    storagePath = storagePath,
    status = status,
    failureCode = failureCode,
)
