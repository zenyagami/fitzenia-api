package com.zenthek.service

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

    suspend fun registerIfAbsent(accessToken: String, request: RegisterUserRequest): RegisterUserResponse {
        log.info("[USER] registerIfAbsent started")
        val authenticatedUser = supabaseGateway.validateAccessToken(accessToken).getOrElse { error ->
            throw mapTokenError(error)
        }
        log.info("[USER] token validated userId={}", authenticatedUser.id)

        validateRegisterRequest(request, authenticatedUser.id, authenticatedUser.email)
        log.info("[USER] payload validation passed userId={}", authenticatedUser.id)

        val profileExists = supabaseGateway.profileExists(authenticatedUser.id).getOrElse { error ->
            throw UpstreamFailureException("Unable to check user profile registration state: ${error.message}")
        }
        val userGoalExists = supabaseGateway.userGoalExists(authenticatedUser.id).getOrElse { error ->
            throw UpstreamFailureException("Unable to check user goal registration state: ${error.message}")
        }
        val calorieTargetExists = supabaseGateway.calorieTargetExists(authenticatedUser.id).getOrElse { error ->
            throw UpstreamFailureException("Unable to check calorie target registration state: ${error.message}")
        }
        log.info(
            "[USER] existing onboarding data userId={} profileExists={} userGoalExists={} calorieTargetExists={}",
            authenticatedUser.id,
            profileExists,
            userGoalExists,
            calorieTargetExists
        )

        val now = System.currentTimeMillis()
        val tokenEmail = authenticatedUser.email!!.trim()
        val profileForInsert = request.userProfile.toServerProfile(tokenEmail, now)
        val userGoalForInsert = request.userGoal.toServerUserGoal(now)
        val calorieTargetForInsert = request.calorieTarget.toServerCalorieTarget(now)

        var insertedAny = false

        if (!profileExists) {
            log.info("[USER] creating profile for userId={} profileId={}", authenticatedUser.id, profileForInsert.id)
            supabaseGateway.insertUserProfile(authenticatedUser.id, profileForInsert).getOrElse { error ->
                throw UpstreamFailureException("Unable to create user profile: ${error.message}")
            }
            log.info("[USER] profile created userId={} profileId={}", authenticatedUser.id, profileForInsert.id)
            insertedAny = true
        } else {
            log.info("[USER] profile already exists for userId={}, skipping insert", authenticatedUser.id)
        }

        if (!userGoalExists) {
            log.info("[USER] creating user_goal for userId={} goalId={}", authenticatedUser.id, userGoalForInsert.id)
            supabaseGateway.insertUserGoal(authenticatedUser.id, userGoalForInsert).getOrElse { error ->
                throw UpstreamFailureException("Unable to create user goal: ${error.message}")
            }
            log.info("[USER] user_goal created userId={} goalId={}", authenticatedUser.id, userGoalForInsert.id)
            insertedAny = true
        } else {
            log.info("[USER] user_goal already exists for userId={}, skipping insert", authenticatedUser.id)
        }

        if (!calorieTargetExists) {
            log.info(
                "[USER] creating calorie_target for userId={} calorieTargetId={}",
                authenticatedUser.id,
                calorieTargetForInsert.id
            )
            supabaseGateway.insertCalorieTarget(authenticatedUser.id, calorieTargetForInsert).getOrElse { error ->
                throw UpstreamFailureException("Unable to create calorie target: ${error.message}")
            }
            log.info(
                "[USER] calorie_target created userId={} calorieTargetId={}",
                authenticatedUser.id,
                calorieTargetForInsert.id
            )
            insertedAny = true
        } else {
            log.info("[USER] calorie_target already exists for userId={}, skipping insert", authenticatedUser.id)
        }

        return RegisterUserResponse(
            ok = true,
            status = if (insertedAny) "created" else "already_registered"
        )
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

    private fun validateRegisterRequest(
        request: RegisterUserRequest,
        authenticatedUserId: String,
        authenticatedEmail: String?
    ) {
        if (request.userProfile.name.isBlank()) throw IllegalArgumentException("userProfile.name must not be blank")
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

    private fun RegisterUserProfileInput.toServerProfile(tokenEmail: String, now: Long): UserProfileEntity = UserProfileEntity(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        email = tokenEmail,
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
