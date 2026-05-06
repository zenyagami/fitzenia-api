package com.zenthek.ingest.writer

import com.zenthek.ingest.OffMirrorRow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OffMirrorWriterTest {

    @Test
    fun `upsertBatch returns parsed counts on success`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/rpc/upsert_off_products", request.url.encodedPath)
            assertEquals("service-key", request.headers["apikey"])
            assertEquals("Bearer service-key", request.headers[HttpHeaders.Authorization])
            assertTrue(request.bodyText().contains("\"items\""))
            respondJson("""[{"inserted":2,"updated":3}]""")
        }
        val writer = buildWriter(engine)

        val counts = writer.upsertBatch(listOf(row("1"), row("2")))

        assertEquals(2, counts.inserted)
        assertEquals(3, counts.updated)
    }

    @Test
    fun `upsertBatch splits statement timeout batches and sums retry counts`() = runBlocking {
        var calls = 0
        val requestSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            calls += 1
            requestSizes += request.bodyText().countOccurrences("\"code\"")
            when (calls) {
                1 -> respondJson(
                    """{"code":"57014","details":null,"hint":null,"message":"canceling statement due to statement timeout"}""",
                    HttpStatusCode.InternalServerError,
                )
                2 -> respondJson("""[{"inserted":1,"updated":1}]""")
                3 -> respondJson("""[{"inserted":2,"updated":0}]""")
                else -> error("unexpected retry $calls")
            }
        }
        val writer = buildWriter(engine)

        val counts = writer.upsertBatch(listOf(row("1"), row("2"), row("3"), row("4")))

        assertEquals(listOf(4, 2, 2), requestSizes)
        assertEquals(3, counts.inserted)
        assertEquals(1, counts.updated)
    }

    @Test
    fun `upsertBatch splits client request timeout batches and sums retry counts`() = runBlocking {
        var calls = 0
        val requestSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            calls += 1
            requestSizes += request.bodyText().countOccurrences("\"code\"")
            when (calls) {
                1 -> throw HttpRequestTimeoutException(request)
                2 -> respondJson("""[{"inserted":0,"updated":2}]""")
                3 -> respondJson("""[{"inserted":1,"updated":1}]""")
                else -> error("unexpected retry $calls")
            }
        }
        val writer = buildWriter(engine)

        val counts = writer.upsertBatch(listOf(row("1"), row("2"), row("3"), row("4")))

        assertEquals(listOf(4, 2, 2), requestSizes)
        assertEquals(1, counts.inserted)
        assertEquals(3, counts.updated)
    }

    @Test
    fun `upsertBatch fails non timeout Supabase errors`() = runBlocking {
        val engine = MockEngine {
            respondJson(
                """{"code":"23505","details":null,"hint":null,"message":"duplicate key value violates unique constraint"}""",
                HttpStatusCode.InternalServerError,
            )
        }
        val writer = buildWriter(engine)

        val failure = assertFailsWith<IllegalStateException> {
            writer.upsertBatch(listOf(row("1"), row("2")))
        }
        assertTrue(failure.message.orEmpty().contains("500"))
    }

    private fun buildWriter(engine: MockEngine): OffMirrorWriter {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return OffMirrorWriter(
            httpClient = httpClient,
            supabaseUrl = "https://example.supabase.co",
            serviceRoleKey = "service-key",
        )
    }

    private fun row(code: String) = OffMirrorRow(
        code = code,
        productName = "Food $code",
        lastModifiedT = 1_700_000_000L + code.toLong(),
    )

    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
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

private fun String.countOccurrences(needle: String): Int {
    var count = 0
    var start = 0
    while (true) {
        val index = indexOf(needle, start)
        if (index < 0) return count
        count += 1
        start = index + needle.length
    }
}
