package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.CalorieTargetEntity
import com.zenthek.model.UserProfileEntity
import com.zenthek.model.UserGoalEntity
import com.zenthek.service.UnauthorizedException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

data class SupabaseAuthenticatedUser(
    val id: String,
    val email: String?,
)

interface SupabaseGateway {
    suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser>
    suspend fun profileExists(accessToken: String, userId: String): Result<Boolean>
    suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean>
    suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean>
    suspend fun insertUserProfile(accessToken: String, userId: String, profile: UserProfileEntity): Result<Unit>
    suspend fun insertUserGoal(accessToken: String, userId: String, userGoal: UserGoalEntity): Result<Unit>
    suspend fun insertCalorieTarget(accessToken: String, userId: String, calorieTarget: CalorieTargetEntity): Result<Unit>
}

class SupabaseClient(
    private val httpClient: HttpClient,
    private val config: SupabaseConfig,
) : SupabaseGateway {
    private val log = LoggerFactory.getLogger(SupabaseClient::class.java)

    private val normalizedBaseUrl = config.url.trimEnd('/')

    override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> = runCatching {
        log.info("[SUPABASE] fetching authenticated user")
        val response = httpClient.get("$normalizedBaseUrl/auth/v1/user") {
            applyUserScopedAuth(accessToken)
        }
        log.debug("[SUPABASE] auth validation status={}", response.status.value)

        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> {
                log.warn("[SUPABASE] access token rejected status={}", response.status.value)
                throw UnauthorizedException("Invalid or expired access token")
            }

            !response.status.isSuccess() -> {
                log.error("[SUPABASE] auth validation failed status={}", response.status.value)
                throw IllegalStateException("Supabase auth validation failed with ${response.status.value}")
            }
        }

        val user = response.body<SupabaseUserDto>()
        if (user.id.isBlank()) {
            throw UnauthorizedException("Invalid access token subject")
        }
        log.info("[SUPABASE] access token valid userId={}", user.id)
        SupabaseAuthenticatedUser(id = user.id, email = user.email)
    }

    override suspend fun profileExists(accessToken: String, userId: String): Result<Boolean> = existsInTable(
        tableName = "user_profile",
        accessToken = accessToken,
        userId = userId,
        label = "profile"
    )

    override suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean> = existsInTable(
        tableName = "user_goal",
        accessToken = accessToken,
        userId = userId,
        label = "goal"
    )

    override suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean> = existsInTable(
        tableName = "calorie_target",
        accessToken = accessToken,
        userId = userId,
        label = "calorie_target"
    )

    override suspend fun insertUserProfile(accessToken: String, userId: String, profile: UserProfileEntity): Result<Unit> = runCatching {
        log.info("[SUPABASE] inserting user_profile userId={} profileId={}", userId, profile.id)
        val response = httpClient.post("$normalizedBaseUrl/rest/v1/user_profile") {
            applyUserScopedAuth(accessToken)
            header(HttpHeaders.Prefer, "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(profile.toInsertDto(userId))
        }

        if (!response.status.isSuccess()) {
            log.error(
                "[SUPABASE] user_profile insert failed userId={} profileId={} status={}",
                userId,
                profile.id,
                response.status.value
            )
            throw IllegalStateException("Supabase profile insert failed with ${response.status.value}")
        }
        log.info("[SUPABASE] user_profile insert success userId={} profileId={}", userId, profile.id)
    }

    override suspend fun insertUserGoal(accessToken: String, userId: String, userGoal: UserGoalEntity): Result<Unit> = runCatching {
        log.info("[SUPABASE] inserting user_goal userId={} goalId={}", userId, userGoal.id)
        val response = httpClient.post("$normalizedBaseUrl/rest/v1/user_goal") {
            applyUserScopedAuth(accessToken)
            header(HttpHeaders.Prefer, "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(userGoal.toInsertDto(userId))
        }

        if (!response.status.isSuccess()) {
            log.error(
                "[SUPABASE] user_goal insert failed userId={} goalId={} status={}",
                userId,
                userGoal.id,
                response.status.value
            )
            throw IllegalStateException("Supabase user_goal insert failed with ${response.status.value}")
        }
        log.info("[SUPABASE] user_goal insert success userId={} goalId={}", userId, userGoal.id)
    }

    override suspend fun insertCalorieTarget(
        accessToken: String,
        userId: String,
        calorieTarget: CalorieTargetEntity,
    ): Result<Unit> = runCatching {
        log.info("[SUPABASE] inserting calorie_target userId={} calorieTargetId={}", userId, calorieTarget.id)
        val response = httpClient.post("$normalizedBaseUrl/rest/v1/calorie_target") {
            applyUserScopedAuth(accessToken)
            header(HttpHeaders.Prefer, "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(calorieTarget.toInsertDto(userId))
        }

        if (!response.status.isSuccess()) {
            log.error(
                "[SUPABASE] calorie_target insert failed userId={} calorieTargetId={} status={}",
                userId,
                calorieTarget.id,
                response.status.value
            )
            throw IllegalStateException("Supabase calorie_target insert failed with ${response.status.value}")
        }
        log.info("[SUPABASE] calorie_target insert success userId={} calorieTargetId={}", userId, calorieTarget.id)
    }

    private suspend fun existsInTable(
        tableName: String,
        accessToken: String,
        userId: String,
        label: String,
    ): Result<Boolean> = runCatching {
        log.info("[SUPABASE] checking {} for userId={}", tableName, userId)
        val response = httpClient.get("$normalizedBaseUrl/rest/v1/$tableName") {
            applyUserScopedAuth(accessToken)
            parameter("select", "id")
            parameter("user_id", "eq.$userId")
            parameter("limit", 1)
        }

        if (!response.status.isSuccess()) {
            log.error("[SUPABASE] {} lookup failed userId={} status={}", tableName, userId, response.status.value)
            throw IllegalStateException("Supabase $tableName lookup failed with ${response.status.value}")
        }

        val exists = response.body<List<SupabaseEntityIdDto>>().isNotEmpty()
        log.info("[SUPABASE] {} exists check userId={} exists={}", label, userId, exists)
        exists
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyUserScopedAuth(accessToken: String) {
        header("apikey", config.publicApiKey)
        bearerAuth(accessToken)
    }
}

private fun UserProfileEntity.toInsertDto(userId: String): SupabaseInsertUserProfileDto = SupabaseInsertUserProfileDto(
    id = id,
    name = name,
    email = email,
    birthDate = birthDate,
    sex = sex,
    heightCm = heightCm,
    createdAt = createdAt,
    lastModifiedAt = lastModifiedAt,
    userId = userId,
)

@Serializable
private data class SupabaseUserDto(
    val id: String,
    val email: String? = null,
)

@Serializable
private data class SupabaseEntityIdDto(
    val id: String,
)

@Serializable
private data class SupabaseInsertUserProfileDto(
    val id: String,
    val name: String,
    val email: String,
    @SerialName("birth_date")
    val birthDate: String,
    val sex: String,
    @SerialName("height_cm")
    val heightCm: Double,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("last_modified_at")
    val lastModifiedAt: Long,
    @SerialName("user_id")
    val userId: String,
)

private fun UserGoalEntity.toInsertDto(userId: String): SupabaseInsertUserGoalDto = SupabaseInsertUserGoalDto(
    id = id,
    goalDirection = goalDirection,
    targetPhase = targetPhase,
    goalWeightKg = goalWeightKg,
    paceTier = paceTier,
    activityLevel = activityLevel,
    bodyFatPercent = bodyFatPercent,
    bodyFatRangeKey = bodyFatRangeKey,
    exerciseFrequency = exerciseFrequency,
    stepsActivityBand = stepsActivityBand,
    liftingExperience = liftingExperience,
    proteinPreference = proteinPreference,
    createdAt = createdAt,
    lastModifiedAt = lastModifiedAt,
    userId = userId,
)

@Serializable
private data class SupabaseInsertUserGoalDto(
    val id: String,
    @SerialName("goal_direction")
    val goalDirection: String,
    @SerialName("target_phase")
    val targetPhase: String,
    @SerialName("goal_weight_kg")
    val goalWeightKg: Double? = null,
    @SerialName("pace_tier")
    val paceTier: String,
    @SerialName("activity_level")
    val activityLevel: String,
    @SerialName("body_fat_percent")
    val bodyFatPercent: Double,
    @SerialName("body_fat_range_key")
    val bodyFatRangeKey: String,
    @SerialName("exercise_frequency")
    val exerciseFrequency: String,
    @SerialName("steps_activity_band")
    val stepsActivityBand: String,
    @SerialName("lifting_experience")
    val liftingExperience: String,
    @SerialName("protein_preference")
    val proteinPreference: String,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("last_modified_at")
    val lastModifiedAt: Long,
    @SerialName("user_id")
    val userId: String,
)

private fun CalorieTargetEntity.toInsertDto(userId: String): SupabaseInsertCalorieTargetDto = SupabaseInsertCalorieTargetDto(
    id = id,
    formula = formula,
    bmrKcal = bmrKcal,
    tdeeKcal = tdeeKcal,
    targetKcal = targetKcal,
    targetMinKcal = targetMinKcal,
    targetMaxKcal = targetMaxKcal,
    macroMode = macroMode,
    proteinTargetG = proteinTargetG,
    carbsTargetG = carbsTargetG,
    fatTargetG = fatTargetG,
    appliedPaceTier = appliedPaceTier,
    floorClamped = floorClamped,
    warning = warning?.name,
    createdAt = createdAt,
    lastModifiedAt = lastModifiedAt,
    userId = userId,
)

@Serializable
private data class SupabaseInsertCalorieTargetDto(
    val id: String,
    val formula: String,
    @SerialName("bmr_kcal")
    val bmrKcal: Long,
    @SerialName("tdee_kcal")
    val tdeeKcal: Long,
    @SerialName("target_kcal")
    val targetKcal: Long,
    @SerialName("target_min_kcal")
    val targetMinKcal: Long,
    @SerialName("target_max_kcal")
    val targetMaxKcal: Long,
    @SerialName("macro_mode")
    val macroMode: String,
    @SerialName("protein_target_g")
    val proteinTargetG: Long,
    @SerialName("carbs_target_g")
    val carbsTargetG: Long,
    @SerialName("fat_target_g")
    val fatTargetG: Long,
    @SerialName("applied_pace_tier")
    val appliedPaceTier: String,
    @SerialName("floor_clamped")
    val floorClamped: Long,
    val warning: String? = null,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("last_modified_at")
    val lastModifiedAt: Long,
    @SerialName("user_id")
    val userId: String,
)
