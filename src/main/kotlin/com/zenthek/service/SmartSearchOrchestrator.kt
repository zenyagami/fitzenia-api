package com.zenthek.service

import com.zenthek.ai.AiClassifyInput
import com.zenthek.ai.AiGenerateInput
import com.zenthek.ai.AiGeneratedItem
import com.zenthek.ai.AiSearchClient
import com.zenthek.ai.ClassifyDecision
import com.zenthek.ai.EquivalentCandidateHint
import com.zenthek.ai.UpstreamHitSummary
import com.zenthek.config.SmartSearchConfig
import com.zenthek.model.CanonicalFoodEntity
import com.zenthek.model.CanonicalServingEntity
import com.zenthek.model.CanonicalTermEntity
import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.InsertCanonicalFood
import com.zenthek.model.InsertCanonicalFoodsPayload
import com.zenthek.model.InsertCanonicalItem
import com.zenthek.model.InsertCanonicalServing
import com.zenthek.model.InsertCanonicalTerm
import com.zenthek.model.InternalFoodItem
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ResultKind
import com.zenthek.model.ServingSize
import com.zenthek.model.SmartSearchResponse
import com.zenthek.model.UpstreamSearchPage
import com.zenthek.mapper.OpenFoodFactsMapper
import com.zenthek.mapper.UsdaMapper
import com.zenthek.upstream.supabase.CanonicalCatalogGateway
import com.zenthek.upstream.supabase.OffMirrorGateway
import com.zenthek.upstream.usda.UsdaMirrorGateway
import com.zenthek.model.SearchStreamBestMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.round
import org.slf4j.LoggerFactory

/**
 * Smart Food Search — sectioned search flow with shared canonical catalog.
 *
 * See `/Users/zenkun/.claude/plans/scalable-mapping-crayon.md` for the full
 * design. Flow summary on page==0:
 *   1. Normalize query + resolve locale/country.
 *   2. Parallel: catalog lookup AND upstream fan-out.
 *   3. If catalog hit → re-read canonicals, assemble, return.
 *   4. Split upstream by ResultKind (GENERIC vs BRANDED).
 *   5. Heuristic: exactly one GENERIC whole-token match → accept, no AI.
 *   6. AI classify (≤200ms) → decide MATCH_EXISTING / CREATE_SPECIFIC / CREATE_BROAD.
 *   7. AI generate (≤700ms, grounded on upstream hits + equivalent candidates).
 *   8. Validate nutrition → persist via RPC → handle status {inserted,reused,partial}.
 *   9. Assemble response with bestMatch/candidates removed from generic/branded pools.
 *
 * Flag-off mode: upstream-only, bestMatch=null. No catalog, no AI.
 * Page > 0: upstream-only pagination of generic+branded. No bestMatch, no AI.
 */
/**
 * Upstream search function: `(query, page, pageSize) -> UpstreamSearchPage`.
 * Allows tests to inject in-memory fakes without HTTP mocking. In production
 * both functions point at `{off,usda}Client::searchPaged`.
 */
internal fun interface UpstreamSearchFn {
    /**
     * @param locale  BCP 47 tag; upstream clients may extract the language code
     *                (e.g. "de-DE" → "de") and narrow search accordingly. USDA
     *                ignores this since it's US-English only.
     * @param country ISO 3166-1 alpha-2 (e.g. "DE"). OFF uses it to re-rank hits
     *                so products sold in the user's country come first while
     *                still returning all global hits. `null` or "GLOBAL" = no
     *                re-ranking. USDA ignores it.
     */
    suspend operator fun invoke(
        query: String,
        page: Int,
        pageSize: Int,
        locale: String?,
        country: String?
    ): UpstreamSearchPage
}

class SmartSearchOrchestrator internal constructor(
    private val offSearch: UpstreamSearchFn,
    private val usdaSearch: UpstreamSearchFn,
    private val catalog: CanonicalCatalogGateway,
    private val ai: AiSearchClient,
    private val config: SmartSearchConfig,
    private val backgroundScope: CoroutineScope,
    /**
     * Local OFF mirror gateway. Non-null in production (when
     * `OFF_MIRROR_READ_ENABLED=true`), null in development. Phase-1 mirror
     * fan-out queries this in parallel with [usdaMirror]. Null skips the OFF
     * mirror branch entirely.
     */
    private val offMirror: OffMirrorGateway? = null,
    /**
     * Local USDA mirror gateway. Non-null in production (when
     * `USDA_MIRROR_READ_ENABLED=true`), null in development. Phase-1 mirror
     * fan-out queries this in parallel with [offMirror]. US-only data — no
     * country filter. Null skips the USDA mirror branch entirely.
     */
    private val usdaMirror: UsdaMirrorGateway? = null,
) {
    private val log = LoggerFactory.getLogger(SmartSearchOrchestrator::class.java)

    /**
     * Dedup set for in-flight async generations. When two users hit `sandwich`
     * within the same instance before the first job finishes, the second one
     * skips launching. Per-instance only — across multiple Cloud Run containers
     * the RPC's slot-idempotency + ON CONFLICT DO NOTHING catches duplicates.
     */
    private val inFlightGenerations: MutableSet<BackgroundGenerationKey> =
        ConcurrentHashMap.newKeySet()

    private data class BackgroundGenerationKey(
        val normalizedQuery: String,
        val locale: String,
        val country: String
    )

    suspend fun search(
        query: String,
        locale: String,
        country: String?,
        page: Int,
        pageSize: Int,
        ipCountry: String? = null
    ): SmartSearchResponse = coroutineScope {
        @Suppress("NAME_SHADOWING")
        val locale = QueryNormalizer.canonicalLocale(locale)
        val normalized = QueryNormalizer.normalize(query)
        val resolvedCountry = resolveCountry(country, locale, ipCountry)

        if (normalized.isBlank()) {
            return@coroutineScope emptyResponse(page, pageSize)
        }

        // Flag-off or page > 0: simple upstream-only path
        if (!config.enabled || page > 0) {
            return@coroutineScope upstreamOnlySearch(
                normalizedQuery = normalized,
                country = resolvedCountry,
                page = page,
                pageSize = pageSize,
                reason = if (!config.enabled) "flag_off" else "page_gt_0",
                locale = locale
            )
        }

        // Page 0, flag on: full orchestrated flow.
        // Step 2: parallel catalog lookup + upstream fan-out.
        val catalogDeferred = async { runCatching { catalog.lookupQueryMappings(normalized, locale, resolvedCountry).getOrThrow() }.getOrNull() }
        val upstreamDeferred = async { fanOutUpstream(normalized, resolvedCountry, pageSize, locale) }

        val mappings = catalogDeferred.await()
        val upstream = upstreamDeferred.await()

        // Step 3: catalog hit? re-read canonicals, assemble, return.
        if (!mappings.isNullOrEmpty()) {
            val assembled = tryAssembleFromCatalog(mappings, normalized, locale, upstream, page, pageSize)
            if (assembled != null) {
                log.info("[SMART] catalog_hit query={} locale={} country={}", normalized, locale, resolvedCountry)
                return@coroutineScope assembled
            }
            log.warn("[SMART] catalog_inconsistent_read query={} locale={} country={}", normalized, locale, resolvedCountry)
            // Fall through to upstream + AI path.
        }

        // Step 4: split upstream.
        val generic = upstream.items.filter { it.kind == ResultKind.GENERIC }
        val branded = upstream.items.filter { it.kind == ResultKind.BRANDED }

        // Step 5: promote OFF exact-query matches to GENERIC (orchestrator-level; mapper has no query).
        val (promotedGeneric, remainingBranded) = promoteExactMatchesToGeneric(branded, normalized, existingGeneric = generic)

        // Step 6: heuristic — exactly one GENERIC whole-token match.
        val heuristicHit = heuristicSingleGenericMatch(promotedGeneric, normalized)
        if (heuristicHit != null) {
            log.info("[SMART] heuristic_accept query={} id={}", normalized, heuristicHit.foodItem.id)
            return@coroutineScope assembleResponse(
                bestMatch = heuristicHit.foodItem,
                candidates = emptyList(),
                generic = promotedGeneric,
                branded = remainingBranded,
                upstreamHasMore = upstream.hasMore,
                page = page,
                pageSize = pageSize
            )
        }

        // Step 7-10: AI classify + (maybe) generate + validate + persist.
        // Two modes:
        //   - syncOnMiss=true  : wait for AI, return with bestMatch (high latency first request).
        //   - syncOnMiss=false : fire background job, return upstream-only now (catalog warms for next user).
        if (!config.aiSyncOnMiss) {
            scheduleBackgroundGeneration(normalized, locale, resolvedCountry, upstream.items)
            return@coroutineScope assembleResponse(
                bestMatch = null,
                candidates = emptyList(),
                generic = promotedGeneric,
                branded = remainingBranded,
                upstreamHasMore = upstream.hasMore,
                page = page,
                pageSize = pageSize
            )
        }

        val aiOutcome = runAi(normalized, locale, resolvedCountry, upstream.items)

        return@coroutineScope when (aiOutcome) {
            is AiOutcome.UpstreamOnly -> {
                // Sync attempt timed out / errored. Fire a background retry so the
                // catalog still warms for the next user. scheduleBackgroundGeneration
                // dedupes via inFlightGenerations, so a parallel request for the same
                // (query, locale, country) won't queue a duplicate.
                scheduleBackgroundGeneration(normalized, locale, resolvedCountry, upstream.items)
                assembleResponse(
                    bestMatch = null,
                    candidates = emptyList(),
                    generic = promotedGeneric,
                    branded = remainingBranded,
                    upstreamHasMore = upstream.hasMore,
                    page = page,
                    pageSize = pageSize
                )
            }
            is AiOutcome.PickExisting -> {
                val pick = upstream.items.firstOrNull { it.foodItem.id == aiOutcome.id }
                    ?: return@coroutineScope assembleResponse(
                        bestMatch = null,
                        candidates = emptyList(),
                        generic = promotedGeneric,
                        branded = remainingBranded,
                        upstreamHasMore = upstream.hasMore,
                        page = page,
                        pageSize = pageSize
                    )
                assembleResponse(
                    bestMatch = pick.foodItem,
                    candidates = emptyList(),
                    generic = promotedGeneric,
                    branded = remainingBranded,
                    upstreamHasMore = upstream.hasMore,
                    page = page,
                    pageSize = pageSize
                )
            }
            is AiOutcome.Generated -> assembleResponse(
                bestMatch = aiOutcome.best,
                candidates = aiOutcome.candidates,
                generic = promotedGeneric,
                branded = remainingBranded,
                upstreamHasMore = upstream.hasMore,
                page = page,
                pageSize = pageSize
            )
        }
    }

    // -----------------------------------------------------------------------
    // Streaming variant — emits upstream event first, bestMatch event after AI
    // -----------------------------------------------------------------------

    /**
     * Streaming search. Unlike [search], always waits synchronously for the AI
     * regardless of [SmartSearchConfig.aiSyncOnMiss] — the whole point is to
     * deliver the bestMatch within the open request.
     *
     * Emits, in order:
     *  - EXACTLY ONE `Upstream` event as soon as upstream fan-out + catalog lookup complete.
     *    Includes bestMatch/candidates if the catalog hit or heuristic accepted; otherwise null.
     *  - At most ONE `BestMatch` event after AI classify+generate completes.
     *    Only emitted when the upstream event had null bestMatch AND AI ran.
     *    `bestMatch = null` inside the payload signals AI timeout/low-confidence;
     *    client should drop the loading placeholder.
     *  - ALWAYS `Done` at the end so the client has an unambiguous terminator.
     */
    fun searchAsFlow(
        query: String,
        locale: String,
        country: String?,
        page: Int,
        pageSize: Int,
        ipCountry: String? = null
    ): Flow<SearchStreamEvent> = flow {
        @Suppress("NAME_SHADOWING")
        val locale = QueryNormalizer.canonicalLocale(locale)
        val normalized = QueryNormalizer.normalize(query)
        val resolvedCountry = resolveCountry(country, locale, ipCountry)

        if (normalized.isBlank()) {
            emit(SearchStreamEvent.Upstream(emptyResponse(page, pageSize)))
            emit(SearchStreamEvent.Done)
            return@flow
        }

        // Flag off or page > 0: upstream-only, no AI, single upstream event.
        if (!config.enabled || page > 0) {
            val response = upstreamOnlySearch(
                normalizedQuery = normalized,
                country = resolvedCountry,
                page = page,
                pageSize = pageSize,
                reason = if (!config.enabled) "flag_off_stream" else "page_gt_0_stream",
                locale = locale
            )
            emit(SearchStreamEvent.Upstream(response))
            emit(SearchStreamEvent.Done)
            return@flow
        }

        // Parallel: catalog lookup + upstream fan-out.
        val parallel = coroutineScope {
            val catalogDeferred = async {
                runCatching {
                    catalog.lookupQueryMappings(normalized, locale, resolvedCountry).getOrThrow()
                }.getOrNull()
            }
            val upstreamDeferred = async { fanOutUpstream(normalized, resolvedCountry, pageSize, locale) }
            catalogDeferred.await() to upstreamDeferred.await()
        }
        val mappings = parallel.first
        val upstream = parallel.second

        // Catalog hit: emit single upstream event with the canonical, done.
        if (!mappings.isNullOrEmpty()) {
            val assembled = tryAssembleFromCatalog(mappings, normalized, locale, upstream, page, pageSize)
            if (assembled != null) {
                log.info("[SMART] stream_catalog_hit query={} locale={} country={}", normalized, locale, resolvedCountry)
                emit(SearchStreamEvent.Upstream(assembled))
                emit(SearchStreamEvent.Done)
                return@flow
            }
            log.warn("[SMART] stream_catalog_inconsistent_read query={} locale={} country={}", normalized, locale, resolvedCountry)
            // fall through to upstream + AI
        }

        // Split + promote + heuristic.
        val generic = upstream.items.filter { it.kind == ResultKind.GENERIC }
        val branded = upstream.items.filter { it.kind == ResultKind.BRANDED }
        val (promotedGeneric, remainingBranded) = promoteExactMatchesToGeneric(branded, normalized, generic)
        val heuristicHit = heuristicSingleGenericMatch(promotedGeneric, normalized)

        if (heuristicHit != null) {
            val response = assembleResponse(
                bestMatch = heuristicHit.foodItem,
                candidates = emptyList(),
                generic = promotedGeneric,
                branded = remainingBranded,
                upstreamHasMore = upstream.hasMore,
                page = page,
                pageSize = pageSize
            )
            emit(SearchStreamEvent.Upstream(response))
            emit(SearchStreamEvent.Done)
            return@flow
        }

        // No cheap win — emit upstream early (bestMatch null), then run AI.
        val upstreamOnly = assembleResponse(
            bestMatch = null,
            candidates = emptyList(),
            generic = promotedGeneric,
            branded = remainingBranded,
            upstreamHasMore = upstream.hasMore,
            page = page,
            pageSize = pageSize
        )
        emit(SearchStreamEvent.Upstream(upstreamOnly))

        // AI runs synchronously from the client's perspective — the request is still open.
        val aiOutcome = runAi(normalized, locale, resolvedCountry, upstream.items)
        val bestMatchPayload = when (aiOutcome) {
            AiOutcome.UpstreamOnly -> {
                // Same retry pattern as the non-streaming sync path: warm the catalog
                // for the next user even though this stream returned a null bestMatch.
                scheduleBackgroundGeneration(normalized, locale, resolvedCountry, upstream.items)
                SearchStreamBestMatch(null, emptyList())
            }
            is AiOutcome.PickExisting -> {
                val pick = upstream.items.firstOrNull { it.foodItem.id == aiOutcome.id }?.foodItem
                SearchStreamBestMatch(pick, emptyList())
            }
            is AiOutcome.Generated -> SearchStreamBestMatch(aiOutcome.best, aiOutcome.candidates)
        }
        emit(SearchStreamEvent.BestMatch(bestMatchPayload))
        emit(SearchStreamEvent.Done)
    }

    /**
     * Events emitted by [searchAsFlow]. Each event has a distinct SSE event name
     * (`upstream`, `bestMatch`, `done`); route layer maps the sealed subtypes to
     * the SSE wire format.
     */
    sealed interface SearchStreamEvent {
        data class Upstream(val response: SmartSearchResponse) : SearchStreamEvent
        data class BestMatch(val payload: SearchStreamBestMatch) : SearchStreamEvent
        data object Done : SearchStreamEvent
    }

    // -----------------------------------------------------------------------
    // Locale / country resolution
    // -----------------------------------------------------------------------

    /**
     * Country resolution order (first non-null wins):
     *  1. Explicit `country` query param from client (preferred — mobile app sends it from device region).
     *  2. Region segment of the locale (e.g. `es-US` → `US`).
     *  3. `ipCountry` from CDN/load-balancer geo header (fallback when client can't detect region).
     *  4. Sentinel `"GLOBAL"` — triggers non-US upstream path, catalog bucket isolated from real countries.
     *
     * Locale and country are orthogonal by design: locale drives name language; country drives
     * which upstream databases are queried (USDA requires US) and which catalog bucket is used.
     */
    private fun resolveCountry(country: String?, locale: String, ipCountry: String?): String {
        val explicit = validCountryCode(country)
        if (explicit != null) return explicit
        val fromLocale = localeRegion(locale)
        if (fromLocale != null) return fromLocale
        val fromIp = validCountryCode(ipCountry)
        if (fromIp != null) return fromIp
        return "GLOBAL"
    }

    private fun validCountryCode(raw: String?): String? {
        val trimmed = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        // Some CDNs return sentinels for unknown/anonymizing networks — reject them explicitly.
        if (trimmed in CDN_UNKNOWN_COUNTRY_SENTINELS) return null
        return trimmed.takeIf { it.length == 2 && it.all(Char::isLetter) }
    }

    private fun localeRegion(locale: String): String? {
        val parts = locale.split('-', '_')
        if (parts.size < 2) return null
        val region = parts[1].uppercase()
        return region.takeIf { it.length == 2 && it.all(Char::isLetter) }
    }

    // -----------------------------------------------------------------------
    // Upstream fan-out
    // -----------------------------------------------------------------------

    /**
     * Two-phase upstream fan-out for page 0. Phase 1 fires both mirrors in
     * parallel; if the merged result has at least [MIN_MIRROR_RESULTS_FOR_LIVE_SKIP]
     * items, we short-circuit and skip the live tier entirely (saves ~500ms+
     * vs the all-parallel pre-USDA-mirror shape). Otherwise Phase 2 fans out
     * to live OFF + USDA (gated by country/config) and merges.
     *
     * Dedup priority via list-order: OFF mirror → USDA mirror → live OFF →
     * live USDA. Mirror rows win when ids collide (cheaper, identical payload).
     */
    private suspend fun fanOutUpstream(
        normalizedQuery: String,
        country: String,
        pageSize: Int,
        locale: String?
    ): UpstreamSearchPage = coroutineScope {
        // Phase 1: mirrors in parallel.
        val offMirrorDeferred = async { fetchOffMirrorCandidates(normalizedQuery, country, pageSize) }
        val usdaMirrorDeferred = async { fetchUsdaMirrorCandidates(normalizedQuery, pageSize) }
        val offMirrorHits = offMirrorDeferred.await()
        val usdaMirrorHits = usdaMirrorDeferred.await()
        val mirrorMerged = dedupById(offMirrorHits + usdaMirrorHits)
        if (mirrorMerged.size >= MIN_MIRROR_RESULTS_FOR_LIVE_SKIP) {
            log.debug(
                "[SMART] phase1 mirror short-circuit query={} mirrorHits={}",
                normalizedQuery, mirrorMerged.size,
            )
            return@coroutineScope UpstreamSearchPage(items = mirrorMerged, hasMore = false)
        }

        // Phase 2: live fan-out only when phase 1 was sparse/empty.
        val offDeferred = async {
            runCatching { offSearch(normalizedQuery, 0, pageSize, locale, country) }
                .onFailure { log.warn("[SMART] OFF search failed: {}", it.message) }
                .getOrDefault(UpstreamSearchPage.EMPTY)
        }
        val usdaDeferred = async {
            if (country == "US" && config.usdaEnabled) {
                runCatching { usdaSearch(normalizedQuery, 0, pageSize, locale, country) }
                    .onFailure { log.warn("[SMART] USDA search failed: {}", it.message) }
                    .getOrDefault(UpstreamSearchPage.EMPTY)
            } else {
                UpstreamSearchPage.EMPTY
            }
        }
        val off = offDeferred.await()
        val usda = usdaDeferred.await()
        UpstreamSearchPage(
            items = dedupById(mirrorMerged + off.items + usda.items),
            hasMore = off.hasMore || usda.hasMore,
        )
    }

    /**
     * Local OFF mirror candidate recall. Returns empty when [offMirror] is
     * null (dev) or the gateway errors — never breaks the overall fan-out.
     * Mirror reflects OFF (branded-products db). Promotion to GENERIC happens
     * in the orchestrator via `promoteExactMatchesToGeneric`.
     */
    private suspend fun fetchOffMirrorCandidates(
        normalizedQuery: String,
        country: String,
        pageSize: Int,
    ): List<InternalFoodItem> {
        val gateway = offMirror ?: return emptyList()
        val rawCountry = country.takeIf { it != "GLOBAL" }
        val rows = runCatching { gateway.candidatesFor(normalizedQuery, rawCountry, pageSize).getOrThrow() }
            .onFailure { log.warn("[SMART] off mirror candidatesFor failed: {}", it.message) }
            .getOrDefault(emptyList())
        return rows.mapNotNull { row ->
            val item = OpenFoodFactsMapper.mapMirrorRow(row) ?: return@mapNotNull null
            InternalFoodItem(item, ResultKind.BRANDED)
        }
    }

    /**
     * Local USDA mirror candidate recall. Returns empty when [usdaMirror] is
     * null (dev) or the gateway errors. ResultKind classification matches
     * `UsdaMapper.mapSearchItemWithKind`: foundation_food → GENERIC,
     * branded_food → BRANDED. No country filter — FDC is US-only.
     */
    private suspend fun fetchUsdaMirrorCandidates(
        normalizedQuery: String,
        pageSize: Int,
    ): List<InternalFoodItem> {
        val gateway = usdaMirror ?: return emptyList()
        val rows = runCatching { gateway.candidatesFor(normalizedQuery, pageSize).getOrThrow() }
            .onFailure { log.warn("[SMART] usda mirror candidatesFor failed: {}", it.message) }
            .getOrDefault(emptyList())
        return rows.mapNotNull { row -> UsdaMapper.mapMirrorRowWithKind(row) }
    }

    /** Dedups by `FoodItem.id`, keeping the first occurrence (mirror first → wins). */
    private fun dedupById(items: List<InternalFoodItem>): List<InternalFoodItem> {
        if (items.isEmpty()) return items
        val seen = HashSet<String>(items.size)
        return items.filter { seen.add(it.foodItem.id) }
    }

    private suspend fun upstreamOnlySearch(
        normalizedQuery: String,
        country: String,
        page: Int,
        pageSize: Int,
        reason: String,
        locale: String?
    ): SmartSearchResponse = coroutineScope {
        // Page 0: same two-phase shape as fanOutUpstream (mirrors first, live
        // only on miss). Page > 0: upstream-only — mirror RPCs aren't pageable
        // and pagination behavior stays identical to the pre-mirror code path.
        val (mirrorMerged, off, usda) = if (page == 0) {
            val offMirrorDeferred = async { fetchOffMirrorCandidates(normalizedQuery, country, pageSize) }
            val usdaMirrorDeferred = async { fetchUsdaMirrorCandidates(normalizedQuery, pageSize) }
            val offMirrorHits = offMirrorDeferred.await()
            val usdaMirrorHits = usdaMirrorDeferred.await()
            val merged = dedupById(offMirrorHits + usdaMirrorHits)
            if (merged.size >= MIN_MIRROR_RESULTS_FOR_LIVE_SKIP) {
                Triple(merged, UpstreamSearchPage.EMPTY, UpstreamSearchPage.EMPTY)
            } else {
                val offDeferred = async {
                    runCatching { offSearch(normalizedQuery, page, pageSize, locale, country) }
                        .getOrDefault(UpstreamSearchPage.EMPTY)
                }
                val usdaDeferred = async {
                    if (country == "US" && config.usdaEnabled) {
                        runCatching { usdaSearch(normalizedQuery, page, pageSize, locale, country) }
                            .getOrDefault(UpstreamSearchPage.EMPTY)
                    } else {
                        UpstreamSearchPage.EMPTY
                    }
                }
                Triple(merged, offDeferred.await(), usdaDeferred.await())
            }
        } else {
            val offDeferred = async {
                runCatching { offSearch(normalizedQuery, page, pageSize, locale, country) }
                    .getOrDefault(UpstreamSearchPage.EMPTY)
            }
            val usdaDeferred = async {
                if (country == "US" && config.usdaEnabled) {
                    runCatching { usdaSearch(normalizedQuery, page, pageSize, locale, country) }
                        .getOrDefault(UpstreamSearchPage.EMPTY)
                } else {
                    UpstreamSearchPage.EMPTY
                }
            }
            Triple(emptyList<InternalFoodItem>(), offDeferred.await(), usdaDeferred.await())
        }
        val merged = dedupById(mirrorMerged + off.items + usda.items)
        val generic = merged.filter { it.kind == ResultKind.GENERIC }.map { it.foodItem }
        val branded = merged.filter { it.kind == ResultKind.BRANDED }.map { it.foodItem }
        val hasMore = off.hasMore || usda.hasMore || merged.size >= pageSize
        log.info("[SMART] upstream_only reason={} query={} returned={}", reason, normalizedQuery, merged.size)
        SmartSearchResponse(
            bestMatch = null,
            bestMatchCandidates = emptyList(),
            genericMatches = generic,
            brandedMatches = branded,
            totalResults = generic.size + branded.size,
            hasMore = hasMore,
            page = page,
            pageSize = pageSize
        )
    }

    // -----------------------------------------------------------------------
    // Heuristic + OFF promotion
    // -----------------------------------------------------------------------

    private fun promoteExactMatchesToGeneric(
        branded: List<InternalFoodItem>,
        normalizedQuery: String,
        existingGeneric: List<InternalFoodItem>
    ): Pair<List<InternalFoodItem>, List<InternalFoodItem>> {
        val promoted = mutableListOf<InternalFoodItem>()
        val remaining = mutableListOf<InternalFoodItem>()
        for (item in branded) {
            val foodItem = item.foodItem
            if (foodItem.source == FoodSource.OPEN_FOOD_FACTS &&
                foodItem.brand == null &&
                QueryNormalizer.exactTokenMatch(foodItem.name, normalizedQuery)
            ) {
                promoted += InternalFoodItem(foodItem, ResultKind.GENERIC)
            } else {
                remaining += item
            }
        }
        // Return (promoted + existing generic, remaining branded)
        return (existingGeneric + promoted) to remaining
    }

    private fun heuristicSingleGenericMatch(
        generic: List<InternalFoodItem>,
        normalizedQuery: String
    ): InternalFoodItem? {
        val matches = generic.filter { QueryNormalizer.containsAsWholeTokens(it.foodItem.name, normalizedQuery) }
        return matches.singleOrNull()
    }

    // -----------------------------------------------------------------------
    // AI stage
    // -----------------------------------------------------------------------

    private sealed interface AiOutcome {
        data object UpstreamOnly : AiOutcome
        data class PickExisting(val id: String) : AiOutcome
        data class Generated(val best: FoodItem, val candidates: List<FoodItem>) : AiOutcome
    }

    /**
     * Fires the same classify → generate → validate → persist pipeline that
     * [runAi] runs, but on [backgroundScope] so the request returns immediately
     * with upstream-only data. First user pays no AI latency; subsequent users
     * (after the write lands) get a catalog hit.
     *
     * Dedup: if an identical (query, locale, country) is already being worked
     * on by this instance, skip. The RPC's slot-idempotency catches cross-instance
     * duplicates at write time.
     */
    private fun scheduleBackgroundGeneration(
        normalizedQuery: String,
        locale: String,
        country: String,
        upstream: List<InternalFoodItem>
    ) {
        val key = BackgroundGenerationKey(normalizedQuery, locale, country)
        if (!inFlightGenerations.add(key)) {
            log.info(
                "[SMART] bg_skip_in_flight query={} locale={} country={}",
                normalizedQuery, locale, country
            )
            return
        }
        backgroundScope.launch {
            try {
                log.info(
                    "[SMART] bg_start query={} locale={} country={}",
                    normalizedQuery, locale, country
                )
                val outcome = runAi(normalizedQuery, locale, country, upstream)
                log.info(
                    "[SMART] bg_done query={} outcome={}",
                    normalizedQuery, outcome::class.simpleName
                )
            } catch (t: Throwable) {
                log.warn(
                    "[SMART] bg_error query={} msg={}",
                    normalizedQuery, t.message
                )
            } finally {
                inFlightGenerations.remove(key)
            }
        }
    }

    private suspend fun runAi(
        normalizedQuery: String,
        locale: String,
        country: String,
        upstream: List<InternalFoodItem>
    ): AiOutcome {
        val hits = upstream
            .sortedWith(compareBy { if (it.kind == ResultKind.GENERIC) 0 else 1 })
            .take(UPSTREAM_HITS_FOR_AI)
            .map { it.toSummary() }

        // Classify
        val classify = try {
            withTimeout(config.aiClassifyTimeoutMs) {
                ai.classify(AiClassifyInput(normalizedQuery, locale, country, hits))
            }
        } catch (_: TimeoutCancellationException) {
            log.warn("[SMART] ai_timeout stage=classify query={}", normalizedQuery)
            return AiOutcome.UpstreamOnly
        } catch (t: Throwable) {
            log.warn("[SMART] ai_error stage=classify query={} msg={}", normalizedQuery, t.message)
            return AiOutcome.UpstreamOnly
        }

        return when (classify.decision) {
            ClassifyDecision.MATCH_EXISTING -> {
                val pickId = classify.candidateIds.firstOrNull()
                if (pickId == null) {
                    log.info(
                        "[SMART] match_existing_empty_fallback_to_generate query={}",
                        normalizedQuery
                    )
                    runGenerate(
                        normalizedQuery = normalizedQuery,
                        locale = locale,
                        country = country,
                        hits = hits,
                        broad = false
                    )
                } else {
                    AiOutcome.PickExisting(pickId)
                }
            }
            ClassifyDecision.NEED_CREATE_SPECIFIC,
            ClassifyDecision.NEED_CREATE_BROAD -> runGenerate(
                normalizedQuery = normalizedQuery,
                locale = locale,
                country = country,
                hits = hits,
                broad = classify.decision == ClassifyDecision.NEED_CREATE_BROAD
            )
        }
    }

    private suspend fun runGenerate(
        normalizedQuery: String,
        locale: String,
        country: String,
        hits: List<UpstreamHitSummary>,
        broad: Boolean
    ): AiOutcome {
        val equivalents = runCatching {
            catalog.findEquivalentCanonicalCandidates(normalizedQuery, limit = 3).getOrElse { emptyList() }
        }.getOrElse { emptyList() }
            .map { EquivalentCandidateHint(it.canonicalFoodId, it.englishName) }

        val generated = try {
            withTimeout(config.aiGenerateTimeoutMs) {
                ai.generate(
                    AiGenerateInput(
                        normalizedQuery = normalizedQuery,
                        // Language only. The full locale ("en-DE") made the model read the
                        // region as the naming language. The catalog key below still uses
                        // the full locale — do not collapse it there or buckets merge.
                        language = localeLanguage(locale) ?: DEFAULT_CATALOG_LANGUAGE,
                        country = country,
                        broad = broad,
                        hits = hits,
                        equivalentCandidates = equivalents
                    )
                )
            }
        } catch (_: TimeoutCancellationException) {
            log.warn("[SMART] ai_timeout stage=generate query={}", normalizedQuery)
            return AiOutcome.UpstreamOnly
        } catch (t: Throwable) {
            log.warn("[SMART] ai_error stage=generate query={} msg={}", normalizedQuery, t.message)
            return AiOutcome.UpstreamOnly
        }
        log.debug("[SMART] ai_generated NLP food from {} -> response={}",generated.items, config.aiGenerateModel)
        if (generated.items.isEmpty()) return AiOutcome.UpstreamOnly

        // Micros are only guaranteed on the 100g reference serving; household
        // servings may arrive with them null. Derive the gaps before validation
        // so both the response and the persisted rows carry complete servings.
        val items = generated.items.map { it.withMicrosDerivedFromReference() }

        // Server-side nutrition validation. Items that fail are returned to the
        // client (with aiGenerated=true) but NOT persisted.
        val itemsWithValidity = items.map { it to NutritionValidator.validate(it) }
        val validItems = itemsWithValidity.filter { it.second is NutritionValidator.Result.Valid }.map { it.first }
        if (validItems.size < itemsWithValidity.size) {
            val invalid = itemsWithValidity.filter { it.second is NutritionValidator.Result.Invalid }
            log.warn(
                "[SMART] nutrition_validation_rejected query={} rejected={} reasons={}",
                normalizedQuery,
                invalid.size,
                invalid.joinToString { (it.second as NutritionValidator.Result.Invalid).reason }
            )
        }

        // Name coverage guard: for compound specific queries (≥2 significant tokens), prevent
        // a single-ingredient canonical (e.g. "Mango") from being stored for a compound query
        // (e.g. "mango matcha"). Items that fail are still shown to the user via generated.items
        // (Skipped path) but are NOT written to the catalog.
        val significantTokens = if (!broad) normalizedQuery.split(" ").filter { it.length > 3 } else emptyList()
        val itemsToPersist = if (significantTokens.size >= 2) {
            validItems.filter { item ->
                val nameLower = item.name.lowercase()
                val covered = significantTokens.count { nameLower.contains(it) }
                val ratio = covered.toFloat() / significantTokens.size
                if (ratio < 0.5f) {
                    log.warn("[SMART] name_coverage_rejected query={} name={} covered={}/{}", normalizedQuery, item.name, covered, significantTokens.size)
                    false
                } else true
            }
        } else validItems

        val persistResult: CatalogPersistResult = if (itemsToPersist.isNotEmpty() &&
            itemsToPersist.all { it.confidence >= config.catalogWriteConfidenceThreshold }
        ) {
            persistToCatalog(normalizedQuery, locale, country, itemsToPersist)
        } else {
            if (itemsToPersist.isNotEmpty()) {
                log.info("[SMART] low_confidence_skip query={} confidences={}",
                    normalizedQuery, itemsToPersist.joinToString { it.confidence.toString() })
            }
            CatalogPersistResult.Skipped
        }

        // For "reused" we must return the PERSISTED data (same bytes for every
        // concurrent user), not this request's losing in-memory generation.
        val assembledItems: List<FoodItem> = when (persistResult) {
            is CatalogPersistResult.Reused -> {
                val ids = persistResult.rankToCanonicalId.entries
                    .sortedBy { it.key }
                    .map { it.value }
                val canonicals = runCatching { catalog.readCanonicals(ids).getOrThrow() }
                    .onFailure { log.warn("[SMART] reused_reread_failed query={} msg={}", normalizedQuery, it.message) }
                    .getOrNull().orEmpty()
                val reusedItems = ids.mapNotNull { id ->
                    canonicals.firstOrNull { it.id == id }?.toFoodItem(locale, normalizedQuery)
                }
                if (reusedItems.size == ids.size) {
                    reusedItems
                } else {
                    log.warn(
                        "[SMART] reused_catalog_display_mismatch query={} locale={} country={} fallback=generated",
                        normalizedQuery,
                        locale,
                        country
                    )
                    items.map { item ->
                        item.toFoodItem(
                            canonicalId = null,
                            canonicalGroupId = null
                        )
                    }
                }
            }
            is CatalogPersistResult.Inserted -> items.mapIndexed { idx, item ->
                item.toFoodItem(
                    canonicalId = persistResult.rankToCanonicalId[idx.toShort()],
                    canonicalGroupId = null
                )
            }
            CatalogPersistResult.Skipped -> items.map { it.toFoodItem(canonicalId = null, canonicalGroupId = null) }
        }

        if (assembledItems.isEmpty()) return AiOutcome.UpstreamOnly
        val bestMatch = assembledItems.first()
        val candidates = if (broad) assembledItems.drop(1) else emptyList()
        return AiOutcome.Generated(bestMatch, candidates)
    }

    private sealed interface CatalogPersistResult {
        data class Inserted(val rankToCanonicalId: Map<Short, String>) : CatalogPersistResult
        data class Reused(val rankToCanonicalId: Map<Short, String>) : CatalogPersistResult
        data object Skipped : CatalogPersistResult
    }

    private suspend fun persistToCatalog(
        normalizedQuery: String,
        locale: String,
        country: String,
        items: List<AiGeneratedItem>
    ): CatalogPersistResult {
        val payload = InsertCanonicalFoodsPayload(
            normalizedQuery = normalizedQuery,
            locale = locale,
            country = country,
            items = items.mapIndexed { idx, item ->
                InsertCanonicalItem(
                    rank = idx.toShort(),
                    canonicalFood = InsertCanonicalFood(
                        aiGenerated = true,
                        modelProvider = "gemini",
                        modelName = config.aiGenerateModel,
                        confidence = item.confidence
                    ),
                    servings = item.servings.map { s ->
                        InsertCanonicalServing(
                            name = s.name,
                            weightGrams = s.weightGrams,
                            caloriesKcal = s.caloriesKcal,
                            proteinG = s.proteinG,
                            carbsG = s.carbsG,
                            fatG = s.fatG,
                            fiberG = s.fiberG,
                            sodiumMg = s.sodiumMg,
                            sugarG = s.sugarG,
                            saturatedFatG = s.saturatedFatG,
                            cholesterolMg = s.cholesterolMg,
                            potassiumMg = s.potassiumMg,
                            calciumMg = s.calciumMg,
                            ironMg = s.ironMg
                        )
                    },
                    terms = listOf(InsertCanonicalTerm(locale = locale, name = item.name, isAlias = false)),
                    linkToCanonicalGroupId = null // Link resolved by LLM → see sanity gate below
                )
            }
        )

        val result = runCatching { catalog.insertCanonicalFoods(payload).getOrThrow() }
            .onFailure { log.warn("[SMART] catalog_write_failed query={} msg={}", normalizedQuery, it.message) }
            .getOrNull() ?: return CatalogPersistResult.Skipped

        return when {
            result.isPartial -> {
                log.warn("[SMART] catalog_inconsistent_write query={} partial_slots={}", normalizedQuery, result.rankToCanonicalFoodId.keys)
                CatalogPersistResult.Skipped
            }
            result.isInserted -> CatalogPersistResult.Inserted(
                result.rankToCanonicalFoodId.mapKeys { it.key.toShort() }
            )
            result.isReused -> CatalogPersistResult.Reused(
                result.rankToCanonicalFoodId.mapKeys { it.key.toShort() }
            )
            else -> {
                log.warn("[SMART] catalog_unknown_status query={} status={}", normalizedQuery, result.status)
                CatalogPersistResult.Skipped
            }
        }
    }

    // -----------------------------------------------------------------------
    // Catalog -> FoodItem conversion (hit path)
    // -----------------------------------------------------------------------

    private suspend fun tryAssembleFromCatalog(
        mappings: List<com.zenthek.model.CanonicalQueryMapRow>,
        normalizedQuery: String,
        locale: String,
        upstream: UpstreamSearchPage,
        page: Int,
        pageSize: Int
    ): SmartSearchResponse? {
        val ids = mappings.map { it.canonicalFoodId }
        val canonicals = runCatching { catalog.readCanonicals(ids).getOrThrow() }
            .onFailure { log.warn("[SMART] catalog read failed: {}", it.message) }
            .getOrNull() ?: return null

        // Read-path invariant: only reuse catalog hits that can be displayed safely for this query/language.
        if (canonicals.size != ids.size) return null
        if (canonicals.any { it.servings.isEmpty() }) return null

        val rankedItems: List<Pair<Short, FoodItem>> = mappings.mapNotNull { m ->
            canonicals.firstOrNull { it.id == m.canonicalFoodId }
                ?.toFoodItem(locale, normalizedQuery)
                ?.let { m.rank to it }
        }.sortedBy { it.first }
        if (rankedItems.size != mappings.size) return null

        val bestMatch = rankedItems.first().second
        val candidates = rankedItems.drop(1).map { it.second }

        val branded = upstream.items.filter { it.kind == ResultKind.BRANDED }
        val generic = upstream.items.filter { it.kind == ResultKind.GENERIC }

        return assembleResponse(
            bestMatch = bestMatch,
            candidates = candidates,
            generic = generic,
            branded = branded,
            upstreamHasMore = upstream.hasMore,
            page = page,
            pageSize = pageSize
        )
    }

    private fun CanonicalFoodEntity.toFoodItem(
        targetLocale: String,
        normalizedQuery: String? = null
    ): FoodItem? {
        val name = displayNameForLocale(targetLocale) ?: return null
        if (!isDisplayNameCompatibleWithQuery(name, targetLocale, normalizedQuery)) return null
        return FoodItem(
            id = "CAT_$id",
            name = name,
            brand = null,
            barcode = null,
            source = FoodSource.CANONICAL,
            imageUrl = null,
            servings = servings.sortedWith(labeledFirst).map { it.toServingSize() },
            aiGenerated = aiGenerated,
            confidence = confidence,
            canonicalGroupId = canonicalGroupId
        )
    }

    private fun CanonicalFoodEntity.displayNameForLocale(targetLocale: String): String? {
        val targetLanguage = localeLanguage(targetLocale) ?: DEFAULT_CATALOG_LANGUAGE
        return terms.firstDisplayName { sameLocale(it.locale, targetLocale) }
            ?: terms.firstDisplayName {
                !sameLocale(it.locale, primaryLocale) && localeLanguage(it.locale) == targetLanguage
            }
            ?: terms.firstDisplayName {
                sameLocale(it.locale, primaryLocale) && localeLanguage(primaryLocale) == targetLanguage
            }
    }

    private fun isDisplayNameCompatibleWithQuery(
        displayName: String,
        targetLocale: String,
        normalizedQuery: String?
    ): Boolean {
        val targetLanguage = localeLanguage(targetLocale) ?: DEFAULT_CATALOG_LANGUAGE
        if (targetLanguage != DEFAULT_CATALOG_LANGUAGE) return true
        val query = normalizedQuery?.takeIf { it.isNotBlank() } ?: return true
        val normalizedName = QueryNormalizer.normalize(displayName)
        return QueryNormalizer.containsAsWholeTokens(displayName, query) ||
            QueryNormalizer.containsAsWholeTokens(query, normalizedName)
    }

    private fun List<CanonicalTermEntity>.firstDisplayName(
        predicate: (CanonicalTermEntity) -> Boolean
    ): String? = firstOrNull { !it.isAlias && predicate(it) }?.name

    private fun sameLocale(left: String, right: String): Boolean =
        left.equals(right, ignoreCase = true)

    private fun localeLanguage(locale: String): String? =
        locale.trim()
            .split('-', '_')
            .firstOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.all(Char::isLetter) }

    // Labeled household serving (e.g. "1 piece", "1 cup") sorts before "100g".
    // 100g is a reference unit, not the user-facing default — anything that is NOT
    // ~100g goes first; ~100g rows go to the end. Stable sort preserves original
    // order among non-100g servings. Returning a non-100g first lets the client
    // use servings[0] as the default selection for natural household units.
    private val labeledFirst = Comparator<CanonicalServingEntity> { a, b ->
        val aIs = abs(a.weightGrams - 100f) < 0.5f
        val bIs = abs(b.weightGrams - 100f) < 0.5f
        when {
            aIs && !bIs -> 1
            !aIs && bIs -> -1
            else -> 0
        }
    }

    private fun CanonicalServingEntity.toServingSize(): ServingSize = ServingSize(
        name = name,
        weightGrams = weightGrams,
        nutrition = NutritionInfo(
            caloriesKcal = caloriesKcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            fiberG = fiberG,
            sodiumMg = sodiumMg,
            sugarG = sugarG,
            saturatedFatG = saturatedFatG,
            cholesterolMg = cholesterolMg,
            potassiumMg = potassiumMg,
            calciumMg = calciumMg,
            ironMg = ironMg
        )
    )

    // -----------------------------------------------------------------------
    // AI item -> FoodItem conversion (generated path)
    // -----------------------------------------------------------------------

    private fun AiGeneratedItem.toFoodItem(canonicalId: String?, canonicalGroupId: String?): FoodItem {
        val idToUse = canonicalId?.let { "CAT_$it" } ?: "CAT_ephemeral_${System.nanoTime()}"
        return FoodItem(
            id = idToUse,
            name = name,
            brand = null,
            barcode = null,
            source = FoodSource.CANONICAL,
            imageUrl = null,
            servings = servings.map {
                ServingSize(
                    name = it.name,
                    weightGrams = it.weightGrams,
                    nutrition = NutritionInfo(
                        caloriesKcal = it.caloriesKcal,
                        proteinG = it.proteinG,
                        carbsG = it.carbsG,
                        fatG = it.fatG,
                        fiberG = it.fiberG,
                        sodiumMg = it.sodiumMg,
                        sugarG = it.sugarG,
                        saturatedFatG = it.saturatedFatG,
                        cholesterolMg = it.cholesterolMg,
                        potassiumMg = it.potassiumMg,
                        calciumMg = it.calciumMg,
                        ironMg = it.ironMg
                    )
                )
            },
            aiGenerated = true,
            confidence = confidence,
            canonicalGroupId = canonicalGroupId
        )
    }

    // A canonical food's per-gram composition is uniform, so a micro missing on
    // a household serving is exactly the 100g value scaled by weight. Filled
    // servings keep whatever the model did emit; only nulls are derived.
    private fun AiGeneratedItem.withMicrosDerivedFromReference(): AiGeneratedItem {
        val ref = servings.firstOrNull {
            it.name.trim().equals("100g", ignoreCase = true) || it.weightGrams in 99f..101f
        } ?: return this
        if (ref.weightGrams <= 0f) return this
        return copy(servings = servings.map { s ->
            if (s === ref || s.weightGrams <= 0f) return@map s
            val factor = s.weightGrams / ref.weightGrams
            fun derive(own: Float?, reference: Float?): Float? =
                own ?: reference?.let { round(it * factor * 10f) / 10f }
            s.copy(
                fiberG = derive(s.fiberG, ref.fiberG),
                sodiumMg = derive(s.sodiumMg, ref.sodiumMg),
                sugarG = derive(s.sugarG, ref.sugarG),
                saturatedFatG = derive(s.saturatedFatG, ref.saturatedFatG),
                cholesterolMg = derive(s.cholesterolMg, ref.cholesterolMg),
                potassiumMg = derive(s.potassiumMg, ref.potassiumMg),
                calciumMg = derive(s.calciumMg, ref.calciumMg),
                ironMg = derive(s.ironMg, ref.ironMg)
            )
        })
    }

    // -----------------------------------------------------------------------
    // Response assembly (dedup bestMatch/candidates from generic/branded)
    // -----------------------------------------------------------------------

    private fun assembleResponse(
        bestMatch: FoodItem?,
        candidates: List<FoodItem>,
        generic: List<InternalFoodItem>,
        branded: List<InternalFoodItem>,
        upstreamHasMore: Boolean,
        page: Int,
        pageSize: Int
    ): SmartSearchResponse {
        val excludedIds = buildSet<String> {
            if (bestMatch != null) add(bestMatch.id)
            addAll(candidates.map { it.id })
        }
        val genericFoodItems = generic.map { it.foodItem }.filter { it.id !in excludedIds }
        val brandedFoodItems = branded.map { it.foodItem }.filter { it.id !in excludedIds }
        val total = genericFoodItems.size + brandedFoodItems.size
        val hasMore = upstreamHasMore || total >= pageSize
        return SmartSearchResponse(
            bestMatch = bestMatch,
            bestMatchCandidates = candidates,
            genericMatches = genericFoodItems,
            brandedMatches = brandedFoodItems,
            totalResults = total,
            hasMore = hasMore,
            page = page,
            pageSize = pageSize
        )
    }

    private fun emptyResponse(page: Int, pageSize: Int): SmartSearchResponse = SmartSearchResponse(
        bestMatch = null,
        bestMatchCandidates = emptyList(),
        genericMatches = emptyList(),
        brandedMatches = emptyList(),
        totalResults = 0,
        hasMore = false,
        page = page,
        pageSize = pageSize
    )

    // -----------------------------------------------------------------------
    // Upstream -> AI summary
    // -----------------------------------------------------------------------

    private fun InternalFoodItem.toSummary(): UpstreamHitSummary {
        val hundredG = foodItem.servings.firstOrNull { abs(it.weightGrams - 100f) < 0.5f }
            ?: foodItem.servings.firstOrNull()
        return UpstreamHitSummary(
            id = foodItem.id,
            name = foodItem.name,
            brand = foodItem.brand,
            kind = kind.name,
            per100gCaloriesKcal = hundredG?.nutrition?.caloriesKcal,
            per100gProteinG = hundredG?.nutrition?.proteinG,
            per100gCarbsG = hundredG?.nutrition?.carbsG,
            per100gFatG = hundredG?.nutrition?.fatG
        )
    }

    private companion object {
        const val UPSTREAM_HITS_FOR_AI = 10

        /**
         * Minimum mirror-tier hit count to skip live fan-out entirely. Tunable
         * tradeoff: too low → skip live unnecessarily on rare/regional queries;
         * too high → never save the latency. Ship at 5; revisit after a week
         * of prod metrics.
         */
        const val MIN_MIRROR_RESULTS_FOR_LIVE_SKIP = 5

        // CDN / LB geo-header values that mean "unknown" — Cloudflare uses "XX"; "T1" = Tor.
        // Treat these as absent rather than as country codes.
        val CDN_UNKNOWN_COUNTRY_SENTINELS = setOf("XX", "T1", "ZZ")

        const val DEFAULT_CATALOG_LANGUAGE = "en"
    }
}
