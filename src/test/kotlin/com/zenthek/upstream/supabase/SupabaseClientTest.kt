package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.UserProfileEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseClientTest {

    @Test
    fun `fetchAuthenticatedUser returns user identity and metadata on success`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/auth/v1/user", request.url.encodedPath)
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            assertEquals("publishable-key", request.headers["apikey"])
            respond(
                content = """
                    {
                      "id":"user-123",
                      "email":"test@example.com",
                      "user_metadata":{
                        "name":"Test User",
                        "avatar_url":"https://example.com/avatar.png"
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchAuthenticatedUser("access-token")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("user-123", user.id)
        assertEquals("test@example.com", user.email)
        assertEquals("Test User", user.name)
        assertEquals("https://example.com/avatar.png", user.avatarUrl)
    }

    @Test
    fun `fetchAuthenticatedUser falls back to secondary metadata keys and ignores blanks`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "id":"user-123",
                      "email":"test@example.com",
                      "user_metadata":{
                        "name":" ",
                        "full_name":"Fallback User",
                        "avatar_url":" ",
                        "picture":"https://example.com/picture.png"
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchAuthenticatedUser("access-token")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("Fallback User", user.name)
        assertEquals("https://example.com/picture.png", user.avatarUrl)
    }

    @Test
    fun `fetchAuthenticatedUser falls back to identity data when user metadata is empty`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "id":"user-123",
                      "email":"test@example.com",
                      "user_metadata":{},
                      "identities":[
                        {
                          "identity_data":{
                            "full_name":"Identity User",
                            "avatar_url":"https://example.com/identity-avatar.png"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchAuthenticatedUser("access-token")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("Identity User", user.name)
        assertEquals("https://example.com/identity-avatar.png", user.avatarUrl)
    }

    @Test
    fun `fetchAuthenticatedUser scans all identities for avatar fallback`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "id":"user-123",
                      "email":"test@example.com",
                      "user_metadata":{},
                      "identities":[
                        {
                          "identity_data":{
                            "full_name":"Email Identity"
                          }
                        },
                        {
                          "identity_data":{
                            "avatar_url":"https://example.com/second-identity-avatar.png"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchAuthenticatedUser("access-token")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("Email Identity", user.name)
        assertEquals("https://example.com/second-identity-avatar.png", user.avatarUrl)
    }

    @Test
    fun `fetchAuthenticatedUser returns failure on unauthorized token`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"msg":"invalid token"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchAuthenticatedUser("bad-token")
        assertTrue(result.isFailure)
    }

    @Test
    fun `profileExists returns true when row is found`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/user_profile", request.url.encodedPath)
            assertEquals("eq.user-123", request.url.parameters["user_id"])
            assertEquals("publishable-key", request.headers["apikey"])
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """[{"id":"profile-1"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.profileExists(accessToken = "access-token", userId = "user-123")
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `profileExists returns false when row is missing`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.profileExists(accessToken = "access-token", userId = "user-123")
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `fetchUserProfileIdentity returns name and avatar for existing row`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/user_profile", request.url.encodedPath)
            assertEquals("id,name,email,avatar_url", request.url.parameters["select"])
            assertEquals("eq.user-123", request.url.parameters["user_id"])
            respond(
                content = """[{"id":"profile-1","name":"","email":"","avatar_url":null}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.fetchUserProfileIdentity(accessToken = "access-token", userId = "user-123")

        assertTrue(result.isSuccess)
        assertEquals("profile-1", result.getOrThrow()?.id)
        assertEquals("", result.getOrThrow()?.name)
        assertEquals("", result.getOrThrow()?.email)
        assertEquals(null, result.getOrThrow()?.avatarUrl)
    }

    @Test
    fun `updateUserProfileIdentity sends only provided fields plus last_modified_at`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/user_profile", request.url.encodedPath)
            assertEquals("eq.user-123", request.url.parameters["user_id"])
            val bodyText = request.bodyText()
            assertTrue(bodyText.contains("\"email\":\"filled@example.com\""))
            assertTrue(bodyText.contains("\"avatar_url\":\"https://example.com/avatar.png\""))
            assertTrue(bodyText.contains("\"last_modified_at\":1700000000000"))
            assertFalse(bodyText.contains("\"name\""))
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = buildClient(engine)

        val result = client.updateUserProfileIdentity(
            accessToken = "access-token",
            userId = "user-123",
            name = null,
            email = "filled@example.com",
            avatarUrl = "https://example.com/avatar.png",
            lastModifiedAt = 1_700_000_000_000,
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message ?: "expected success")
    }

    @Test
    fun `insertUserProfile succeeds on 201 and includes avatar_url`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/user_profile", request.url.encodedPath)
            assertEquals("publishable-key", request.headers["apikey"])
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            val bodyText = request.bodyText()
            assertTrue(bodyText.contains("\"avatar_url\":\"https://example.com/avatar.png\""))
            assertTrue(bodyText.contains("\"email\":\"test@example.com\""))
            respond(content = "", status = HttpStatusCode.Created)
        }
        val client = buildClient(engine)

        val result = client.insertUserProfile(
            accessToken = "access-token",
            userId = "user-123",
            profile = sampleProfile(avatarUrl = "https://example.com/avatar.png"),
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message ?: "expected success")
    }

    @Test
    fun `insertUserProfile returns failure on upstream error`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"message":"db down"}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.insertUserProfile(
            accessToken = "access-token",
            userId = "user-123",
            profile = sampleProfile(),
        )
        assertTrue(result.isFailure)
    }

    private fun buildClient(engine: MockEngine): SupabaseClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return SupabaseClient(
            httpClient = httpClient,
            config = SupabaseConfig(
                url = "https://example.supabase.co",
                publishableKey = "publishable-key",
                legacyAnonKey = null,
                jwtVerificationMode = com.zenthek.config.SupabaseJwtVerificationMode.JWKS,
            ),
        )
    }

    private fun sampleProfile(avatarUrl: String? = null) = UserProfileEntity(
        id = "profile-1",
        name = "Test User",
        email = "test@example.com",
        avatarUrl = avatarUrl,
        birthDate = "1990-01-01",
        sex = "female",
        heightCm = 170.0,
        createdAt = 1_700_000_000_000,
        lastModifiedAt = 1_700_000_000_000,
    )
}

private fun HttpRequestData.bodyText(): String {
    val content = body
    return when (content) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unsupported request body type: ${content::class}")
    }
}
