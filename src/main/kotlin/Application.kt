package com.zenthek.fitzenio.rest

import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ErrorResponse
import com.zenthek.routes.configureRouting
import com.zenthek.service.FoodService
import com.zenthek.service.UnauthorizedException
import com.zenthek.service.UpstreamFailureException
import com.zenthek.service.UserProfileService
import com.zenthek.upstream.supabase.SupabaseClient
import com.zenthek.upstream.openai.OpenAiApiService
import com.zenthek.upstream.fatsecret.FatSecretClient
import com.zenthek.upstream.gemini.GeminiApiService
import com.zenthek.upstream.fatsecret.FatSecretTokenManager
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.usda.UsdaClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import com.zenthek.config.ConfigLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // Load environment configuration
    val config = ConfigLoader.loadConfig()

    log.info("Starting Fitzenio API in ${config.environment} mode")

    val httpClient = buildHttpClient()

    val offClient = OpenFoodFactsClient(httpClient)
    val fsTokenManager = FatSecretTokenManager(httpClient, config.apiKeys)
    val fsClient = FatSecretClient(httpClient, fsTokenManager)
    val usdaClient = UsdaClient(httpClient, config.apiKeys.usdaApiKey)
    val imageAnalyzer: ImageAnalyzer = if (config.useGemini) {
        log.info("Image analysis backend: Gemini Flash")
        GeminiApiService(httpClient, config.geminiApiKey)
    } else {
        log.info("Image analysis backend: GPT-5-mini")
        OpenAiApiService(httpClient, config.apiKeys.openAiApiKey)
    }

    val foodService = FoodService(offClient, fsClient, usdaClient)
    val supabaseClient = SupabaseClient(httpClient, config.supabase)
    val userProfileService = UserProfileService(supabaseClient)

    configureSerialization()
    configureStatusPages()
    configureRouting(foodService, imageAnalyzer, userProfileService)
}

fun Application.configureSerialization() {
    val appJson = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    install(ContentNegotiation) {
        json(appJson)
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Bad request")
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Bad request")
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid request body")
            )
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(cause.message ?: "Unauthorized")
            )
        }
        exception<UpstreamFailureException> { call, cause ->
            call.application.log.error("Upstream dependency failure", cause)
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("Upstream dependency failure")
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error")
            )
        }
    }
}

fun buildHttpClient(): HttpClient = HttpClient(CIO) {
    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
    }
}
