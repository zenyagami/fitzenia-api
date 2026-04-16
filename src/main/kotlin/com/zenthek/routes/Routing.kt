package com.zenthek.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.auth.requireBearerAccessToken
import com.zenthek.model.AnalyzeImageRequest
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.RegisterUserRequest
import com.zenthek.service.FoodService
import com.zenthek.model.SearchStreamBestMatch
import com.zenthek.model.SmartSearchResponse
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.service.UserProfileService
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

object RateLimitNames {
    const val FOOD_SEARCH = "food-search"
    const val IMAGE_ANALYSIS = "image-analysis"
}

private val sseJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("com.zenthek.routes.UserRoutes")

private suspend fun ByteWriteChannel.sendSseEvent(event: String, data: String) {
    writeFully("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

fun Application.configureRouting(
    foodService: FoodService,
    smartSearch: SmartSearchOrchestrator,
    imageAnalyzer: ImageAnalyzer,
    userProfileService: UserProfileService,
) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        authenticate(SUPABASE_AUTH_PROVIDER) {
            route("/api/food") {
                configureFoodRoutes(foodService, smartSearch, imageAnalyzer)
            }

            route("/api/user") {
                configureUserRoutes(userProfileService)
            }
        }
    }
}

fun Route.configureFoodRoutes(
    foodService: FoodService,
    smartSearch: SmartSearchOrchestrator,
    imageAnalyzer: ImageAnalyzer,
) {
    rateLimit(RateLimitName(RateLimitNames.FOOD_SEARCH)) {
        get("/autocomplete") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val query = call.request.queryParameters["q"]?.trim()
                ?: throw IllegalArgumentException("Missing required parameter: q")
            if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            if (limit > 25) throw IllegalArgumentException("limit cannot exceed 25")

            log.debug("[FOOD] autocomplete userId={} query={}", authenticatedUser.userId, query)
            val suggestions = foodService.autocomplete(query, limit)
            call.respond(HttpStatusCode.OK, mapOf("suggestions" to suggestions))
        }

        get("/search") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val query = call.request.queryParameters["q"]?.trim()
                ?: throw IllegalArgumentException("Missing required parameter: q")
            if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")

            val locale = call.request.queryParameters["locale"]?.trim()
                ?: throw IllegalArgumentException("Missing required parameter: locale")
            if (locale.isBlank()) throw IllegalArgumentException("Parameter 'locale' must not be blank")

            val country = call.request.queryParameters["country"]?.trim()?.ifBlank { null }
            val ipCountry = extractIpCountry(call.request.headers)

            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 25
            if (pageSize > 50) throw IllegalArgumentException("pageSize cannot exceed 50")

            log.debug(
                "[FOOD] search userId={} query={} locale={} country={} ipCountry={} page={} pageSize={}",
                authenticatedUser.userId, query, locale, country, ipCountry, page, pageSize
            )
            val response = smartSearch.search(query, locale, country, page, pageSize, ipCountry)

            call.respond(HttpStatusCode.OK, response)
        }

        get("/search/stream") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val query = call.request.queryParameters["q"]?.trim()
                ?: throw IllegalArgumentException("Missing required parameter: q")
            if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")

            val locale = call.request.queryParameters["locale"]?.trim()
                ?: throw IllegalArgumentException("Missing required parameter: locale")
            if (locale.isBlank()) throw IllegalArgumentException("Parameter 'locale' must not be blank")

            val country = call.request.queryParameters["country"]?.trim()?.ifBlank { null }
            val ipCountry = extractIpCountry(call.request.headers)

            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 25
            if (pageSize > 50) throw IllegalArgumentException("pageSize cannot exceed 50")

            log.debug(
                "[FOOD] search/stream userId={} query={} locale={} country={} ipCountry={} page={} pageSize={}",
                authenticatedUser.userId, query, locale, country, ipCountry, page, pageSize
            )

            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                try {
                    smartSearch.searchAsFlow(query, locale, country, page, pageSize, ipCountry)
                        .collect { event ->
                            when (event) {
                                is SmartSearchOrchestrator.SearchStreamEvent.Upstream ->
                                    sendSseEvent(
                                        "upstream",
                                        sseJson.encodeToString(SmartSearchResponse.serializer(), event.response)
                                    )
                                is SmartSearchOrchestrator.SearchStreamEvent.BestMatch ->
                                    sendSseEvent(
                                        "bestMatch",
                                        sseJson.encodeToString(SearchStreamBestMatch.serializer(), event.payload)
                                    )
                                SmartSearchOrchestrator.SearchStreamEvent.Done ->
                                    sendSseEvent("done", "{}")
                            }
                        }
                } catch (e: Exception) {
                    application.log.error("SSE search/stream failed", e)
                    sendSseEvent("error", """{"message":"Search failed"}""")
                }
            }
        }

        get("/barcode/{barcode}") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val barcode = call.parameters["barcode"]?.trim()
                ?: throw IllegalArgumentException("Missing barcode path parameter")
            if (barcode.isBlank() || !barcode.all { it.isDigit() }) {
                throw IllegalArgumentException("Barcode must contain only digits")
            }

            log.debug("[FOOD] barcode lookup userId={} barcode={}", authenticatedUser.userId, barcode)
            val result = foodService.getByBarcode(barcode)

            call.respond(
                HttpStatusCode.OK,
                mapOf("result" to result)
            )
        }
    }

    rateLimit(RateLimitName(RateLimitNames.IMAGE_ANALYSIS)) {
        post("/analyze-image") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val body = call.receive<AnalyzeImageRequest>()
            val imageBytes = java.util.Base64.getDecoder().decode(body.image)
            log.debug("[FOOD] analyze-image userId={} locale={}", authenticatedUser.userId, body.locale)
            val result = imageAnalyzer.analyzeImage(
                imageBytes,
                body.mealTitle,
                body.additionalContext,
                body.locale,
                "image/jpeg"
            )
            call.respond(HttpStatusCode.OK, result)
        }

        post("/analyze-image-stream") {
            val authenticatedUser = call.requireAuthenticatedUser()
            val body = call.receive<AnalyzeImageRequest>()
            val imageBytes = java.util.Base64.getDecoder().decode(body.image)
            log.debug("[FOOD] analyze-image-stream userId={} locale={}", authenticatedUser.userId, body.locale)
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                sendSseEvent("status", """{"phase":"analyzing"}""")
                try {
                    val result = imageAnalyzer.analyzeImage(
                        imageBytes,
                        body.mealTitle,
                        body.additionalContext,
                        body.locale,
                        "image/jpeg"
                    )
                    sendSseEvent("result", sseJson.encodeToString(ImageAnalysisResponse.serializer(), result))
                } catch (e: Exception) {
                    application.log.error("SSE analyze-image-stream failed", e)
                    sendSseEvent("error", """{"message":"Analysis failed"}""")
                }
            }
        }
    }
}

fun Route.configureUserRoutes(userProfileService: UserProfileService) {
    post("/register") {
        val authenticatedUser = call.requireAuthenticatedUser()
        log.info("[USER] POST /api/user/register received")
        val accessToken = call.requireBearerAccessToken()
        val request = call.receive<RegisterUserRequest>()
        log.info("[USER] register payload validated at route level")
        val response = userProfileService.registerIfAbsent(authenticatedUser, accessToken, request)
        log.info("[USER] register completed status={}", response.status)
        call.respond(HttpStatusCode.OK, response)
    }

    get("/registration-status") {
        val authenticatedUser = call.requireAuthenticatedUser()
        log.info("[USER] GET /api/user/registration-status received")
        val accessToken = call.requireBearerAccessToken()
        val response = userProfileService.getRegistrationStatus(authenticatedUser, accessToken)
        log.info("[USER] registration-status completed isRegistered={}", response.isRegistered)
        call.respond(HttpStatusCode.OK, response)
    }
}

/**
 * Extracts a best-effort 2-letter ISO country code from common CDN / load-balancer
 * geo headers. Used as a fallback when the client does not send the `country`
 * query param and the locale has no region segment. Returns null if no header
 * resolves to a real country code.
 *
 * Orders by trust:
 *   1. `Cf-IPCountry`           — Cloudflare (most common edge).
 *   2. `X-Appengine-Country`    — App Engine / some GCP runtimes.
 *   3. `X-Client-Geo-Location`  — certain GCP Cloud Load Balancer configs.
 *   4. `X-Goog-Country`         — rare, but observed on some GCP frontends.
 *
 * The orchestrator re-validates + rejects CDN sentinels like `XX`/`T1`/`ZZ`,
 * so we don't filter here.
 */
private fun extractIpCountry(headers: io.ktor.http.Headers): String? {
    val candidateHeaders = listOf(
        "Cf-IPCountry",
        "X-Appengine-Country",
        "X-Client-Geo-Location",
        "X-Goog-Country"
    )
    for (name in candidateHeaders) {
        val value = headers[name]?.trim()?.ifBlank { null }
        if (value != null) return value
    }
    return null
}
