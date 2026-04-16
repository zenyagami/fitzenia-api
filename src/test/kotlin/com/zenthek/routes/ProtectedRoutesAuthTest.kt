package com.zenthek.routes

import com.zenthek.ai.AiClassifyInput
import com.zenthek.ai.AiClassifyResult
import com.zenthek.ai.AiGenerateInput
import com.zenthek.ai.AiGenerateResult
import com.zenthek.ai.AiSearchClient
import com.zenthek.ai.ClassifyDecision
import com.zenthek.auth.TestJwksServer
import com.zenthek.auth.configureAuthentication
import com.zenthek.auth.createSupabaseAccessToken
import com.zenthek.auth.createTestSupabaseConfig
import com.zenthek.auth.generateTestRsaKeyPair
import com.zenthek.config.ApiKeys
import com.zenthek.config.SmartSearchConfig
import com.zenthek.fitzenio.rest.configureRateLimit
import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.fitzenio.rest.configureStatusPages
import com.zenthek.model.CanonicalEquivalentCandidate
import com.zenthek.model.CanonicalFoodEntity
import com.zenthek.model.CanonicalQueryMapRow
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.InsertCanonicalFoodsPayload
import com.zenthek.model.InsertCanonicalFoodsResult
import com.zenthek.service.FoodService
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.service.UserProfileService
import com.zenthek.upstream.fatsecret.FatSecretClient
import com.zenthek.upstream.fatsecret.FatSecretTokenManager
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import com.zenthek.upstream.supabase.SupabaseAuthenticatedUser
import com.zenthek.upstream.supabase.SupabaseGateway
import com.zenthek.upstream.usda.UsdaClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtectedRoutesAuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health remains public`() {
        val keyPair = generateTestRsaKeyPair("health-public")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            testApplication {
                installProtectedApp(jwksServer.baseUrl)

                val response = client.get("/health")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("ok"))
            }
        }
    }

    @Test
    fun `protected routes return 401 for missing or invalid tokens`() {
        val keyPair = generateTestRsaKeyPair("protected-routes")
        val badKeyPair = generateTestRsaKeyPair("other-key")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val validBaseUrl = jwksServer.baseUrl
            val expiredToken = createSupabaseAccessToken(
                baseUrl = validBaseUrl,
                keyPair = keyPair,
                expiresAt = java.util.Date(System.currentTimeMillis() - 60_000),
            )
            val wrongIssuerToken = createSupabaseAccessToken(
                baseUrl = validBaseUrl,
                keyPair = keyPair,
                issuer = "https://wrong-issuer.example/auth/v1",
            )
            val wrongRoleToken = createSupabaseAccessToken(
                baseUrl = validBaseUrl,
                keyPair = keyPair,
                role = "anon",
            )
            val badSignatureToken = createSupabaseAccessToken(
                baseUrl = validBaseUrl,
                keyPair = keyPair,
                signingKey = badKeyPair,
            )

            val invalidCases = listOf(
                null to "missing",
                "not-a-jwt" to "malformed",
                expiredToken to "expired",
                wrongIssuerToken to "wrong-issuer",
                wrongRoleToken to "wrong-role",
                badSignatureToken to "bad-signature",
            )

            testApplication {
                installProtectedApp(validBaseUrl)

                for ((token, _) in invalidCases) {
                    for (request in protectedRequests()) {
                        val response = request.invoke(client, token)
                        assertEquals(
                            HttpStatusCode.Unauthorized,
                            response.status,
                            "Expected 401 for ${request.method.value} ${request.path} with token=${token ?: "missing"}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `protected route accepts a valid token`() {
        val keyPair = generateTestRsaKeyPair("protected-routes-valid")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)

            testApplication {
                installProtectedApp(jwksServer.baseUrl)

                val response = client.get("/api/user/registration-status") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        }
    }

    private fun ApplicationTestBuilder.installProtectedApp(baseUrl: String) {
        val gateway = AuthOnlySupabaseGateway()
        val userProfileService = UserProfileService(gateway)
        application {
            configureSerialization()
            configureStatusPages()
            configureRateLimit()
            configureAuthentication(createTestSupabaseConfig(baseUrl), gateway)
            configureRouting(
                foodService = buildFoodService(),
                smartSearch = buildSmartSearch(),
                imageAnalyzer = ImageAnalyzer { _, _, _, _, _ ->
                    ImageAnalysisResponse(
                        items = emptyList(),
                        totalCalories = 0,
                        totalProteinG = 0.0,
                        totalCarbsG = 0.0,
                        totalFatG = 0.0,
                        totalFiberG = null,
                        totalSodiumMg = null,
                    )
                },
                userProfileService = userProfileService,
            )
        }
    }

    private fun buildFoodService(): FoodService {
        val httpClient = HttpClient(MockEngine {
            error("Auth tests should not reach upstream clients")
        })
        val apiKeys = ApiKeys(
            fatSecretClientId = "client-id",
            fatSecretClientSecret = "client-secret",
            usdaApiKey = "usda-key",
            openAiApiKey = "openai-key",
            supabaseServiceRoleKey = null,
        )
        val offClient = OpenFoodFactsClient(httpClient)
        val tokenManager = FatSecretTokenManager(httpClient, apiKeys)
        val fsClient = FatSecretClient(httpClient, tokenManager)
        val usdaClient = UsdaClient(httpClient, apiKeys.usdaApiKey)
        return FoodService(offClient, fsClient, usdaClient)
    }

    /**
     * Minimal SmartSearchOrchestrator for auth tests. The handler is only reached
     * when auth FAILS (in which case search never runs) or in tests that do not
     * exercise /api/food/search — so stub collaborators throw if anyone actually
     * calls them. Flag is disabled so the orchestrator also won't touch catalog/AI
     * even if invoked.
     */
    private fun buildSmartSearch(): SmartSearchOrchestrator {
        val stubCatalog = object : CanonicalCatalogGateway {
            override suspend fun lookupQueryMappings(
                normalizedQuery: String, locale: String, country: String
            ): Result<List<CanonicalQueryMapRow>> = error("catalog not expected in auth tests")
            override suspend fun readCanonicals(ids: List<String>): Result<List<CanonicalFoodEntity>> =
                error("catalog not expected in auth tests")
            override suspend fun insertCanonicalFoods(
                payload: InsertCanonicalFoodsPayload
            ): Result<InsertCanonicalFoodsResult> = error("catalog not expected in auth tests")
            override suspend fun findEquivalentCanonicalCandidates(
                englishLikeName: String, limit: Int
            ): Result<List<CanonicalEquivalentCandidate>> = error("catalog not expected in auth tests")
        }
        val stubAi = object : AiSearchClient {
            override suspend fun classify(input: AiClassifyInput): AiClassifyResult =
                AiClassifyResult(ClassifyDecision.MATCH_EXISTING, emptyList(), 0f)
            override suspend fun generate(input: AiGenerateInput): AiGenerateResult =
                AiGenerateResult(emptyList())
        }
        return SmartSearchOrchestrator(
            offSearch = { _, _, _, _, _ -> error("upstream not expected in auth tests") },
            usdaSearch = { _, _, _, _, _ -> error("upstream not expected in auth tests") },
            catalog = stubCatalog,
            ai = stubAi,
            config = SmartSearchConfig(
                enabled = false,
                usdaEnabled = false,
                aiRankModel = "stub",
                aiGenerateModel = "stub",
                aiClassifyTimeoutMs = 3000L,
                aiGenerateTimeoutMs = 8000L,
                aiSyncOnMiss = true,
                catalogWriteConfidenceThreshold = 0.7f,
            ),
            backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
    }

    private fun protectedRequests(): List<ProtectedRequest> {
        val registerBody = """
            {
              "userProfile":{"name":"Test User","birthDate":"1990-01-01","sex":"female","heightCm":168.0},
              "userGoal":{"id":"","goalDirection":"LOSE","targetPhase":"CUT","goalWeightKg":62.0,"paceTier":"MODERATE","activityLevel":"LIGHT","bodyFatPercent":20.0,"bodyFatRangeKey":"TIER_3","exerciseFrequency":"ONE_TO_THREE","stepsActivityBand":"SEDENTARY","liftingExperience":"NONE","proteinPreference":"MODERATE"},
              "calorieTarget":{"id":"","formula":"MIFLIN","bmrKcal":1500,"tdeeKcal":2100,"targetKcal":1800,"targetMinKcal":1700,"targetMaxKcal":1900,"macroMode":"BALANCED","proteinTargetG":130,"carbsTargetG":180,"fatTargetG":55,"appliedPaceTier":"MODERATE","floorClamped":0,"warning":null}
            }
        """.trimIndent()
        val imageBody = """{"image":"Zm9v"}"""

        return listOf(
            ProtectedRequest(HttpMethod.Get, "/api/food/autocomplete?q=banana"),
            ProtectedRequest(HttpMethod.Get, "/api/food/search?q=banana"),
            ProtectedRequest(HttpMethod.Get, "/api/food/barcode/1234567890123"),
            ProtectedRequest(HttpMethod.Post, "/api/food/analyze-image", imageBody),
            ProtectedRequest(HttpMethod.Post, "/api/food/analyze-image-stream", imageBody),
            ProtectedRequest(HttpMethod.Post, "/api/user/register", registerBody),
            ProtectedRequest(HttpMethod.Get, "/api/user/registration-status"),
        )
    }
}

private data class ProtectedRequest(
    val method: HttpMethod,
    val path: String,
    val body: String? = null,
) {
    suspend fun invoke(
        client: io.ktor.client.HttpClient,
        token: String?,
    ): io.ktor.client.statement.HttpResponse {
        return when (method) {
            HttpMethod.Get -> client.get(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }

            HttpMethod.Post -> client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                body?.let { setBody(it) }
            }

            else -> error("Unsupported method $method")
        }
    }
}

private class AuthOnlySupabaseGateway : SupabaseGateway {
    override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> {
        return Result.success(SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"))
    }

    override suspend fun profileExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean> = Result.success(false)

    override suspend fun insertUserProfile(
        accessToken: String,
        userId: String,
        profile: com.zenthek.model.UserProfileEntity,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun insertUserGoal(
        accessToken: String,
        userId: String,
        userGoal: com.zenthek.model.UserGoalEntity,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun insertCalorieTarget(
        accessToken: String,
        userId: String,
        calorieTarget: com.zenthek.model.CalorieTargetEntity,
    ): Result<Unit> = Result.success(Unit)
}
