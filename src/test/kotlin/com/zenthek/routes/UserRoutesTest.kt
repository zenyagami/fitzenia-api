package com.zenthek.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.TestJwksServer
import com.zenthek.auth.createSupabaseAccessToken
import com.zenthek.auth.createTestSupabaseConfig
import com.zenthek.auth.generateTestRsaKeyPair
import com.zenthek.auth.configureAuthentication
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
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register creates onboarding data and skips overwrite when already registered`() {
        val keyPair = generateTestRsaKeyPair("user-routes")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val payload = validRegisterRequest()
                val requestJson = json.encodeToString(payload)

                val firstResponse = client.post("/api/user/register") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
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
                assertEquals(accessToken, gateway.lastAccessToken)
                assertEquals("test@example.com", gateway.lastInsertedProfile?.email)
                assertTrue(gateway.lastInsertedProfile?.id?.isNotBlank() == true)
                assertTrue((gateway.lastInsertedProfile?.createdAt ?: 0L) > 0L)
                assertTrue((gateway.lastInsertedProfile?.lastModifiedAt ?: 0L) > 0L)

                val secondResponse = client.post("/api/user/register") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
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
        }
    }

    @Test
    fun `register falls back to Supabase user lookup when JWT email is missing`() {
        val keyPair = generateTestRsaKeyPair("user-routes-fallback")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
                fetchedUser = SupabaseAuthenticatedUser(id = "user-1", email = "fallback@example.com"),
            )
            val accessToken = createSupabaseAccessToken(
                baseUrl = jwksServer.baseUrl,
                keyPair = keyPair,
                email = null,
            )

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val response = client.post("/api/user/register") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(validRegisterRequest()))
                }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(1, gateway.fetchAuthenticatedUserCalls)
                assertEquals("fallback@example.com", gateway.lastInsertedProfile?.email)
            }
        }
    }

    @Test
    fun `register returns 400 on invalid body`() {
        val keyPair = generateTestRsaKeyPair("user-routes-invalid")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val response = client.post("/api/user/register") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userProfile":{"name":"Test User","birthDate":"","sex":"female","heightCm":170.0},"userGoal":{"id":"g","goalDirection":"LOSE","targetPhase":"PHASE","goalWeightKg":70.0,"paceTier":"MODERATE","activityLevel":"LIGHT","bodyFatPercent":20.0,"bodyFatRangeKey":"TIER_3","exerciseFrequency":"ONE_TO_THREE","stepsActivityBand":"SEDENTARY","liftingExperience":"NONE","proteinPreference":"MODERATE"},"calorieTarget":{"id":"c","formula":"MIFLIN","bmrKcal":1600,"tdeeKcal":2200,"targetKcal":1900,"targetMinKcal":1800,"targetMaxKcal":2000,"macroMode":"BALANCED","proteinTargetG":150,"carbsTargetG":180,"fatTargetG":60,"appliedPaceTier":"MODERATE","floorClamped":0,"warning":null}}""")
                }

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }
    }

    @Test
    fun `registration-status returns true when profile exists`() {
        val keyPair = generateTestRsaKeyPair("user-routes-status-true")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = true,
                userGoalExistsInitially = true,
                calorieTargetExistsInitially = true,
            )
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val response = client.get("/api/user/registration-status") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val payload = json.decodeFromString<RegistrationStatusResponse>(response.bodyAsText())
                assertTrue(payload.isSignedIn)
                assertTrue(payload.isRegistered)
            }
        }
    }

    @Test
    fun `registration-status returns false when profile does not exist`() {
        val keyPair = generateTestRsaKeyPair("user-routes-status-false")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val response = client.get("/api/user/registration-status") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val payload = json.decodeFromString<RegistrationStatusResponse>(response.bodyAsText())
                assertFalse(payload.isRegistered)
            }
        }
    }

    @Test
    fun `user routes return 401 when bearer token is missing`() {
        val keyPair = generateTestRsaKeyPair("user-routes-missing")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val gateway = FakeSupabaseGateway(
                profileExistsInitially = false,
                userGoalExistsInitially = false,
                calorieTargetExistsInitially = false,
            )

            testApplication {
                installUserRoutesApp(jwksServer.baseUrl, gateway)

                val registerResponse = client.post("/api/user/register") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(validRegisterRequest()))
                }
                assertEquals(HttpStatusCode.Unauthorized, registerResponse.status)

                val statusResponse = client.get("/api/user/registration-status")
                assertEquals(HttpStatusCode.Unauthorized, statusResponse.status)
            }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installUserRoutesApp(
        baseUrl: String,
        gateway: FakeSupabaseGateway,
    ) {
        val service = UserProfileService(gateway)
        application {
            configureSerialization()
            configureStatusPages()
            configureAuthentication(createTestSupabaseConfig(baseUrl), gateway)
            routing {
                authenticate(SUPABASE_AUTH_PROVIDER) {
                    route("/api/user") {
                        configureUserRoutes(service)
                    }
                }
            }
        }
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
    private val fetchedUser: SupabaseAuthenticatedUser = SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"),
) : SupabaseGateway {
    var profileInsertCalls = 0
    var userGoalInsertCalls = 0
    var calorieTargetInsertCalls = 0
    var fetchAuthenticatedUserCalls = 0
    var lastInsertedProfile: UserProfileEntity? = null
    var lastInsertedUserGoal: UserGoalEntity? = null
    var lastInsertedCalorieTarget: CalorieTargetEntity? = null
    var lastAccessToken: String? = null
    private var profileExists = profileExistsInitially
    private var userGoalExists = userGoalExistsInitially
    private var calorieTargetExists = calorieTargetExistsInitially

    override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> {
        fetchAuthenticatedUserCalls += 1
        lastAccessToken = accessToken
        return Result.success(fetchedUser)
    }

    override suspend fun profileExists(accessToken: String, userId: String): Result<Boolean> {
        lastAccessToken = accessToken
        return Result.success(profileExists)
    }

    override suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean> {
        lastAccessToken = accessToken
        return Result.success(userGoalExists)
    }

    override suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean> {
        lastAccessToken = accessToken
        return Result.success(calorieTargetExists)
    }

    override suspend fun insertUserProfile(accessToken: String, userId: String, profile: UserProfileEntity): Result<Unit> {
        profileInsertCalls += 1
        lastInsertedProfile = profile
        lastAccessToken = accessToken
        profileExists = true
        return Result.success(Unit)
    }

    override suspend fun insertUserGoal(accessToken: String, userId: String, userGoal: UserGoalEntity): Result<Unit> {
        userGoalInsertCalls += 1
        lastInsertedUserGoal = userGoal
        lastAccessToken = accessToken
        userGoalExists = true
        return Result.success(Unit)
    }

    override suspend fun insertCalorieTarget(
        accessToken: String,
        userId: String,
        calorieTarget: CalorieTargetEntity,
    ): Result<Unit> {
        calorieTargetInsertCalls += 1
        lastInsertedCalorieTarget = calorieTarget
        lastAccessToken = accessToken
        calorieTargetExists = true
        return Result.success(Unit)
    }
}
