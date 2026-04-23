package com.zenthek.service

import com.zenthek.auth.AuthenticatedUserContext
import com.zenthek.model.CalorieTargetEntity
import com.zenthek.model.RegisterCalorieTargetInput
import com.zenthek.model.RegisterUserGoalInput
import com.zenthek.model.RegisterUserProfileInput
import com.zenthek.model.RegisterUserRequest
import com.zenthek.model.RegisterUserResponse
import com.zenthek.model.RegistrationStatusResponse
import com.zenthek.model.UserGoalEntity
import com.zenthek.model.UserProfileEntity
import com.zenthek.upstream.supabase.SupabaseGateway
import org.slf4j.LoggerFactory
import java.util.UUID

class UserProfileService(
    private val supabaseGateway: SupabaseGateway,
) {
    private val log = LoggerFactory.getLogger(UserProfileService::class.java)

    suspend fun registerIfAbsent(
        authenticatedUser: AuthenticatedUserContext,
        accessToken: String,
        request: RegisterUserRequest,
    ): RegisterUserResponse {
        log.info("[USER] registerIfAbsent started userId={}", authenticatedUser.userId)
        val authenticatedProfile = resolveAuthenticatedProfile(authenticatedUser, accessToken)

        validateRegisterRequest(request, authenticatedUser.userId, authenticatedProfile.email)
        log.info("[USER] payload validation passed userId={}", authenticatedUser.userId)

        val profileExists = supabaseGateway.profileExists(accessToken, authenticatedUser.userId).getOrElse { error ->
            throw UpstreamFailureException("Unable to check user profile registration state: ${error.message}")
        }
        val userGoalExists = supabaseGateway.userGoalExists(accessToken, authenticatedUser.userId).getOrElse { error ->
            throw UpstreamFailureException("Unable to check user goal registration state: ${error.message}")
        }
        val calorieTargetExists = supabaseGateway.calorieTargetExists(accessToken, authenticatedUser.userId).getOrElse { error ->
            throw UpstreamFailureException("Unable to check calorie target registration state: ${error.message}")
        }
        log.info(
            "[USER] existing onboarding data userId={} profileExists={} userGoalExists={} calorieTargetExists={}",
            authenticatedUser.userId,
            profileExists,
            userGoalExists,
            calorieTargetExists
        )

        val now = System.currentTimeMillis()
        val profileForInsert = request.userProfile.toServerProfile(authenticatedProfile, authenticatedUser.userId, now)
        val userGoalForInsert = request.userGoal.toServerUserGoal(now)
        val calorieTargetForInsert = request.calorieTarget.toServerCalorieTarget(now)

        var insertedAny = false

        if (!profileExists) {
            log.info("[USER] creating profile for userId={} profileId={}", authenticatedUser.userId, profileForInsert.id)
            supabaseGateway.insertUserProfile(accessToken, authenticatedUser.userId, profileForInsert).getOrElse { error ->
                if (error is UnauthorizedException) throw error
                throw UpstreamFailureException("Unable to create user profile: ${error.message}")
            }
            log.info("[USER] profile created userId={} profileId={}", authenticatedUser.userId, profileForInsert.id)
            insertedAny = true
        } else {
            maybeBackfillExistingProfileIdentity(accessToken, authenticatedUser.userId, authenticatedProfile, now)
            log.info("[USER] profile already exists for userId={}, skipping insert", authenticatedUser.userId)
        }

        if (!userGoalExists) {
            log.info("[USER] creating user_goal for userId={} goalId={}", authenticatedUser.userId, userGoalForInsert.id)
            supabaseGateway.insertUserGoal(accessToken, authenticatedUser.userId, userGoalForInsert).getOrElse { error ->
                if (error is UnauthorizedException) throw error
                throw UpstreamFailureException("Unable to create user goal: ${error.message}")
            }
            log.info("[USER] user_goal created userId={} goalId={}", authenticatedUser.userId, userGoalForInsert.id)
            insertedAny = true
        } else {
            log.info("[USER] user_goal already exists for userId={}, skipping insert", authenticatedUser.userId)
        }

        if (!calorieTargetExists) {
            log.info(
                "[USER] creating calorie_target for userId={} calorieTargetId={}",
                authenticatedUser.userId,
                calorieTargetForInsert.id
            )
            supabaseGateway.insertCalorieTarget(accessToken, authenticatedUser.userId, calorieTargetForInsert).getOrElse { error ->
                if (error is UnauthorizedException) throw error
                throw UpstreamFailureException("Unable to create calorie target: ${error.message}")
            }
            log.info(
                "[USER] calorie_target created userId={} calorieTargetId={}",
                authenticatedUser.userId,
                calorieTargetForInsert.id
            )
            insertedAny = true
        } else {
            log.info("[USER] calorie_target already exists for userId={}, skipping insert", authenticatedUser.userId)
        }

        return RegisterUserResponse(
            ok = true,
            status = if (insertedAny) "created" else "already_registered"
        )
    }

    suspend fun getRegistrationStatus(
        authenticatedUser: AuthenticatedUserContext,
        accessToken: String,
    ): RegistrationStatusResponse {
        log.info("[USER] getRegistrationStatus started userId={}", authenticatedUser.userId)

        val profileExists = supabaseGateway.profileExists(accessToken, authenticatedUser.userId).getOrElse { error ->
            throw UpstreamFailureException("Unable to read registration status: ${error.message}")
        }
        log.info("[USER] registration-status resolved userId={} isRegistered={}", authenticatedUser.userId, profileExists)

        return RegistrationStatusResponse(
            isSignedIn = true,
            isRegistered = profileExists,
        )
    }

    private fun validateRegisterRequest(
        request: RegisterUserRequest,
        authenticatedUserId: String,
        authenticatedEmail: String?
    ) {
        if (request.userProfile.birthDate.isBlank()) throw IllegalArgumentException("userProfile.birthDate must not be blank")
        if (request.userProfile.sex.isBlank()) throw IllegalArgumentException("userProfile.sex must not be blank")
        if (request.userProfile.heightCm <= 0.0) throw IllegalArgumentException("userProfile.heightCm must be greater than 0")
        if (request.userGoal.goalDirection.isBlank()) throw IllegalArgumentException("userGoal.goalDirection must not be blank")
        if (request.userGoal.targetPhase.isBlank()) throw IllegalArgumentException("userGoal.targetPhase must not be blank")
        if (request.userGoal.paceTier.isBlank()) throw IllegalArgumentException("userGoal.paceTier must not be blank")
        if (request.userGoal.activityLevel.isBlank()) throw IllegalArgumentException("userGoal.activityLevel must not be blank")
        if (request.userGoal.bodyFatRangeKey.isBlank()) throw IllegalArgumentException("userGoal.bodyFatRangeKey must not be blank")
        if (request.userGoal.exerciseFrequency.isBlank()) throw IllegalArgumentException("userGoal.exerciseFrequency must not be blank")
        if (request.userGoal.stepsActivityBand.isBlank()) throw IllegalArgumentException("userGoal.stepsActivityBand must not be blank")
        if (request.userGoal.liftingExperience.isBlank()) throw IllegalArgumentException("userGoal.liftingExperience must not be blank")
        if (request.userGoal.proteinPreference.isBlank()) throw IllegalArgumentException("userGoal.proteinPreference must not be blank")
        if (request.calorieTarget.formula.isBlank()) throw IllegalArgumentException("calorieTarget.formula must not be blank")
        if (request.calorieTarget.macroMode.isBlank()) throw IllegalArgumentException("calorieTarget.macroMode must not be blank")
        if (request.calorieTarget.appliedPaceTier.isBlank()) {
            throw IllegalArgumentException("calorieTarget.appliedPaceTier must not be blank")
        }
        if (authenticatedEmail.isNullOrBlank()) {
            log.warn("[USER] authenticated email missing for userId={}", authenticatedUserId)
            throw IllegalArgumentException("authenticated user email is required")
        }
    }

    private suspend fun resolveAuthenticatedProfile(
        authenticatedUser: AuthenticatedUserContext,
        accessToken: String,
    ): ResolvedAuthenticatedProfile {
        var email = authenticatedUser.email.normalizeOptionalField()
        var name = authenticatedUser.name.normalizeOptionalField()
        var avatarUrl = authenticatedUser.avatarUrl.normalizeOptionalField()

        if (email == null || name == null || avatarUrl == null) {
            log.info(
                "[USER] auth profile incomplete, falling back to Supabase user lookup userId={} missingEmail={} missingName={} missingAvatarUrl={}",
                authenticatedUser.userId,
                email == null,
                name == null,
                avatarUrl == null
            )
            val fetchedUser = fetchAuthenticatedUserOrThrow(authenticatedUser, accessToken)
            email = email ?: fetchedUser.email.normalizeOptionalField()
            name = name ?: fetchedUser.name.normalizeOptionalField()
            avatarUrl = avatarUrl ?: fetchedUser.avatarUrl.normalizeOptionalField()
        }

        val resolvedEmail = email ?: throw IllegalArgumentException("authenticated user email is required")
        return ResolvedAuthenticatedProfile(
            email = resolvedEmail,
            name = name ?: resolvedEmail.substringBefore("@"),
            avatarUrl = avatarUrl,
        )
    }

    private suspend fun fetchAuthenticatedUserOrThrow(
        authenticatedUser: AuthenticatedUserContext,
        accessToken: String,
    ) = supabaseGateway.fetchAuthenticatedUser(accessToken).getOrElse { error ->
        when (error) {
            is UnauthorizedException -> {
                log.warn("[USER] token validation failed during auth profile fallback: {}", error.message)
                throw error
            }

            else -> {
                log.error("[USER] unexpected Supabase auth lookup failure", error)
                throw UpstreamFailureException("Unable to resolve authenticated user profile")
            }
        }
    }.also { fetchedUser ->
        if (fetchedUser.id != authenticatedUser.userId) {
            log.warn(
                "[USER] authenticated user mismatch between JWT and Supabase lookup jwtUserId={} supabaseUserId={}",
                authenticatedUser.userId,
                fetchedUser.id
            )
            throw UnauthorizedException("Invalid or expired access token")
        }
    }

    private suspend fun maybeBackfillExistingProfileIdentity(
        accessToken: String,
        userId: String,
        authenticatedProfile: ResolvedAuthenticatedProfile,
        now: Long,
    ) {
        val existingProfile = supabaseGateway.fetchUserProfileIdentity(accessToken, userId).getOrElse { error ->
            throw UpstreamFailureException("Unable to read existing user profile: ${error.message}")
        } ?: throw UpstreamFailureException("Unable to read existing user profile")

        val nameUpdate = authenticatedProfile.name.takeIf { existingProfile.name.isBlank() }
        val emailUpdate = authenticatedProfile.email.takeIf { existingProfile.email.isBlank() }
        val avatarUpdate = authenticatedProfile.avatarUrl?.takeIf { existingProfile.avatarUrl.isNullOrBlank() }

        if (nameUpdate == null && emailUpdate == null && avatarUpdate == null) {
            return
        }

        supabaseGateway.updateUserProfileIdentity(
            accessToken = accessToken,
            userId = userId,
            name = nameUpdate,
            email = emailUpdate,
            avatarUrl = avatarUpdate,
            lastModifiedAt = now,
        ).getOrElse { error ->
            throw UpstreamFailureException("Unable to update user profile identity: ${error.message}")
        }
        log.info(
            "[USER] backfilled existing profile identity userId={} updatedName={} updatedEmail={} updatedAvatar={}",
            userId,
            nameUpdate != null,
            emailUpdate != null,
            avatarUpdate != null
        )
    }

    private fun RegisterUserProfileInput.toServerProfile(
        authenticatedProfile: ResolvedAuthenticatedProfile,
        userId: String,
        now: Long,
    ): UserProfileEntity = UserProfileEntity(
        id = userId,
        name = authenticatedProfile.name,
        email = authenticatedProfile.email,
        avatarUrl = authenticatedProfile.avatarUrl,
        birthDate = birthDate.trim(),
        sex = sex.trim(),
        heightCm = heightCm,
        createdAt = now,
        lastModifiedAt = now,
    )

    private fun RegisterUserGoalInput.toServerUserGoal(now: Long): UserGoalEntity = UserGoalEntity(
        id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        goalDirection = goalDirection.trim(),
        targetPhase = targetPhase.trim(),
        goalWeightKg = goalWeightKg,
        paceTier = paceTier.trim(),
        activityLevel = activityLevel.trim(),
        bodyFatPercent = bodyFatPercent,
        bodyFatRangeKey = bodyFatRangeKey.trim(),
        exerciseFrequency = exerciseFrequency.trim(),
        stepsActivityBand = stepsActivityBand.trim(),
        liftingExperience = liftingExperience.trim(),
        proteinPreference = proteinPreference.trim(),
        createdAt = now,
        lastModifiedAt = now,
    )

    private fun RegisterCalorieTargetInput.toServerCalorieTarget(now: Long): CalorieTargetEntity = CalorieTargetEntity(
        id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        formula = formula.trim(),
        bmrKcal = bmrKcal,
        tdeeKcal = tdeeKcal,
        targetKcal = targetKcal,
        targetMinKcal = targetMinKcal,
        targetMaxKcal = targetMaxKcal,
        macroMode = macroMode.trim(),
        proteinTargetG = proteinTargetG,
        carbsTargetG = carbsTargetG,
        fatTargetG = fatTargetG,
        appliedPaceTier = appliedPaceTier.trim(),
        floorClamped = floorClamped,
        warning = warning,
        createdAt = now,
        lastModifiedAt = now,
    )
}

private data class ResolvedAuthenticatedProfile(
    val email: String,
    val name: String,
    val avatarUrl: String?,
)

private fun String?.normalizeOptionalField(): String? {
    return this?.trim()?.ifBlank { null }
}
