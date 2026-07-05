package com.zenthek.coach

import com.zenthek.auth.AuthenticatedUserContext
import com.zenthek.auth.configureAuthentication
import com.zenthek.coach.agent.CoachAgentFactory
import com.zenthek.coach.auth.PremiumGate
import com.zenthek.coach.compaction.ConversationCompactor
import com.zenthek.coach.config.CoachConfigLoader
import com.zenthek.coach.persistence.BudgetGateway
import com.zenthek.coach.persistence.ChatGateway
import com.zenthek.coach.persistence.NotesGateway
import com.zenthek.coach.rag.EmbeddingClient
import com.zenthek.coach.rag.HybridRetriever
import com.zenthek.coach.routes.configureCoachRouting
import com.zenthek.coach.routes.configureNotesRouting
import com.zenthek.coach.routes.configureUsageRouting
import com.zenthek.config.SupabaseJwtVerificationMode
import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.revenuecat.RevenueCatEntitlementGateway
import com.zenthek.revenuecat.RevenueCatRestClient
import com.zenthek.revenuecat.RevenueCatSyncService
import com.zenthek.fitzenio.rest.configureStatusPages
import com.zenthek.routes.RateLimitNames
import com.zenthek.service.UnauthorizedException
import com.zenthek.upstream.supabase.SupabaseClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

fun Application.module() {
    log.info("Starting Fitzenia AI Coach service")
    val config = CoachConfigLoader.load()

    if (config.supabase.jwtVerificationMode == SupabaseJwtVerificationMode.REMOTE) {
        log.warn(
            "Supabase JWT verification is running in REMOTE mode. " +
                "Every request will call /auth/v1/user — use only as a temporary fallback."
        )
    }

    val httpClient = buildCoachHttpClient()
    val supabaseClient = SupabaseClient(httpClient, config.supabase)
    val chatGateway = ChatGateway(httpClient, config.supabase.normalizedUrl, config.serviceRoleKey)
    val notesGateway = NotesGateway(httpClient, config.supabase.normalizedUrl, config.serviceRoleKey)
    val budgetGateway = BudgetGateway(httpClient, config.supabase.normalizedUrl, config.serviceRoleKey)
    // Lazy sync-on-miss: when RevenueCat REST is configured, the premium gate reconciles a
    // user's live subscriber snapshot on a cache miss so existing subscribers (who never fired a
    // fresh webhook) self-heal on their first coach request. The webhook itself stays on fitzenia-api.
    val revenueCatSync = config.revenueCatRestApiKey?.let { key ->
        RevenueCatSyncService(
            gateway = RevenueCatEntitlementGateway(httpClient, config.supabase.normalizedUrl, config.serviceRoleKey),
            rest = RevenueCatRestClient(httpClient, restApiKey = key, restBaseUrl = config.revenueCatRestBaseUrl),
            isProductionDeployment = !config.environment.isDebug(),
        )
    }
    if (revenueCatSync != null) {
        log.info("[COACH] lazy RevenueCat sync-on-miss enabled")
    } else {
        log.info("[COACH] RevenueCat REST not configured — premium gate reads user_entitlement only")
    }
    val premiumGate = PremiumGate(httpClient, config, revenueCatSync)
    val agentFactory = CoachAgentFactory(config.geminiApiKey)
    val embeddingClient = EmbeddingClient(httpClient, config.geminiApiKey)
    val hybridRetriever = HybridRetriever(httpClient, config.supabase.normalizedUrl, config.serviceRoleKey, embeddingClient)
    val compactor = ConversationCompactor(agentFactory, chatGateway)

    configureSerialization()
    configureStatusPages()
    configureCoachCors()
    configureCoachRateLimit()
    configureAuthentication(config.supabase, supabaseClient)
    configureCoachRouting(
        chatGateway     = chatGateway,
        premiumGate     = premiumGate,
        agentFactory    = agentFactory,
        hybridRetriever = hybridRetriever,
        httpClient      = httpClient,
        supabaseUrl     = config.supabase.normalizedUrl,
        supabaseAnonKey = config.supabase.publicApiKey,
        notesGateway    = notesGateway,
        compactor       = compactor,
        budgetGateway   = budgetGateway,
    )
    configureNotesRouting(notesGateway = notesGateway, premiumGate = premiumGate)
    configureUsageRouting(
        config          = config,
        budgetGateway   = budgetGateway,
        premiumGate     = premiumGate,
        revenueCatSync  = revenueCatSync,
    )
}

private fun Application.configureCoachCors() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }
}

private fun Application.configureCoachRateLimit() {
    install(RateLimit) {
        register(RateLimitName(RateLimitNames.COACH_MESSAGE)) {
            rateLimiter(limit = 6, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<AuthenticatedUserContext>()?.userId
                    ?: throw UnauthorizedException("Authentication required")
            }
        }
        register(RateLimitName(RateLimitNames.COACH_MANAGEMENT)) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<AuthenticatedUserContext>()?.userId
                    ?: throw UnauthorizedException("Authentication required")
            }
        }
    }
}

private fun buildCoachHttpClient(): HttpClient = HttpClient(CIO) {
    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        // 60 s to survive dev-Supabase cold starts; prod queries resolve in <300 ms.
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 10_000
    }
}
