package com.zenthek.routes

import com.zenthek.model.AnalyzeImageRequest
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.RegisterUserRequest
import com.zenthek.model.SearchResponse
import com.zenthek.service.FoodService
import com.zenthek.service.UnauthorizedException
import com.zenthek.service.UserProfileService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val sseJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("com.zenthek.routes.UserRoutes")

private suspend fun ByteWriteChannel.sendSseEvent(event: String, data: String) {
    writeFully("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

fun Application.configureRouting(
    foodService: FoodService,
    imageAnalyzer: ImageAnalyzer,
    userProfileService: UserProfileService,
) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        route("/api/food") {
            configureFoodRoutes(foodService, imageAnalyzer)
        }

        route("/api/user") {
            configureUserRoutes(userProfileService)
        }
    }
}

fun Route.configureFoodRoutes(
    foodService: FoodService,
    imageAnalyzer: ImageAnalyzer,
) {
    get("/autocomplete") {
        val query = call.request.queryParameters["q"]?.trim()
            ?: throw IllegalArgumentException("Missing required parameter: q")
        if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
        if (limit > 25) throw IllegalArgumentException("limit cannot exceed 25")

        val suggestions = foodService.autocomplete(query, limit)
        call.respond(HttpStatusCode.OK, mapOf("suggestions" to suggestions))
    }

    get("/search") {
        val query = call.request.queryParameters["q"]?.trim()
            ?: throw IllegalArgumentException("Missing required parameter: q")
        if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 25
        if (pageSize > 50) throw IllegalArgumentException("pageSize cannot exceed 50")

        val results = foodService.search(query, page, pageSize)

        call.respond(HttpStatusCode.OK, SearchResponse(results, results.size, page, pageSize))
    }

    get("/barcode/{barcode}") {
        val barcode = call.parameters["barcode"]?.trim()
            ?: throw IllegalArgumentException("Missing barcode path parameter")
        if (barcode.isBlank() || !barcode.all { it.isDigit() }) {
            throw IllegalArgumentException("Barcode must contain only digits")
        }

        val result = foodService.getByBarcode(barcode)

        call.respond(
            HttpStatusCode.OK,
            mapOf("result" to result)
        )
    }

    post("/analyze-image") {
        val body = call.receive<AnalyzeImageRequest>()
        val imageBytes = java.util.Base64.getDecoder().decode(body.image)
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
        val body = call.receive<AnalyzeImageRequest>()
        val imageBytes = java.util.Base64.getDecoder().decode(body.image)
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

fun Route.configureUserRoutes(userProfileService: UserProfileService) {
    post("/register") {
        log.info("[USER] POST /api/user/register received")
        val accessToken = call.requireBearerToken()
        val request = call.receive<RegisterUserRequest>()
        log.info("[USER] register payload validated at route level")
        val response = userProfileService.registerIfAbsent(accessToken, request)
        log.info("[USER] register completed status={}", response.status)
        call.respond(HttpStatusCode.OK, response)
    }

    get("/registration-status") {
        log.info("[USER] GET /api/user/registration-status received")
        val accessToken = call.requireBearerToken()
        val response = userProfileService.getRegistrationStatus(accessToken)
        log.info("[USER] registration-status completed isRegistered={}", response.isRegistered)
        call.respond(HttpStatusCode.OK, response)
    }
}

private fun ApplicationCall.requireBearerToken(): String {
    val authorizationHeader = request.headers[HttpHeaders.Authorization]
        ?: run {
            log.warn("[USER] Authorization header missing")
            throw UnauthorizedException("Missing Authorization header")
        }

    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) {
        log.warn("[USER] Authorization header is not Bearer")
        throw UnauthorizedException("Invalid authorization scheme")
    }

    return authorizationHeader.substring(prefix.length).trim()
        .ifBlank {
            log.warn("[USER] Bearer token was blank")
            throw UnauthorizedException("Missing access token")
        }
}
