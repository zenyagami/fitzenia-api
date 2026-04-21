package com.zenthek.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfileEntity(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val birthDate: String,
    val sex: String,
    val heightCm: Double,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

@Serializable
data class RegisterUserRequest(
    val userProfile: RegisterUserProfileInput,
    val userGoal: RegisterUserGoalInput,
    val calorieTarget: RegisterCalorieTargetInput,
)

@Serializable
data class RegisterUserResponse(
    val ok: Boolean,
    val status: String,
)

@Serializable
data class RegistrationStatusResponse(
    val isSignedIn: Boolean,
    val isRegistered: Boolean,
)

@Serializable
data class UserGoalEntity(
    val id: String,
    val goalDirection: String,
    val targetPhase: String,
    val goalWeightKg: Double? = null,
    val paceTier: String,
    val activityLevel: String,
    val bodyFatPercent: Double,
    val bodyFatRangeKey: String,
    val exerciseFrequency: String,
    val stepsActivityBand: String,
    val liftingExperience: String,
    val proteinPreference: String,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

@Serializable
data class CalorieTargetEntity(
    val id: String,
    val formula: String,
    val bmrKcal: Long,
    val tdeeKcal: Long,
    val targetKcal: Long,
    val targetMinKcal: Long,
    val targetMaxKcal: Long,
    val macroMode: String,
    val proteinTargetG: Long,
    val carbsTargetG: Long,
    val fatTargetG: Long,
    val appliedPaceTier: String,
    val floorClamped: Long,
    val warning: CalorieWarning? = null,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

@Serializable
data class RegisterUserProfileInput(
    val birthDate: String,
    val sex: String,
    val heightCm: Double,
)

@Serializable
data class RegisterUserGoalInput(
    val id: String? = null,
    val goalDirection: String,
    val targetPhase: String,
    val goalWeightKg: Double? = null,
    val paceTier: String,
    val activityLevel: String,
    val bodyFatPercent: Double,
    val bodyFatRangeKey: String,
    val exerciseFrequency: String,
    val stepsActivityBand: String,
    val liftingExperience: String,
    val proteinPreference: String,
)

@Serializable
data class RegisterCalorieTargetInput(
    val id: String? = null,
    val formula: String,
    val bmrKcal: Long,
    val tdeeKcal: Long,
    val targetKcal: Long,
    val targetMinKcal: Long,
    val targetMaxKcal: Long,
    val macroMode: String,
    val proteinTargetG: Long,
    val carbsTargetG: Long,
    val fatTargetG: Long,
    val appliedPaceTier: String,
    val floorClamped: Long,
    val warning: CalorieWarning? = null,
)

@Serializable
enum class CalorieWarning {
    FLOOR_CLAMPED,
    PACE_DOWNGRADED,
}

@Serializable
data class ErrorResponse(
    val error: String,
)
