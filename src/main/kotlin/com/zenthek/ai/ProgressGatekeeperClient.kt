package com.zenthek.ai

import kotlinx.serialization.Serializable

/**
 * Server-side gate that runs once per generate request before any expensive image-edit calls.
 * Validates the uploaded photo is suitable (front-pose body photo of an adult, not NSFW, etc.)
 * and as a free byproduct returns a body-fat percentage estimate that can be used when the
 * client did not supply one.
 */
fun interface ProgressGatekeeperClient {
    suspend fun verify(imageBytes: ByteArray, mimeType: String): GatekeeperVerdict
}

@Serializable
data class GatekeeperVerdict(
    val isAcceptable: Boolean,
    val rejectionReasons: List<GatekeeperRejectionReason> = emptyList(),
    val confidence: Double = 0.0,
    val estimatedBodyFatPercent: Double? = null,
    val estimatedBodyFatConfidence: Double? = null,
    val estimatedBodyFatNotes: String? = null,
    val model: String,
)

@Serializable
enum class GatekeeperRejectionReason {
    NOT_BODY_PHOTO,
    NOT_FRONT_FACING,
    MULTIPLE_PEOPLE,
    MINOR_DETECTED,
    NSFW,
    TOO_LOW_QUALITY,
    FACE_NOT_VISIBLE,
}
