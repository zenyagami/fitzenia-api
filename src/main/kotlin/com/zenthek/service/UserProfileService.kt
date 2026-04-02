package com.zenthek.service

import com.zenthek.model.RegisterUserResponse
import com.zenthek.model.RegistrationStatusResponse
import com.zenthek.model.UserProfileEntity
import com.zenthek.upstream.supabase.SupabaseGateway
import org.slf4j.LoggerFactory

class UserProfileService(
    private val supabaseGateway: SupabaseGateway,
) {
    private val log = LoggerFactory.getLogger(UserProfileService::class.java)
    private companion object {
        const val REGISTERED_SYNC_STATUS = "SYNCED"
    }

    suspend fun registerIfAbsent(accessToken: String, profile: UserProfileEntity): RegisterUserResponse {
        log.info("[USER] registerIfAbsent started profileId={}", profile.id)
        val authenticatedUser = supabaseGateway.validateAccessToken(accessToken).getOrElse { error ->
            throw mapTokenError(error)
        }
        log.info("[USER] token validated userId={}", authenticatedUser.id)

        validateProfilePayload(profile, authenticatedUser.id, authenticatedUser.email)
        log.info("[USER] payload validation passed userId={} profileId={}", authenticatedUser.id, profile.id)

        val profileExists = supabaseGateway.profileExists(authenticatedUser.id).getOrElse { error ->
            throw UpstreamFailureException("Unable to check user profile registration state: ${error.message}")
        }
        log.info("[USER] existing profile check userId={} exists={}", authenticatedUser.id, profileExists)

        if (profileExists) {
            log.info("Skipping profile overwrite for existing user_id={}", authenticatedUser.id)
            return RegisterUserResponse(ok = true, status = "already_registered")
        }

        val profileForInsert = profile.copy(syncStatus = REGISTERED_SYNC_STATUS)
        if (profile.syncStatus != REGISTERED_SYNC_STATUS) {
            log.info(
                "[USER] normalizing syncStatus for new registration userId={} profileId={} from={} to={}",
                authenticatedUser.id,
                profile.id,
                profile.syncStatus,
                REGISTERED_SYNC_STATUS
            )
        }

        log.info("[USER] creating profile for userId={} profileId={}", authenticatedUser.id, profile.id)
        supabaseGateway.insertUserProfile(authenticatedUser.id, profileForInsert).getOrElse { error ->
            throw UpstreamFailureException("Unable to create user profile: ${error.message}")
        }
        log.info("[USER] profile created userId={} profileId={}", authenticatedUser.id, profile.id)

        return RegisterUserResponse(ok = true, status = "created")
    }

    suspend fun getRegistrationStatus(accessToken: String): RegistrationStatusResponse {
        log.info("[USER] getRegistrationStatus started")
        val authenticatedUser = supabaseGateway.validateAccessToken(accessToken).getOrElse { error ->
            throw mapTokenError(error)
        }
        log.info("[USER] token validated for registration-status userId={}", authenticatedUser.id)

        val profileExists = supabaseGateway.profileExists(authenticatedUser.id).getOrElse { error ->
            throw UpstreamFailureException("Unable to read registration status: ${error.message}")
        }
        log.info("[USER] registration-status resolved userId={} isRegistered={}", authenticatedUser.id, profileExists)

        return RegistrationStatusResponse(
            isSignedIn = true,
            isRegistered = profileExists,
        )
    }

    private fun validateProfilePayload(profile: UserProfileEntity, authenticatedUserId: String, authenticatedEmail: String?) {
        if (profile.id.isBlank()) throw IllegalArgumentException("id must not be blank")
        if (profile.name.isBlank()) throw IllegalArgumentException("name must not be blank")
        if (profile.birthDate.isBlank()) throw IllegalArgumentException("birthDate must not be blank")
        if (profile.sex.isBlank()) throw IllegalArgumentException("sex must not be blank")
        if (profile.heightCm <= 0.0) throw IllegalArgumentException("heightCm must be greater than 0")
        if (profile.syncStatus.isBlank()) throw IllegalArgumentException("syncStatus must not be blank")
        if (profile.email.isBlank()) throw IllegalArgumentException("email must not be blank")

        val tokenEmail = authenticatedEmail?.trim()?.lowercase()
        if (!tokenEmail.isNullOrBlank() && tokenEmail != profile.email.trim().lowercase()) {
            log.warn("[USER] email mismatch for userId={}", authenticatedUserId)
            throw IllegalArgumentException("email does not match authenticated user")
        }
    }

    private fun mapTokenError(error: Throwable): Throwable {
        return when (error) {
            is UnauthorizedException -> {
                log.warn("[USER] token validation failed: {}", error.message)
                error
            }

            else -> {
                log.error("[USER] unexpected token validation failure", error)
                UnauthorizedException("Invalid or expired access token")
            }
        }
    }
}
