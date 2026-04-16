package com.zenthek.service

import com.zenthek.ai.AiClassifyInput
import com.zenthek.ai.AiClassifyResult
import com.zenthek.ai.AiGenerateInput
import com.zenthek.ai.AiGenerateResult
import com.zenthek.ai.AiGeneratedItem
import com.zenthek.ai.AiGeneratedServing
import com.zenthek.ai.AiSearchClient
import com.zenthek.ai.ClassifyDecision
import com.zenthek.config.SmartSearchConfig
import com.zenthek.model.CanonicalEquivalentCandidate
import com.zenthek.model.CanonicalFoodEntity
import com.zenthek.model.CanonicalQueryMapRow
import com.zenthek.model.CanonicalServingEntity
import com.zenthek.model.CanonicalTermEntity
import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.InsertCanonicalFoodsPayload
import com.zenthek.model.InsertCanonicalFoodsResult
import com.zenthek.model.InternalFoodItem
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ResultKind
import com.zenthek.model.ServingSize
import com.zenthek.model.UpstreamSearchPage
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartSearchOrchestratorTest {

    // -----------------------------------------------------------------------
    // Flag-off + page > 0
    // -----------------------------------------------------------------------

    @Test
    fun `flag off returns upstream-only with null bestMatch`() = runBlocking {
        val ai = FakeAi()
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> page(listOf(brandedItem("OFF_1", "Greek Yogurt", brand = "Acme"))) },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog,
            config = defaultConfig().copy(enabled = false)
        )

        val response = orchestrator.search("greek yogurt", "en-US", "US", page = 0, pageSize = 10)

        assertNull(response.bestMatch)
        assertEquals(1, response.brandedMatches.size)
        assertEquals(0, ai.classifyCalls)
        assertEquals(0, catalog.lookupCalls)
    }

    @Test
    fun `page gt 0 returns upstream-only with null bestMatch`() = runBlocking {
        val ai = FakeAi()
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, p, _, _, _ -> page(listOf(brandedItem("OFF_P$p", "Something", brand = "Acme"))) },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("something", "en-US", "US", page = 2, pageSize = 10)

        assertNull(response.bestMatch)
        assertEquals(2, response.page)
        assertEquals(0, ai.classifyCalls)
        assertEquals(0, catalog.lookupCalls)
    }

    // -----------------------------------------------------------------------
    // Catalog hit
    // -----------------------------------------------------------------------

    @Test
    fun `catalog hit returns bestMatch from catalog and skips AI`() = runBlocking {
        val ai = FakeAi()
        val catalog = FakeCatalog(
            mappings = mapOf(
                QueryKey("cheesecake", "en-US", "US") to listOf(CanonicalQueryMapRow("cat-1", 0))
            ),
            reads = mapOf(
                "cat-1" to canonicalEntity("cat-1", nameByLocale = mapOf("en-US" to "Cheesecake"))
            )
        )
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> page(listOf(brandedItem("OFF_9", "Cheesecake Factory Original", brand = "Factory"))) },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("Cheesecake", "en-US", "US", page = 0, pageSize = 10)

        assertNotNull(response.bestMatch)
        assertEquals("CAT_cat-1", response.bestMatch!!.id)
        assertEquals(FoodSource.CANONICAL, response.bestMatch!!.source)
        assertEquals(0, ai.classifyCalls)
        assertEquals(0, ai.generateCalls)
        assertEquals(1, response.brandedMatches.size)
    }

    // -----------------------------------------------------------------------
    // Heuristic accept: exactly one generic match, no AI
    // -----------------------------------------------------------------------

    @Test
    fun `single generic match triggers heuristic accept and skips AI`() = runBlocking {
        val ai = FakeAi()
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ ->
                page(
                    listOf(
                        genericItem("USDA_1", "Bananas, raw"),
                        brandedItem("USDA_2", "Chiquita Banana Chips", brand = "Chiquita")
                    )
                )
            },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("banana", "en-US", "US", page = 0, pageSize = 10)

        assertNotNull(response.bestMatch)
        assertEquals("USDA_1", response.bestMatch!!.id)
        assertEquals(0, ai.classifyCalls)
        // Single branded item remains in branded section (bestMatch removed from generic)
        assertEquals(0, response.genericMatches.size)
        assertEquals(1, response.brandedMatches.size)
    }

    @Test
    fun `multiple generic matches skip heuristic and call classify`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(
                ClassifyDecision.MATCH_EXISTING,
                candidateIds = listOf("USDA_1"),
                confidence = 0.9f
            )
        )
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ ->
                page(
                    listOf(
                        genericItem("USDA_1", "Sandwich, chicken salad"),
                        genericItem("USDA_2", "Sandwich, tuna salad"),
                        genericItem("USDA_3", "Sandwich, roast beef")
                    )
                )
            },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("sandwich", "en-US", "US", page = 0, pageSize = 10)

        assertEquals(1, ai.classifyCalls) // heuristic didn't accept; AI was invoked
        assertNotNull(response.bestMatch)
        assertEquals("USDA_1", response.bestMatch!!.id)
    }

    // -----------------------------------------------------------------------
    // AI generate SPECIFIC + persist
    // -----------------------------------------------------------------------

    @Test
    fun `generate specific with high confidence persists to catalog`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_SPECIFIC, emptyList(), 0.9f),
            generateResult = AiGenerateResult(
                items = listOf(
                    generatedItem(name = "Cheesecake", confidence = 0.9f)
                )
            )
        )
        val catalog = FakeCatalog(
            insertResult = InsertCanonicalFoodsResult(
                rankToCanonicalFoodId = mapOf("0" to "cat-new"),
                status = InsertCanonicalFoodsResult.STATUS_INSERTED
            )
        )
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> page(listOf(brandedItem("OFF_99", "Philadelphia NY Cheesecake", brand = "Philadelphia"))) },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("cheesecake", "ja-JP", country = null, page = 0, pageSize = 10)

        assertEquals(1, ai.generateCalls)
        assertEquals(1, catalog.insertCalls)
        assertNotNull(response.bestMatch)
        assertTrue(response.bestMatch!!.aiGenerated)
        assertEquals("CAT_cat-new", response.bestMatch!!.id)
    }

    @Test
    fun `generate broad returns multiple bestMatchCandidates`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_BROAD, emptyList(), 0.85f),
            generateResult = AiGenerateResult(
                items = listOf(
                    generatedItem(name = "Chicken Sandwich", confidence = 0.88f),
                    generatedItem(name = "Tuna Sandwich", confidence = 0.86f),
                    generatedItem(name = "Beef Sandwich", confidence = 0.82f)
                )
            )
        )
        val catalog = FakeCatalog(
            insertResult = InsertCanonicalFoodsResult(
                rankToCanonicalFoodId = mapOf("0" to "cat-s0", "1" to "cat-s1", "2" to "cat-s2"),
                status = InsertCanonicalFoodsResult.STATUS_INSERTED
            )
        )
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("sandwich", "en-US", "US", page = 0, pageSize = 10)

        assertNotNull(response.bestMatch)
        assertEquals("Chicken Sandwich", response.bestMatch!!.name)
        assertEquals(2, response.bestMatchCandidates.size)
    }

    // -----------------------------------------------------------------------
    // Low confidence: returned but not persisted
    // -----------------------------------------------------------------------

    @Test
    fun `low confidence generation returns item but does not persist`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_SPECIFIC, emptyList(), 0.9f),
            generateResult = AiGenerateResult(
                items = listOf(generatedItem(name = "Obscure Food", confidence = 0.4f))
            )
        )
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("obscurefood", "en-US", "US", page = 0, pageSize = 10)

        assertNotNull(response.bestMatch)
        assertTrue(response.bestMatch!!.aiGenerated)
        assertEquals(0, catalog.insertCalls)
    }

    // -----------------------------------------------------------------------
    // Timeouts
    // -----------------------------------------------------------------------

    @Test
    fun `classify timeout returns upstream-only with 200 equivalent shape`() = runBlocking {
        val ai = FakeAi(classifySleepMs = 500L) // > 200ms classify budget
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> page(listOf(brandedItem("OFF_1", "Thing", brand = "X"))) },
            usda = { _, _, _, _, _ ->
                page(
                    listOf(
                        genericItem("USDA_1", "Thing type A"),
                        genericItem("USDA_2", "Thing type B")
                    )
                )
            },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("thing", "en-US", "US", page = 0, pageSize = 10)

        assertNull(response.bestMatch)
        assertEquals(0, ai.generateCalls) // didn't proceed to generate
        assertTrue(response.genericMatches.size + response.brandedMatches.size > 0)
    }

    @Test
    fun `generate timeout returns upstream-only`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_SPECIFIC, emptyList(), 0.9f),
            generateSleepMs = 1000L // > 700ms generate budget
        )
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> page(listOf(brandedItem("OFF_1", "Thing", brand = "X"))) },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("thing", "en-US", "US", page = 0, pageSize = 10)

        assertNull(response.bestMatch)
        assertEquals(0, catalog.insertCalls)
    }

    // -----------------------------------------------------------------------
    // Reused: concurrent-race case — persisted data wins, not in-memory generation
    // -----------------------------------------------------------------------

    @Test
    fun `reused write re-reads canonical and ignores in-memory generation`() = runBlocking {
        // Scenario: two racers generate slightly different nutrition. The RPC returns
        // status=reused with the winning row's ID. This request must surface the
        // CATALOG-persisted data (re-read), not its own losing in-memory item.
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_SPECIFIC, emptyList(), 0.9f),
            generateResult = AiGenerateResult(
                items = listOf(generatedItem(name = "My Local Generation (loser)", confidence = 0.9f))
            )
        )
        // Catalog write returns "reused" pointing at cat-winner; read returns the winning row's data.
        val catalog = FakeCatalog(
            reads = mapOf(
                "cat-winner" to canonicalEntity(
                    "cat-winner",
                    nameByLocale = mapOf("en-US" to "Winner's Cheesecake")
                )
            ),
            insertResult = InsertCanonicalFoodsResult(
                rankToCanonicalFoodId = mapOf("0" to "cat-winner"),
                status = InsertCanonicalFoodsResult.STATUS_REUSED
            )
        )
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("cheesecake", "en-US", "US", page = 0, pageSize = 10)

        assertNotNull(response.bestMatch)
        // The critical assertion: we see the PERSISTED row, not our in-memory generation
        assertEquals("Winner's Cheesecake", response.bestMatch!!.name)
        assertEquals("CAT_cat-winner", response.bestMatch!!.id)
    }

    // -----------------------------------------------------------------------
    // Partial catalog write status
    // -----------------------------------------------------------------------

    @Test
    fun `catalog write partial status does not populate bestMatch from persistence`() = runBlocking {
        val ai = FakeAi(
            classifyResult = AiClassifyResult(ClassifyDecision.NEED_CREATE_SPECIFIC, emptyList(), 0.9f),
            generateResult = AiGenerateResult(items = listOf(generatedItem(name = "Weird", confidence = 0.9f)))
        )
        val catalog = FakeCatalog(
            insertResult = InsertCanonicalFoodsResult(
                rankToCanonicalFoodId = emptyMap(),
                status = InsertCanonicalFoodsResult.STATUS_PARTIAL
            )
        )
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("weird", "en-US", "US", page = 0, pageSize = 10)

        // Item is still returned to client (aiGenerated), but with ephemeral id (no canonical-id mapping)
        assertNotNull(response.bestMatch)
        assertTrue(response.bestMatch!!.id.startsWith("CAT_ephemeral_"))
    }

    // -----------------------------------------------------------------------
    // bestMatch / candidates deduped from generic/branded
    // -----------------------------------------------------------------------

    @Test
    fun `bestMatch is removed from generic and branded sections`() = runBlocking {
        val ai = FakeAi()
        val catalog = FakeCatalog()
        val orchestrator = buildOrchestrator(
            off = { _, _, _, _, _ -> UpstreamSearchPage.EMPTY },
            usda = { _, _, _, _, _ ->
                page(
                    listOf(
                        genericItem("USDA_1", "Bananas, raw"), // will become bestMatch via heuristic
                        brandedItem("USDA_2", "Acme Banana Bread", brand = "Acme")
                    )
                )
            },
            ai = ai,
            catalog = catalog
        )

        val response = orchestrator.search("banana", "en-US", "US", page = 0, pageSize = 10)

        assertEquals("USDA_1", response.bestMatch?.id)
        assertFalse(response.genericMatches.any { it.id == "USDA_1" })
        assertFalse(response.brandedMatches.any { it.id == "USDA_1" })
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun defaultConfig() = SmartSearchConfig(
        enabled = true,
        usdaEnabled = true,
        aiRankModel = "gemini-test-rank",
        aiGenerateModel = "gemini-test-gen",
        aiClassifyTimeoutMs = 200L,      // tight in tests to exercise timeout paths
        aiGenerateTimeoutMs = 700L,
        aiSyncOnMiss = true,             // existing tests assert sync bestMatch; async mode has its own shape
        catalogWriteConfidenceThreshold = 0.7f
    )

    private fun buildOrchestrator(
        off: UpstreamSearchFn,
        usda: UpstreamSearchFn,
        ai: AiSearchClient,
        catalog: CanonicalCatalogGateway,
        config: SmartSearchConfig = defaultConfig()
    ) = SmartSearchOrchestrator(
        offSearch = off,
        usdaSearch = usda,
        catalog = catalog,
        ai = ai,
        config = config,
        backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    )

    private fun page(items: List<InternalFoodItem>, hasMore: Boolean = false) =
        UpstreamSearchPage(items, hasMore)

    private fun genericItem(id: String, name: String) = InternalFoodItem(
        foodItem = FoodItem(
            id = id, name = name, brand = null, barcode = null,
            source = FoodSource.USDA, imageUrl = null,
            servings = listOf(serving100g())
        ),
        kind = ResultKind.GENERIC
    )

    private fun brandedItem(id: String, name: String, brand: String?) = InternalFoodItem(
        foodItem = FoodItem(
            id = id, name = name, brand = brand, barcode = null,
            source = if (id.startsWith("OFF")) FoodSource.OPEN_FOOD_FACTS else FoodSource.USDA,
            imageUrl = null,
            servings = listOf(serving100g())
        ),
        kind = ResultKind.BRANDED
    )

    private fun serving100g() = ServingSize(
        name = "100g", weightGrams = 100f,
        nutrition = NutritionInfo(
            caloriesKcal = 200f, proteinG = 10f, carbsG = 20f, fatG = 8f,
            fiberG = null, sodiumMg = null, sugarG = null, saturatedFatG = null
        )
    )

    private fun generatedItem(name: String, confidence: Float) = AiGeneratedItem(
        name = name,
        confidence = confidence,
        servings = listOf(
            AiGeneratedServing(
                name = "100g", weightGrams = 100f,
                caloriesKcal = 200f, proteinG = 10f, carbsG = 20f, fatG = 8f
            )
        )
    )

    private fun canonicalEntity(id: String, nameByLocale: Map<String, String>) = CanonicalFoodEntity(
        id = id,
        canonicalGroupId = "grp-$id",
        primaryLocale = nameByLocale.keys.first(),
        primaryCountry = "US",
        aiGenerated = true,
        modelProvider = "gemini",
        modelName = "gemini-test-gen",
        confidence = 0.9f,
        servings = listOf(
            CanonicalServingEntity(
                id = "s-$id", canonicalFoodId = id, name = "100g", weightGrams = 100f,
                caloriesKcal = 321f, proteinG = 5.5f, carbsG = 26f, fatG = 22.5f
            )
        ),
        terms = nameByLocale.map { (locale, name) ->
            CanonicalTermEntity(
                id = "t-$locale-$id",
                canonicalFoodId = id,
                locale = locale,
                name = name,
                isAlias = false
            )
        }
    )

    // -------- Fakes --------

    private data class QueryKey(val normalizedQuery: String, val locale: String, val country: String)

    private class FakeAi(
        private val classifyResult: AiClassifyResult = AiClassifyResult(ClassifyDecision.MATCH_EXISTING, emptyList(), 0f),
        private val generateResult: AiGenerateResult = AiGenerateResult(emptyList()),
        private val classifySleepMs: Long = 0L,
        private val generateSleepMs: Long = 0L
    ) : AiSearchClient {
        var classifyCalls: Int = 0
            private set
        var generateCalls: Int = 0
            private set

        override suspend fun classify(input: AiClassifyInput): AiClassifyResult {
            classifyCalls++
            if (classifySleepMs > 0) delay(classifySleepMs)
            return classifyResult
        }

        override suspend fun generate(input: AiGenerateInput): AiGenerateResult {
            generateCalls++
            if (generateSleepMs > 0) delay(generateSleepMs)
            return generateResult
        }
    }

    private class FakeCatalog(
        private val mappings: Map<QueryKey, List<CanonicalQueryMapRow>> = emptyMap(),
        private val reads: Map<String, CanonicalFoodEntity> = emptyMap(),
        private val insertResult: InsertCanonicalFoodsResult = InsertCanonicalFoodsResult(
            rankToCanonicalFoodId = mapOf("0" to "cat-stub"),
            status = InsertCanonicalFoodsResult.STATUS_INSERTED
        )
    ) : CanonicalCatalogGateway {
        var lookupCalls: Int = 0
            private set
        var insertCalls: Int = 0
            private set

        override suspend fun lookupQueryMappings(
            normalizedQuery: String,
            locale: String,
            country: String
        ): Result<List<CanonicalQueryMapRow>> {
            lookupCalls++
            return Result.success(mappings[QueryKey(normalizedQuery, locale, country)].orEmpty())
        }

        override suspend fun readCanonicals(ids: List<String>): Result<List<CanonicalFoodEntity>> =
            Result.success(ids.mapNotNull { reads[it] })

        override suspend fun insertCanonicalFoods(
            payload: InsertCanonicalFoodsPayload
        ): Result<InsertCanonicalFoodsResult> {
            insertCalls++
            return Result.success(insertResult)
        }

        override suspend fun findEquivalentCanonicalCandidates(
            englishLikeName: String,
            limit: Int
        ): Result<List<CanonicalEquivalentCandidate>> = Result.success(emptyList())
    }
}
