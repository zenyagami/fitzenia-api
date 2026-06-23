package com.zenthek.routes

import com.zenthek.ai.AiClassifyInput
import com.zenthek.ai.AiClassifyResult
import com.zenthek.ai.AiGenerateInput
import com.zenthek.ai.AiGenerateResult
import com.zenthek.ai.AiSearchClient
import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.TestJwksServer
import com.zenthek.auth.configureAuthentication
import com.zenthek.auth.createSupabaseAccessToken
import com.zenthek.auth.createTestSupabaseConfig
import com.zenthek.auth.generateTestRsaKeyPair
import com.zenthek.config.SmartSearchConfig
import com.zenthek.fitzenio.rest.configureRateLimit
import com.zenthek.fitzenio.rest.configureSerialization
import com.zenthek.fitzenio.rest.configureStatusPages
import com.zenthek.model.CalorieTargetEntity
import com.zenthek.model.CanonicalEquivalentCandidate
import com.zenthek.model.CanonicalFoodEntity
import com.zenthek.model.CanonicalQueryMapRow
import com.zenthek.model.ImageAnalysisItem
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.InsertCanonicalFoodsPayload
import com.zenthek.model.InsertCanonicalFoodsResult
import com.zenthek.model.UserGoalEntity
import com.zenthek.model.UserProfileEntity
import com.zenthek.model.UpstreamSearchPage
import com.zenthek.service.FoodService
import com.zenthek.service.SmartSearchOrchestrator
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import com.zenthek.upstream.supabase.ExistingUserProfileIdentity
import com.zenthek.upstream.supabase.SupabaseAuthenticatedUser
import com.zenthek.upstream.supabase.SupabaseGateway
import com.zenthek.upstream.usda.UsdaClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FoodImageRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `analyze image repairs ml unit scaling before responding`() {
        withFoodRoutesAnalyzer(incidentResponse()) { accessToken ->
            val response = client.post("/api/food/analyze-image") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(requestJson())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<ImageAnalysisResponse>(response.bodyAsText())
            assertEquals(720.0, payload.totalCaloriesExact, absoluteTolerance = 0.000_001)
            assertEquals(1.0, payload.items.first().caloriesExact, absoluteTolerance = 0.000_001)
        }
    }

    @Test
    fun `analyze image stream emits error for unrecoverable nutrition`() {
        withFoodRoutesAnalyzer(unrecoverableResponse()) { accessToken ->
            val response = client.post("/api/food/analyze-image-stream") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(requestJson())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "event: status")
            assertContains(body, "event: error")
        }
    }

    @Test
    fun `analyze image returns bad gateway for unrecoverable nutrition`() {
        withFoodRoutesAnalyzer(unrecoverableResponse()) { accessToken ->
            val response = client.post("/api/food/analyze-image") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(requestJson())
            }

            assertEquals(HttpStatusCode.BadGateway, response.status)
        }
    }

    private fun withFoodRoutesAnalyzer(
        analyzerResponse: ImageAnalysisResponse,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(String) -> Unit,
    ) {
        val keyPair = generateTestRsaKeyPair("food-image-routes")
        TestJwksServer(listOf(keyPair)).use { jwksServer ->
            val accessToken = createSupabaseAccessToken(baseUrl = jwksServer.baseUrl, keyPair = keyPair)
            testApplication {
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureRateLimit()
                    configureAuthentication(createTestSupabaseConfig(jwksServer.baseUrl), AuthOnlySupabaseGateway())
                    routing {
                        authenticate(SUPABASE_AUTH_PROVIDER) {
                            route("/api/food") {
                                configureFoodRoutes(
                                    foodService = dummyFoodService(),
                                    smartSearch = dummySmartSearch(),
                                    imageAnalyzer = ImageAnalyzer { _, _, _, _, _ -> analyzerResponse },
                                )
                            }
                        }
                    }
                }
                block(accessToken)
            }
        }
    }

    private fun requestJson(): String {
        val image = Base64.getEncoder().encodeToString("fake-image".toByteArray())
        return """{"image":"$image"}"""
    }

    private fun dummyFoodService(): FoodService {
        val httpClient = HttpClient(MockEngine) { engine { addHandler { respond("{}") } } }
        return FoodService(OpenFoodFactsClient(httpClient), UsdaClient(httpClient, "test-key"))
    }

    private fun dummySmartSearch(): SmartSearchOrchestrator =
        SmartSearchOrchestrator(
            offSearch = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usdaSearch = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            catalog = FakeCanonicalCatalogGateway,
            ai = FakeAiSearchClient,
            config = SmartSearchConfig(
                enabled = false,
                usdaEnabled = false,
                aiRankModel = "test-rank",
                aiGenerateModel = "test-generate",
                aiClassifyTimeoutMs = 1,
                aiGenerateTimeoutMs = 1,
                aiSyncOnMiss = true,
                catalogWriteConfidenceThreshold = 0.7f,
            ),
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private fun incidentResponse() = response(
        totalCaloriesExact = 62_970.0,
        totalProteinG = 1_016.0,
        totalCarbsG = 3_818.0,
        totalFatG = 4_514.0,
        items = listOf(
            item("Creamy soup", "ml", 250.0, 250.0, 250.0, 4.0, 15.0, 18.0, 0.5, 500),
            item("Bread slice", "slice", 1.0, 30.0, 80.0, 3.0, 15.0, 1.0, 2.0, 150),
            item("Fusilli pasta", "portion", 1.0, 250.0, 360.0, 12.0, 48.0, 12.0, 3.0, 400),
            item("Garden salad", "portion", 1.0, 100.0, 30.0, 1.0, 5.0, 1.0, 2.0, 50),
        ),
    )

    private fun unrecoverableResponse() = response(
        totalCaloriesExact = 20_000.0,
        totalProteinG = 20.0,
        totalCarbsG = 20.0,
        totalFatG = 20.0,
        items = listOf(
            item("Mystery portion", "portion", 1.0, 200.0, 20_000.0, 20.0, 20.0, 20.0, 0.0, 0),
        ),
    )

    private fun response(
        totalCaloriesExact: Double,
        totalProteinG: Double,
        totalCarbsG: Double,
        totalFatG: Double,
        items: List<ImageAnalysisItem>,
    ) = ImageAnalysisResponse(
        errorCode = null,
        title = "Meal",
        subtitle = "Meal scan",
        isLikelyRestaurant = false,
        items = items,
        totalCalories = totalCaloriesExact.toInt(),
        totalCaloriesExact = totalCaloriesExact,
        totalProteinG = totalProteinG,
        totalCarbsG = totalCarbsG,
        totalFatG = totalFatG,
        totalFiberG = items.sumOf { it.fiberG ?: 0.0 },
        totalSodiumMg = items.sumOf { it.sodiumMg ?: 0 },
        totalSugarG = null,
        notes = null,
    )

    private fun item(
        name: String,
        servingUnit: String,
        servingCount: Double,
        weightG: Double,
        caloriesExact: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        fiberG: Double?,
        sodiumMg: Int?,
    ) = ImageAnalysisItem(
        name = name,
        portionDescription = "$servingCount $servingUnit",
        servingUnit = servingUnit,
        servingCount = servingCount,
        weightG = weightG,
        confidence = "high",
        calories = caloriesExact.toInt(),
        caloriesExact = caloriesExact,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        fiberG = fiberG,
        sodiumMg = sodiumMg,
        sugarG = null,
    )
}

private object FakeAiSearchClient : AiSearchClient {
    override suspend fun classify(input: AiClassifyInput): AiClassifyResult =
        error("not used")

    override suspend fun generate(input: AiGenerateInput): AiGenerateResult =
        error("not used")
}

private object FakeCanonicalCatalogGateway : CanonicalCatalogGateway {
    override suspend fun lookupQueryMappings(
        normalizedQuery: String,
        locale: String,
        country: String,
    ): Result<List<CanonicalQueryMapRow>> = Result.success(emptyList())

    override suspend fun readCanonicals(ids: List<String>): Result<List<CanonicalFoodEntity>> =
        Result.success(emptyList())

    override suspend fun insertCanonicalFoods(payload: InsertCanonicalFoodsPayload): Result<InsertCanonicalFoodsResult> =
        error("not used")

    override suspend fun findEquivalentCanonicalCandidates(
        englishLikeName: String,
        limit: Int,
    ): Result<List<CanonicalEquivalentCandidate>> = Result.success(emptyList())
}

private class AuthOnlySupabaseGateway : SupabaseGateway {
    override suspend fun fetchAuthenticatedUser(accessToken: String): Result<SupabaseAuthenticatedUser> =
        Result.success(SupabaseAuthenticatedUser(id = "user-1", email = "test@example.com"))

    override suspend fun profileExists(accessToken: String, userId: String): Result<Boolean> =
        error("not used")

    override suspend fun fetchUserProfileIdentity(accessToken: String, userId: String): Result<ExistingUserProfileIdentity?> =
        error("not used")

    override suspend fun updateUserProfileIdentity(
        accessToken: String,
        userId: String,
        name: String?,
        email: String?,
        avatarUrl: String?,
        lastModifiedAt: Long,
    ): Result<Unit> = error("not used")

    override suspend fun userGoalExists(accessToken: String, userId: String): Result<Boolean> =
        error("not used")

    override suspend fun calorieTargetExists(accessToken: String, userId: String): Result<Boolean> =
        error("not used")

    override suspend fun insertUserProfile(accessToken: String, userId: String, profile: UserProfileEntity): Result<Unit> =
        error("not used")

    override suspend fun insertUserGoal(accessToken: String, userId: String, userGoal: UserGoalEntity): Result<Unit> =
        error("not used")

    override suspend fun insertCalorieTarget(
        accessToken: String,
        userId: String,
        calorieTarget: CalorieTargetEntity,
    ): Result<Unit> = error("not used")
}
