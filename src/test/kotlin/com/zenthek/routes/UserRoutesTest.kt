package com.zenthek.routes

import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.fitzenio.rest.configureStatusPages
import com.zenthek.model.CalorieTargetEntity
import com.zenthek.model.RegisterCalorieTargetInput
import com.zenthek.model.RegisterUserGoalInput
import com.zenthek.model.RegisterUserProfileInput
import com.zenthek.model.RegisterUserRequest
import com.zenthek.model.RegisterUserResponse
import com.zenthek.model.RegistrationStatusResponse
import com.zenthek.model.UserGoalEntity
import com.zenthek.model.UserProfileEntity
import com.zenthek.service.UserProfileService
import com.zenthek.upstream.supabase.SupabaseAuthenticatedUser
import com.zenthek.upstream.supabase.SupabaseGateway
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register creates onboarding data and skips overwrite when already registered`() = testApplication {
        val gateway = FakeSupabaseGateway(
            profileExistsInitially = false,
            userGoalExistsInitially = false,
            calorieTargetExistsInitially = false,
        )
        val service = UserProfileService(gateway)
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                route("/api/user") {
                    configureUserRoutes(service)
                }
            }
        }

        val payload = validRegisterRequest()
        val requestJson = json.encodeToString(payload)

        val firstResponse = client.post("/api/user/register") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstPayload = json.decodeFromString<RegisterUserResponse>(firstResponse.bodyAsText())
        assertTrue(firstPayload.ok)
        assertEquals("created", firstPayload.status)
        assertEquals(1, gateway.profileInsertCalls)
        assertEquals(1, gateway.userGoalInsertCalls)
        assertEquals(1, gateway.calorieTargetInsertCalls)
        assertEquals("test@example.com", gateway.lastInsertedProfile?.email)
        assertTrue(gateway.lastInsertedProfile?.id?.isNotBlank() == true)
        assertTrue((gateway.lastInsertedProfile?.createdAt ?: 0L) > 0L)
        assertTrue((gateway.lastInsertedProfile?.lastModifiedAt ?: 0L) > 0L)

        val secondResponse = client.post("/api/user/register") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        val secondPayload = json.decodeFromString<RegisterUserResponse>(secondResponse.bodyAsText())
        assertTrue(secondPayload.ok)
        assertEquals("already_registered", secondPayload.status)
        assertEquals(1, gateway.profileInsertCalls)
        assertEquals(1, gateway.userGoalInsertCalls)
        assertEquals(1, gateway.calorieTargetInsertCalls)
    }

    @Test
    fun `register returns 400 on invalid body`() = testApplication {
        val service = UserProfileService(
            FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                route("/api/user") {
                    configureUserRoutes(service)
                }
            }
        }

        val response = client.post("/api/user/register") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody("""{"userProfile":{"name":"","birthDate":"1990-01-01","sex":"female","heightCm":170.0},"userGoal":{"id":"g","goalDirection":"LOSE","targetPhase":"PHASE","goalWeightKg":70.0,"paceTier":"MODERATE","activityLevel":"LIGHT","bodyFatPercent":20.0,"bodyFatRangeKey":"TIER_3","exerciseFrequency":"ONE_TO_THREE","stepsActivityBand":"SEDENTARY","liftingExperience":"NONE","proteinPreference":"MODERATE"},"calorieTarget":{"id":"c","formula":"MIFLIN","bmrKcal":1600,"tdeeKcal":2200,"targetKcal":1900,"targetMinKcal":1800,"targetMaxKcal":2000,"macroMode":"BALANCED","proteinTargetG":150,"carbsTargetG":180,"fatTargetG":60,"appliedPaceTier":"MODERATE","floorClamped":0,"warning":null}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration-status returns true when profile exists`() = testApplication {
        val service = UserProfileService(
            FakeSupabaseGateway(
                profileExistsInitially = true,
                userGoalExistsInitially = true,
                calorieTargetExistsInitially = true,
            )
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                route("/api/user") {
                    configureUserRoutes(service)
                }
            }
        }

        val response = client.get("/api/user/registration-status") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<RegistrationStatusResponse>(response.bodyAsText())
        assertTrue(payload.isSignedIn)
        assertTrue(payload.isRegistered)
    }

    @Test
    fun `registration-status returns false when profile does not exist`() = testApplication {
        val service = UserProfileService(
            FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                route("/api/user") {
                    configureUserRoutes(service)
                }
            }
        }

        val response = client.get("/api/user/registration-status") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<RegistrationStatusResponse>(response.bodyAsText())
        assertFalse(payload.isRegistered)
    }

    @Test
    fun `user routes return 401 when bearer token is missing`() = testApplication {
        val service = UserProfileService(
            FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                route("/api/user") {
                    configureUserRoutes(service)
                }
            }
        }

        val registerResponse = client.post("/api/user/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(validRegisterRequest()))
        }
        assertEquals(HttpStatusCode.Unauthorized, registerResponse.status)

        val statusResponse = client.get("/api/user/registration-status")
        assertEquals(HttpStatusCode.Unauthorized, statusResponse.status)
    }

    private fun validRegisterRequest() = RegisterUserRequest(
        userProfile = RegisterUserProfileInput(
            name = "Test User",
            birthDate = "1990-01-01",
            sex = "female",
            heightCm = 168.0,
        ),
        userGoal = RegisterUserGoalInput(
            id = "",
            goalDirection = "LOSE",
            targetPhase = "CUT",
            goalWeightKg = 62.0,
            paceTier = "MODERATE",
            activityLevel = "LIGHT",
            bodyFatPercent = 20.0,
            bodyFatRangeKey = "TIER_3",
            exerciseFrequency = "ONE_TO_THREE",
            stepsActivityBand = "SEDENTARY",
            liftingExperience = "NONE",
            proteinPreference = "MODERATE",
        ),
        calorieTarget = RegisterCalorieTargetInput(
            id = "",
            formula = "MIFLIN",
            bmrKcal = 1500,
            tdeeKcal = 2100,
            targetKcal = 1800,
            targetMinKcal = 1700,
            targetMaxKcal = 1900,
            macroMode = "BALANCED",
            proteinTargetG = 130,
            carbsTargetG = 180,
            fatTargetG = 55,
            appliedPaceTier = "MODERATE",
            floorClamped = 0,
            warning = null,
        ),
    )
}

private class FakeSupabaseGateway(
    profileExistsInitially: Boolean,
    userGoalExistsInitially: Boolean,
    calorieTargetExistsInitially: Boolean,
) : SupabaseGateway {
    var profileInsertCalls = 0
    var userGoalInsertCalls = 0
    var calorieTargetInsertCalls = 0
    var lastInsertedProfile: UserProfileEntity? = null
    var lastInsertedUserGoal: UserGoalEntity? = null
    var lastInsertedCalorieTarget: CalorieTargetEntity? = null
    private var profileExists = profileExistsInitially
    private var userGoalExists = userGoalExistsInitially
    private var calorieTargetExists = calorieTargetExistsInitially

    override suspend fun validateAccessToken(accessToken: String): Result<SupabaseAuthenticatedUser> {
        return if (accessToken == "valid-token") {
            Result.success(SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"))
        } else {
            Result.failure(IllegalStateException("invalid token"))
        }
    }

    override suspend fun profileExists(userId: String): Result<Boolean> = Result.success(profileExists)

    override suspend fun userGoalExists(userId: String): Result<Boolean> = Result.success(userGoalExists)

    override suspend fun calorieTargetExists(userId: String): Result<Boolean> = Result.success(calorieTargetExists)

    override suspend fun insertUserProfile(userId: String, profile: UserProfileEntity): Result<Unit> {
        profileInsertCalls += 1
        lastInsertedProfile = profile
        profileExists = true
        return Result.success(Unit)
    }

    override suspend fun insertUserGoal(userId: String, userGoal: UserGoalEntity): Result<Unit> {
        userGoalInsertCalls += 1
        lastInsertedUserGoal = userGoal
        userGoalExists = true
        return Result.success(Unit)
    }

    override suspend fun insertCalorieTarget(userId: String, calorieTarget: CalorieTargetEntity): Result<Unit> {
        calorieTargetInsertCalls += 1
        lastInsertedCalorieTarget = calorieTarget
        calorieTargetExists = true
        return Result.success(Unit)
    }
}
