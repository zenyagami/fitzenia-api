package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.UserProfileEntity
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
    suspend fun validateAccessToken(accessToken: String): Result<SupabaseAuthenticatedUser>
    suspend fun profileExists(userId: String): Result<Boolean>
    suspend fun insertUserProfile(userId: String, profile: UserProfileEntity): Result<Unit>
}

class SupabaseClient(
    private val httpClient: HttpClient,
    private val config: SupabaseConfig,
) : SupabaseGateway {
    private val log = LoggerFactory.getLogger(SupabaseClient::class.java)

    private val normalizedBaseUrl = config.url.trimEnd('/')

    override suspend fun validateAccessToken(accessToken: String): Result<SupabaseAuthenticatedUser> = runCatching {
        log.info("[SUPABASE] validating access token")
        val response = httpClient.get("$normalizedBaseUrl/auth/v1/user") {
            header("apikey", config.serviceRoleKey)
            bearerAuth(accessToken)
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

    override suspend fun profileExists(userId: String): Result<Boolean> = runCatching {
        log.info("[SUPABASE] checking user_profile for userId={}", userId)
        val response = httpClient.get("$normalizedBaseUrl/rest/v1/user_profile") {
            header("apikey", config.serviceRoleKey)
            bearerAuth(config.serviceRoleKey)
            parameter("select", "id")
            parameter("user_id", "eq.$userId")
            parameter("limit", 1)
        }

        if (!response.status.isSuccess()) {
            log.error("[SUPABASE] user_profile lookup failed userId={} status={}", userId, response.status.value)
            throw IllegalStateException("Supabase profile lookup failed with ${response.status.value}")
        }

        val exists = response.body<List<SupabaseUserProfileIdDto>>().isNotEmpty()
        log.info("[SUPABASE] user_profile exists check userId={} exists={}", userId, exists)
        exists
    }

    override suspend fun insertUserProfile(userId: String, profile: UserProfileEntity): Result<Unit> = runCatching {
        log.info("[SUPABASE] inserting user_profile userId={} profileId={}", userId, profile.id)
        val response = httpClient.post("$normalizedBaseUrl/rest/v1/user_profile") {
            header("apikey", config.serviceRoleKey)
            bearerAuth(config.serviceRoleKey)
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
    syncStatus = syncStatus,
    userId = userId,
)

@Serializable
private data class SupabaseUserDto(
    val id: String,
    val email: String? = null,
)

@Serializable
private data class SupabaseUserProfileIdDto(
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
    @SerialName("sync_status")
    val syncStatus: String,
    @SerialName("user_id")
    val userId: String,
)
