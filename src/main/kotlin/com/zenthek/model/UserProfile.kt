package com.zenthek.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfileEntity(
    val id: String,
    val name: String,
    val email: String,
    val birthDate: String,
    val sex: String,
    val heightCm: Double,
    val createdAt: Long,
    val lastModifiedAt: Long,
    val syncStatus: String,
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
data class ErrorResponse(
    val error: String,
)
