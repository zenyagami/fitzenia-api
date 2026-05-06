package com.zenthek.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.auth.requireBearerAccessToken
import com.zenthek.model.AnalyzeImageRequest
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.RegisterUserRequest
import com.zenthek.service.AccountService
import com.zenthek.service.AiProgressProjectionService
import com.zenthek.service.DeleteLadderResult
import com.zenthek.service.FoodService
import com.zenthek.service.LadderEventEmitter
import com.zenthek.model.SearchStreamBestMatch
import com.zenthek.model.SmartSearchResponse
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.service.UserProfileService
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

object RateLimitNames {
    const val FOOD_SEARCH = "food-search"
    const val IMAGE_ANALYSIS = "image-analysis"
    const val ACCOUNT = "account"
    const val PROGRESS_PROJECTION = "progress-projection"
}

private val sseJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("com.zenthek.routes.UserRoutes")

@Serializable
data class DataSourceCredit(
    val name: String,
    val url: String,
    val licenses: List<String>,
    val notes: String? = null,
)

@Serializable
data class CreditsResponse(val sources: List<DataSourceCredit>)

private val CREDITS_RESPONSE = CreditsResponse(
    sources = listOf(
        DataSourceCredit(
            name = "Open Food Facts",
            url = "https://world.openfoodfacts.org/",
            licenses = listOf(
                "Open Database License (ODbL) v1.0 — https://opendatacommons.org/licenses/odbl/1-0/",
                "Database Contents License (DbCL) v1.0 — https://opendatacommons.org/licenses/dbcl/1-0/",
            ),
            notes = "Product data is mirrored locally and refreshed daily; attribution required by ODbL.",
        ),
        DataSourceCredit(
            name = "USDA FoodData Central",
            url = "https://fdc.nal.usda.gov/",
            licenses = listOf("U.S. Government Works (public domain)"),
            notes = null,
        ),
    ),
)

private suspend fun ByteWriteChannel.sendSseEvent(event: String, data: String) {
    writeFully("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

fun Application.configureRouting(
    foodService: FoodService,
    smartSearch: SmartSearchOrchestrator,
    imageAnalyzer: ImageAnalyzer,
    userProfileService: UserProfileService,
    accountService: AccountService,
    aiProgressProjectionService: AiProgressProjectionService,
) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // Public attribution endpoint. Open Food Facts data is licensed under
        // ODbL (database) + DbCL (contents); attribution is required when the
        // mirror serves food data. USDA FoodData Central is public domain and
        // listed for completeness.
        get("/credits") {
            call.respond(HttpStatusCode.OK, CREDITS_RESPONSE)
        }

        authenticate(SUPABASE_AUTH_PROVIDER) {
            route("/api/food") {
                configureFoodRoutes(foodService, smartSearch, imageAnalyzer)
            }

            route("/api/user") {
                configureUserRoutes(userProfileService)
            }

            rateLimit(RateLimitName(RateLimitNames.ACCOUNT)) {
                route("/api/account") {
                    delete {
                        val user = call.requireAuthenticatedUser()
                        accountService.deleteAccount(user.userId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            rateLimit(RateLimitName(RateLimitNames.PROGRESS_PROJECTION)) {
                route("/api/progress/ladders") {
                    configureProgressLadderRoutes(aiProgressProjectionService)
                }
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

            val country = call.request.queryParameters["country"]?.trim()?.ifBlank { null }
            val ipCountry = extractIpCountry(call.request.headers)

            log.debug(
                "[FOOD] barcode lookup userId={} barcode={} country={} ipCountry={}",
                authenticatedUser.userId, barcode, country, ipCountry
            )
            val result = foodService.getByBarcode(barcode, country, ipCountry)

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
private fun extractIpCountry(headers: Headers): String? {
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

/**
 * AI Progress Projections — bytes-in ladder generator + delete endpoint.
 * See `AiProgressProjectionService` for the orchestration logic; routes here are thin.
 */
fun Route.configureProgressLadderRoutes(service: AiProgressProjectionService) {
    post("/generate-stream") {
        val authenticatedUser = call.requireAuthenticatedUser()

        // Drain the multipart form: one `image` file part + optional body-comp text fields.
        var imageBytes: ByteArray? = null
        var imageMimeType: String? = null
        var currentWeightKg: Double? = null
        var currentBodyFatPercent: Double? = null
        var targetWeightKg: Double? = null
        var targetBodyFatPercent: Double? = null

        val multipart = try {
            call.receiveMultipart()
        } catch (e: Exception) {
            log.warn("[PROJECTION] multipart receive failed userId={}", authenticatedUser.userId, e)
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid multipart body"))
        }

        try {
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "image") {
                            imageBytes = part.provider().toByteArray()
                            imageMimeType = part.contentType?.toString() ?: "application/octet-stream"
                        }
                    }
                    is PartData.FormItem -> when (part.name) {
                        "currentWeightKg"       -> currentWeightKg = part.value.trim().toDoubleOrNull()
                        "currentBodyFatPercent" -> currentBodyFatPercent = part.value.trim().toDoubleOrNull()
                        "targetWeightKg"        -> targetWeightKg = part.value.trim().toDoubleOrNull()
                        "targetBodyFatPercent"  -> targetBodyFatPercent = part.value.trim().toDoubleOrNull()
                        // locale is reserved for future prompt localization; ignored in v1.
                    }
                    else -> { /* binary item etc. — ignored */ }
                }
                part.dispose()
            }
        } catch (e: Exception) {
            log.warn("[PROJECTION] multipart parse failed userId={}", authenticatedUser.userId, e)
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not parse multipart body"))
        }

        val bytes = imageBytes
        val mime = imageMimeType
        if (bytes == null || mime == null) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'image' file part"))
        }

        log.info(
            "[PROJECTION] generate-stream userId={} bytes={} mime={}",
            authenticatedUser.userId, bytes.size, mime,
        )

        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            // Once the client disconnects, every further sendSseEvent throws
            // ClosedWriteChannelException. We swallow it and flip this flag so the
            // orchestration coroutine can finish persisting rungs in the background — the
            // client picks up the result via Supabase REST + Realtime on reconnect (see
            // docs/AI_PROGRESS_PROJECTIONS_CLIENT.md "Realtime fallback"). Aborting on
            // disconnect would leave an orphan PENDING ladder row and brick retries because
            // of the (user_id, request_key) unique index.
            var clientConnected = true
            suspend fun safeSend(event: String, data: String) {
                if (!clientConnected) return
                try {
                    sendSseEvent(event, data)
                } catch (e: ClosedWriteChannelException) {
                    clientConnected = false
                    log.info(
                        "[PROJECTION] client disconnected mid-stream userId={}; continuing generation in background",
                        authenticatedUser.userId,
                    )
                }
            }

            try {
                safeSend("status", """{"phase":"validating"}""")
                val emitter = LadderEventEmitter { event, payload -> safeSend(event, payload) }
                service.generate(
                    userId = authenticatedUser.userId,
                    sourceBytes = bytes,
                    mimeType = mime,
                    requestedCurrentWeightKg = currentWeightKg,
                    requestedCurrentBodyFatPercent = currentBodyFatPercent,
                    requestedTargetWeightKg = targetWeightKg,
                    requestedTargetBodyFatPercent = targetBodyFatPercent,
                    emitter = emitter,
                    isClientConnected = { clientConnected },
                )
            } catch (e: Exception) {
                application.log.error("SSE progress/ladders/generate-stream failed", e)
                if (clientConnected) {
                    runCatching {
                        sendSseEvent("error", """{"message":"Generation failed","code":"INTERNAL_ERROR"}""")
                    }
                }
            }
        }
    }

    delete("/{ladderId}") {
        val authenticatedUser = call.requireAuthenticatedUser()
        val ladderId = call.parameters["ladderId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ladderId"))

        when (service.delete(authenticatedUser.userId, ladderId)) {
            DeleteLadderResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteLadderResult.NotFoundOrNotOwned -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ladder not found"))
        }
    }
}
