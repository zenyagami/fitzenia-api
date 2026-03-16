package com.zenthek.routes

import com.zenthek.model.AnalyzeImageRequest
import com.zenthek.model.SearchResponse
import com.zenthek.service.FoodService
import com.zenthek.fitzenio.rest.com.zenthek.upstream.openai.OpenAiApiService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(
    foodService: FoodService,
    openAiClient: OpenAiApiService
) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        route("/api/food") {
            
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
                val result = openAiClient.analyzeImage(imageBytes, body.mealTitle, body.additionalContext, body.locale, "image/jpeg")
                call.respond(HttpStatusCode.OK, result)
            }

            post("/analyze-image-fast") {
                val body = call.receive<AnalyzeImageRequest>()
                val imageBytes = java.util.Base64.getDecoder().decode(body.image)
                val result = openAiClient.analyzeImageFast(imageBytes, body.mealTitle, body.additionalContext, body.locale, "image/jpeg")
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}
