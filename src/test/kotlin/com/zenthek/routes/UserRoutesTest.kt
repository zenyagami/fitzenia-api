package com.zenthek.routes

import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.fitzenio.rest.configureStatusPages
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register creates profile when absent and skips overwrite when already registered`() = testApplication {
        val gateway = FakeSupabaseGateway(profileExistsInitially = false)
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

        val payload = validProfile()
        val requestJson = json.encodeToString(payload)

        val firstResponse = client.post("/api/user/register") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstPayload = json.parseToJsonElement(firstResponse.bodyAsText()).jsonObject
        assertTrue(firstPayload["ok"]?.jsonPrimitive?.boolean == true)
        assertEquals("created", firstPayload["status"]?.jsonPrimitive?.content)

        val secondResponse = client.post("/api/user/register") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        val secondPayload = json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject
        assertTrue(secondPayload["ok"]?.jsonPrimitive?.boolean == true)
        assertEquals("already_registered", secondPayload["status"]?.jsonPrimitive?.content)
        assertEquals(1, gateway.insertCalls)
        assertEquals("SYNCED", gateway.lastInsertedProfile?.syncStatus)
    }

    @Test
    fun `register returns 400 on invalid body`() = testApplication {
        val service = UserProfileService(FakeSupabaseGateway(profileExistsInitially = false))
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
            setBody("""{"id":"only-id"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration-status returns true when profile exists`() = testApplication {
        val service = UserProfileService(FakeSupabaseGateway(profileExistsInitially = true))
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
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(payload["isSignedIn"]?.jsonPrimitive?.boolean == true)
        assertTrue(payload["isRegistered"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `registration-status returns false when profile does not exist`() = testApplication {
        val service = UserProfileService(FakeSupabaseGateway(profileExistsInitially = false))
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
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(payload["isRegistered"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `user routes return 401 when bearer token is missing`() = testApplication {
        val service = UserProfileService(FakeSupabaseGateway(profileExistsInitially = false))
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
            setBody(json.encodeToString(validProfile()))
        }
        assertEquals(HttpStatusCode.Unauthorized, registerResponse.status)

        val statusResponse = client.get("/api/user/registration-status")
        assertEquals(HttpStatusCode.Unauthorized, statusResponse.status)
    }

    private fun validProfile() = UserProfileEntity(
        id = "profile-1",
        name = "Test User",
        email = "test@example.com",
        birthDate = "1990-01-01",
        sex = "female",
        heightCm = 168.0,
        createdAt = 1_700_000_000_000,
        lastModifiedAt = 1_700_000_000_000,
        syncStatus = "PENDING",
    )
}

private class FakeSupabaseGateway(
    profileExistsInitially: Boolean,
) : SupabaseGateway {
    var insertCalls = 0
    var lastInsertedProfile: UserProfileEntity? = null
    private var profileExists = profileExistsInitially

    override suspend fun validateAccessToken(accessToken: String): Result<SupabaseAuthenticatedUser> {
        return if (accessToken == "valid-token") {
            Result.success(SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"))
        } else {
            Result.failure(IllegalStateException("invalid token"))
        }
    }

    override suspend fun profileExists(userId: String): Result<Boolean> {
        return Result.success(profileExists)
    }

    override suspend fun insertUserProfile(userId: String, profile: UserProfileEntity): Result<Unit> {
        insertCalls += 1
        lastInsertedProfile = profile
        profileExists = true
        return Result.success(Unit)
    }
}
