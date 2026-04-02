package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.UserProfileEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
    fun `validateAccessToken returns user identity on success`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/auth/v1/user", request.url.encodedPath)
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            assertEquals("service-role-key", request.headers["apikey"])
            respond(
                content = """{"id":"user-123","email":"test@example.com"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.validateAccessToken("access-token")
        assertTrue(result.isSuccess)
        assertEquals("user-123", result.getOrThrow().id)
    }

    @Test
    fun `validateAccessToken returns failure on unauthorized token`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"msg":"invalid token"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.validateAccessToken("bad-token")
        assertTrue(result.isFailure)
    }

    @Test
    fun `profileExists returns true when row is found`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/user_profile", request.url.encodedPath)
            assertEquals("eq.user-123", request.url.parameters["user_id"])
            respond(
                content = """[{"id":"profile-1"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = buildClient(engine)

        val result = client.profileExists("user-123")
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

        val result = client.profileExists("user-123")
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `insertUserProfile succeeds on 201`() = runBlocking {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.Created)
        }
        val client = buildClient(engine)

        val result = client.insertUserProfile(
            userId = "user-123",
            profile = sampleProfile(),
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
                serviceRoleKey = "service-role-key",
            ),
        )
    }

    private fun sampleProfile() = UserProfileEntity(
        id = "profile-1",
        name = "Test User",
        email = "test@example.com",
        birthDate = "1990-01-01",
        sex = "female",
        heightCm = 170.0,
        createdAt = 1_700_000_000_000,
        lastModifiedAt = 1_700_000_000_000,
        syncStatus = "PENDING",
    )
}
