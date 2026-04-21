package com.zenthek.auth

import com.zenthek.config.SupabaseJwtVerificationMode
import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.fitzenio.rest.configureStatusPages
import com.zenthek.upstream.supabase.ExistingUserProfileIdentity
import com.zenthek.upstream.supabase.SupabaseAuthenticatedUser
import com.zenthek.upstream.supabase.SupabaseGateway
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SupabaseAuthenticationTest {

    @Test
    fun `jwt auth accepts a valid token for a known kid`() {
        val primaryKey = generateTestRsaKeyPair("kid-1")
        val secondaryKey = generateTestRsaKeyPair("kid-2")
        TestJwksServer(listOf(primaryKey, secondaryKey)).use { jwksServer ->
            val accessToken = createSupabaseAccessToken(
                baseUrl = jwksServer.baseUrl,
                keyPair = secondaryKey,
                userMetadata = mapOf(
                    "full_name" to "JWT User",
                    "picture" to "https://example.com/jwt.png",
                ),
            )

            testApplication {
                installAuthOnlyApp(jwksServer.baseUrl)

                val response = client.get("/me") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("user-1|JWT User|https://example.com/jwt.png", response.bodyAsText())
            }
        }
    }

    @Test
    fun `jwt auth rejects unknown kid and expired tokens`() {
        val keyPair = generateTestRsaKeyPair("kid-known")
        val unknownKidKeyPair = generateTestRsaKeyPair("kid-unknown")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val unknownKidToken = createSupabaseAccessToken(
                baseUrl = jwksServer.baseUrl,
                keyPair = unknownKidKeyPair,
            )
            val expiredToken = createSupabaseAccessToken(
                baseUrl = jwksServer.baseUrl,
                keyPair = keyPair,
                expiresAt = java.util.Date(System.currentTimeMillis() - 60_000),
            )

            testApplication {
                installAuthOnlyApp(jwksServer.baseUrl)

                val unknownKidResponse = client.get("/me") {
                    header(HttpHeaders.Authorization, "Bearer $unknownKidToken")
                }
                assertEquals(HttpStatusCode.Unauthorized, unknownKidResponse.status)

                val expiredResponse = client.get("/me") {
                    header(HttpHeaders.Authorization, "Bearer $expiredToken")
                }
                assertEquals(HttpStatusCode.Unauthorized, expiredResponse.status)
            }
        }
    }

    @Test
    fun `remote mode accepts bearer when gateway confirms the user`() {
        val gateway = object : SupabaseGateway by NoOpSupabaseGateway() {
            override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> {
                assertEquals("remote-valid-token", accessToken)
                return Result.success(
                    SupabaseAuthenticatedUser(
                        id = "user-remote",
                        email = "remote@example.com",
                        name = "Remote User",
                        avatarUrl = "https://example.com/remote.png",
                    )
                )
            }
        }

        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAuthentication(
                    createTestSupabaseConfig("http://unused.example", mode = SupabaseJwtVerificationMode.REMOTE),
                    gateway,
                )
                routing {
                    authenticate(SUPABASE_AUTH_PROVIDER) {
                        get("/me") {
                            val user = call.requireAuthenticatedUser()
                            val token = call.requireBearerAccessToken()
                            call.respondText("${user.userId}|${user.name}|${user.avatarUrl}|$token")
                        }
                    }
                }
            }

            val response = client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer remote-valid-token")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "user-remote|Remote User|https://example.com/remote.png|remote-valid-token",
                response.bodyAsText()
            )
        }
    }

    @Test
    fun `remote mode rejects bearer when gateway fails`() {
        val gateway = object : SupabaseGateway by NoOpSupabaseGateway() {
            override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> {
                return Result.failure(RuntimeException("boom"))
            }
        }

        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAuthentication(
                    createTestSupabaseConfig("http://unused.example", mode = SupabaseJwtVerificationMode.REMOTE),
                    gateway,
                )
                routing {
                    authenticate(SUPABASE_AUTH_PROVIDER) {
                        get("/me") {
                            call.respondText(call.requireAuthenticatedUser().userId)
                        }
                    }
                }
            }

            val response = client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer broken-token")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installAuthOnlyApp(baseUrl: String) {
        application {
            configureSerialization()
            configureStatusPages()
            configureAuthentication(createTestSupabaseConfig(baseUrl), NoOpSupabaseGateway())
            routing {
                authenticate(SUPABASE_AUTH_PROVIDER) {
                    get("/me") {
                        val user = call.requireAuthenticatedUser()
                        call.respondText("${user.userId}|${user.name}|${user.avatarUrl}")
                    }
                }
            }
        }
    }
}

private class NoOpSupabaseGateway : SupabaseGateway {
    override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> {
        return Result.success(SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"))
    }

    override suspend fun profileExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun fetchUserProfileIdentity(accessToken: String, userId: String): Result<ExistingUserProfileIdentity?> {
        return Result.success(null)
    }

    override suspend fun updateUserProfileIdentity(
        accessToken: String,
        userId: String,
        name: String?,
        email: String?,
        avatarUrl: String?,
        lastModifiedAt: Long,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun insertUserProfile(
        accessToken: String,
        userId: String,
        profile: com.zenthek.model.UserProfileEntity,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun insertUserGoal(
        accessToken: String,
        userId: String,
        userGoal: com.zenthek.model.UserGoalEntity,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun insertCalorieTarget(
        accessToken: String,
        userId: String,
        calorieTarget: com.zenthek.model.CalorieTargetEntity,
    ): Result<Unit> = Result.success(Unit)
}
