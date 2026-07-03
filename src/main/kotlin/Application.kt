package com.zenthek.fitzenio.rest

import com.zenthek.ai.GeminiAiSearchClient
import com.zenthek.auth.AuthenticatedUserContext
import com.zenthek.auth.configureAuthentication
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ErrorResponse
import com.zenthek.revenuecat.RevenueCatEntitlementGateway
import com.zenthek.revenuecat.RevenueCatRestClient
import com.zenthek.revenuecat.RevenueCatSyncService
import com.zenthek.routes.RateLimitNames
import com.zenthek.routes.configureRouting
import com.zenthek.service.AccountService
import com.zenthek.service.AiProgressProjectionService
import com.zenthek.service.FoodService
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.service.ForbiddenException
import com.zenthek.service.UnauthorizedException
import com.zenthek.service.UpstreamFailureException
import com.zenthek.service.UserProfileService
import com.zenthek.upstream.supabase.AiProgressLadderGateway
import com.zenthek.upstream.supabase.CanonicalCatalogClient
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import com.zenthek.upstream.supabase.OffMirrorGateway
import com.zenthek.upstream.supabase.SupabaseAdminGateway
import com.zenthek.upstream.supabase.SupabaseClient
import com.zenthek.upstream.imageedit.ProgressImageEditClient
import com.zenthek.upstream.openai.OpenAiApiService
import com.zenthek.upstream.openai.OpenAiImageEditClient
import com.zenthek.upstream.gemini.GeminiApiService
import com.zenthek.upstream.gemini.GeminiImageEditClient
import com.zenthek.upstream.gemini.GeminiProgressGatekeeperClient
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.usda.UsdaClient
import com.zenthek.upstream.usda.UsdaMirrorGateway
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.get
import io.ktor.server.plugins.BadRequestException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.principal
import com.zenthek.config.ConfigLoader
import com.zenthek.config.ImageGenerationProvider
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

    log.info("Starting Fitzenia API in ${config.environment} mode")

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

    // Local OFF mirror — read-side gateway only. Constructed only when
    // OFF_MIRROR_READ_ENABLED is on (production by default). Dev keeps
    // offMirrorGateway = null and falls back to the existing live OFF/USDA
    // path byte-for-byte.
    val offMirrorGateway: OffMirrorGateway? = if (config.offMirror.readEnabled) {
        log.info("OFF mirror read path: enabled (off_food → live OFF/USDA fallback)")
        OffMirrorGateway(
            httpClient = httpClient,
            config = config.supabase,
            serviceRoleKey = config.apiKeys.supabaseServiceRoleKey,
        )
    } else {
        log.info("OFF mirror read path: disabled (live fetch OFF only)")
        null
    }

    // USDA mirror — read-side gateway. Constructed only when
    // USDA_MIRROR_READ_ENABLED is on (production by default). When non-null,
    // FoodService consults it after the OFF mirror miss; SmartSearch hits it
    // in phase-1 mirror fan-out alongside OFF mirror.
    val usdaMirrorGateway: UsdaMirrorGateway? = if (config.usdaMirror.readEnabled) {
        log.info("USDA database mirror read path: enabled (usda_food → live FDC fallback)")
        UsdaMirrorGateway(
            httpClient = httpClient,
            config = config.supabase,
            serviceRoleKey = config.apiKeys.supabaseServiceRoleKey,
        )
    } else {
        log.info("USDA mirror read path: disabled (live fetch FDC only)")
        null
    }

    val foodService = FoodService(offClient, usdaClient, offMirrorGateway, usdaMirrorGateway)
    val supabaseClient = SupabaseClient(httpClient, config.supabase)
    val userProfileService = UserProfileService(supabaseClient)

    // Smart Food Search: shared canonical catalog (service-role) + Gemini AI classify/generate.
    // When SMART_FOOD_SEARCH_ENABLED=false, the catalog + AI clients are still constructed
    // but never invoked — the orchestrator takes the upstream-only branch.
    val canonicalCatalog: CanonicalCatalogGateway = CanonicalCatalogClient(
        httpClient = httpClient,
        config = config.supabase,
        serviceRoleKey = config.apiKeys.supabaseServiceRoleKey
    )
    val supabaseAdminGateway = SupabaseAdminGateway(
        httpClient = httpClient,
        supabaseConfig = config.supabase,
        serviceRoleKey = config.apiKeys.supabaseServiceRoleKey,
    )
    val accountService = AccountService(supabaseAdminGateway)
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
        backgroundScope = smartSearchBackgroundScope,
        offMirror = offMirrorGateway,
        usdaMirror = usdaMirrorGateway,
    )

    // AI Progress Projections — bytes-in ladder generator + delete endpoint.
    val ladderGateway = AiProgressLadderGateway(
        httpClient = httpClient,
        supabaseConfig = config.supabase,
        serviceRoleKey = config.apiKeys.supabaseServiceRoleKey,
    )
    val progressGatekeeper = GeminiProgressGatekeeperClient(
        httpClient = httpClient,
        apiKey = config.geminiApiKey,
        config = config.aiProgressProjection,
    )
    val imageEditClient: ProgressImageEditClient = when (config.aiProgressProjection.provider) {
        ImageGenerationProvider.OPENAI -> OpenAiImageEditClient(
            httpClient = httpClient,
            apiKey = config.apiKeys.openAiApiKey,
            config = config.aiProgressProjection,
        )
        ImageGenerationProvider.GEMINI -> GeminiImageEditClient(
            httpClient = httpClient,
            apiKey = config.geminiApiKey,
            config = config.aiProgressProjection,
        )
    }
    log.info(
        "AI Progress Projections: provider={} model={}",
        config.aiProgressProjection.provider,
        config.aiProgressProjection.activeImageModel,
    )
    val aiProgressProjectionService = AiProgressProjectionService(
        ladderGateway = ladderGateway,
        storageGateway = supabaseAdminGateway,
        gatekeeper = progressGatekeeper,
        imageEdit = imageEditClient,
        config = config.aiProgressProjection,
    )

    // RevenueCat → Supabase entitlement sync. Constructed only when both secrets
    // are present; otherwise the webhook route short-circuits to 503.
    val revenueCatSync: RevenueCatSyncService? = if (config.revenueCat.configured) {
        log.info("RevenueCat webhook: enabled")
        RevenueCatSyncService(
            gateway = RevenueCatEntitlementGateway(
                httpClient = httpClient,
                supabaseUrl = config.supabase.normalizedUrl,
                serviceRoleKey = config.apiKeys.supabaseServiceRoleKey,
            ),
            rest = RevenueCatRestClient(
                httpClient = httpClient,
                restApiKey = config.revenueCat.restApiKey!!,
                restBaseUrl = config.revenueCat.restBaseUrl,
            ),
            webhookAuth = config.revenueCat.webhookAuth!!,
        )
    } else {
        log.info("RevenueCat webhook: disabled (REVENUECAT_* not set)")
        null
    }

    warnIfRemoteMode(config.supabase)
    probeJwks(httpClient, config.supabase)

    configureSerialization()
    configureStatusPages()
    configureRateLimit()
    configureAuthentication(config.supabase, supabaseClient)
    configureRouting(foodService, smartSearch, imageAnalyzer, userProfileService, accountService, aiProgressProjectionService, revenueCatSync)
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
        register(RateLimitName(RateLimitNames.ACCOUNT)) {
            rateLimiter(limit = 3, refillPeriod = 1.minutes)
            requestKey { call -> call.authenticatedUserIdOrFail() }
        }
        register(RateLimitName(RateLimitNames.PROGRESS_PROJECTION)) {
            rateLimiter(limit = 3, refillPeriod = 1.minutes)
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
        exception<ForbiddenException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(cause.message ?: "Forbidden")
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
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
}
