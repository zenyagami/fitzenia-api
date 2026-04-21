package com.zenthek.fitzenio.rest

import com.zenthek.ai.GeminiAiSearchClient
import com.zenthek.auth.AuthenticatedUserContext
import com.zenthek.auth.configureAuthentication
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ErrorResponse
import com.zenthek.routes.RateLimitNames
import com.zenthek.routes.configureRouting
import com.zenthek.service.FoodService
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.service.UnauthorizedException
import com.zenthek.service.UpstreamFailureException
import com.zenthek.service.UserProfileService
import com.zenthek.upstream.supabase.CanonicalCatalogClient
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import com.zenthek.upstream.supabase.SupabaseClient
import com.zenthek.upstream.openai.OpenAiApiService
import com.zenthek.upstream.gemini.GeminiApiService
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.usda.UsdaClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.get
import io.ktor.server.plugins.BadRequestException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.principal
import com.zenthek.config.ConfigLoader
import com.zenthek.config.SupabaseConfig
import com.zenthek.config.SupabaseJwtVerificationMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // Load environment configuration
    val config = ConfigLoader.loadConfig()

    log.info("Starting Fitzenio API in ${config.environment} mode")

    val httpClient = buildHttpClient()

    val offClient = OpenFoodFactsClient(httpClient)
    val usdaClient = UsdaClient(httpClient, config.apiKeys.usdaApiKey)
    val imageAnalyzer: ImageAnalyzer = if (config.useGeminiForAiImage) {
        log.info("Image analysis backend: Gemini Flash")
        GeminiApiService(httpClient, config.geminiApiKey)
    } else {
        log.info("Image analysis backend: GPT-5-mini")
        OpenAiApiService(httpClient, config.apiKeys.openAiApiKey)
    }

    val foodService = FoodService(offClient, usdaClient)
    val supabaseClient = SupabaseClient(httpClient, config.supabase)
    val userProfileService = UserProfileService(supabaseClient)

    // Smart Food Search: shared canonical catalog (service-role) + Gemini AI classify/generate.
    // When SMART_FOOD_SEARCH_ENABLED=false, the catalog + AI clients are still constructed
    // but never invoked — the orchestrator takes the upstream-only branch.
    val canonicalCatalog: CanonicalCatalogGateway = CanonicalCatalogClient(
        httpClient = httpClient,
        config = config.supabase,
        serviceRoleKey = config.apiKeys.supabaseServiceRoleKey ?: "DISABLED"
    )
    val aiSearchClient = GeminiAiSearchClient(
        httpClient = httpClient,
        apiKey = config.geminiApiKey,
        rankModel = config.smartSearch.aiRankModel,
        generateModel = config.smartSearch.aiGenerateModel
    )
    // Background scope for async write-behind AI generation. SupervisorJob so one failed
    // generation doesn't cancel siblings. Dispatchers.IO because these coroutines are
    // almost entirely waiting on Gemini + Supabase HTTP calls. Cancelled on app shutdown
    // below so in-flight work can finish cleanly.
    val smartSearchBackgroundScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("smart-search-bg")
    )
    monitor.subscribe(ApplicationStopping) {
        log.info("Cancelling smart-search background scope on app shutdown")
        smartSearchBackgroundScope.cancel()
    }

    val smartSearch = SmartSearchOrchestrator(
        offSearch = { q, p, ps, loc, ctry -> offClient.searchPaged(q, p, ps, loc, ctry) },
        usdaSearch = { q, p, ps, loc, ctry -> usdaClient.searchPaged(q, p, ps, loc, ctry) },
        catalog = canonicalCatalog,
        ai = aiSearchClient,
        config = config.smartSearch,
        backgroundScope = smartSearchBackgroundScope
    )

    warnIfRemoteMode(config.supabase)
    probeJwks(httpClient, config.supabase)

    configureSerialization()
    configureStatusPages()
    configureRateLimit()
    configureAuthentication(config.supabase, supabaseClient)
    configureRouting(foodService, smartSearch, imageAnalyzer, userProfileService)
}

fun Application.configureRateLimit() {
    install(RateLimit) {
        register(RateLimitName(RateLimitNames.FOOD_SEARCH)) {
            rateLimiter(limit = 200, refillPeriod = 1.minutes)
            requestKey { call -> call.authenticatedUserIdOrFail() }
        }
        register(RateLimitName(RateLimitNames.IMAGE_ANALYSIS)) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.authenticatedUserIdOrFail() }
        }
    }
}

private fun ApplicationCall.authenticatedUserIdOrFail(): String {
    return principal<AuthenticatedUserContext>()?.userId
        ?: throw UnauthorizedException("Authentication required")
}

private fun Application.warnIfRemoteMode(supabase: SupabaseConfig) {
    if (supabase.jwtVerificationMode == SupabaseJwtVerificationMode.REMOTE) {
        log.warn(
            "Supabase JWT verification is running in REMOTE mode. " +
                "Every request will call /auth/v1/user — use only as a temporary fallback."
        )
    }
}

private fun Application.probeJwks(httpClient: HttpClient, supabase: SupabaseConfig) {
    if (supabase.jwtVerificationMode != SupabaseJwtVerificationMode.JWKS) return
    val log = log
    val jwksUrl = supabase.jwksUrl
    launch {
        runCatching { httpClient.get(jwksUrl) }
            .onSuccess { response ->
                if (response.status.value in 200..299) {
                    log.info("Supabase JWKS reachable at {} (status {})", jwksUrl, response.status.value)
                } else {
                    log.warn("Supabase JWKS probe returned status {} for {}", response.status.value, jwksUrl)
                }
            }
            .onFailure { error ->
                log.warn("Supabase JWKS probe failed for {}: {}", jwksUrl, error.message)
            }
    }
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
