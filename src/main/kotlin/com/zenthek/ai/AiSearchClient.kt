package com.zenthek.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Two-stage AI search decisions for the Smart Food Search orchestrator:
 *  1. [classify]  — given normalized query + upstream hits, decide whether
 *                   to reuse an existing hit or synthesize a canonical.
 *  2. [generate]  — given normalized query + upstream hits as grounding,
 *                   synthesize canonical food(s) with nutrition.
 *
 * v1 ships only GeminiAiSearchClient. Implementations must enforce strict
 * JSON output (via responseMimeType + responseJsonSchema for Gemini).
 *
 * Budgets (per plan): classify ≤200ms, generate ≤700ms, total ≤900ms.
 * The orchestrator wraps each call in `withTimeout(...)` and falls back
 * to upstream-only on timeout — the implementation should not retry.
 */
interface AiSearchClient {
    suspend fun classify(input: AiClassifyInput): AiClassifyResult

    suspend fun generate(input: AiGenerateInput): AiGenerateResult
}

// ---------------------------------------------------------------------------
// Classify stage
// ---------------------------------------------------------------------------

/**
 * One upstream hit passed to the AI stage. Keep this shape small — the
 * classifier and generator receive up to 10 of these per call.
 */
@Serializable
data class UpstreamHitSummary(
    val id: String,
    val name: String,
    val brand: String? = null,
    val kind: String,               // "GENERIC" | "BRANDED"
    @SerialName("per_100g_calories_kcal") val per100gCaloriesKcal: Float? = null,
    @SerialName("per_100g_protein_g") val per100gProteinG: Float? = null,
    @SerialName("per_100g_carbs_g") val per100gCarbsG: Float? = null,
    @SerialName("per_100g_fat_g") val per100gFatG: Float? = null
)

@Serializable
data class AiClassifyInput(
    @SerialName("normalized_query") val normalizedQuery: String,
    val locale: String,
    val country: String,
    val hits: List<UpstreamHitSummary>
)

/**
 * Cheap-stage decision. Determines whether to create a canonical, and if so,
 * whether the query is "specific" (cheesecake → 1 canonical) or "broad"
 * (sandwich → 2-3 canonical variants in bestMatchCandidates).
 */
@Serializable
data class AiClassifyResult(
    val decision: ClassifyDecision,
    @SerialName("candidate_ids") val candidateIds: List<String> = emptyList(),
    val confidence: Float
)

@Serializable
enum class ClassifyDecision {
    @SerialName("MATCH_EXISTING") MATCH_EXISTING,       // pick a hit from upstream; no generation needed
    @SerialName("NEED_CREATE_SPECIFIC") NEED_CREATE_SPECIFIC,  // synthesize one canonical
    @SerialName("NEED_CREATE_BROAD") NEED_CREATE_BROAD  // synthesize 2-3 canonical variants
}

// ---------------------------------------------------------------------------
// Generate stage
// ---------------------------------------------------------------------------

/** Existing canonical candidate passed to the generator for cross-locale linking. */
@Serializable
data class EquivalentCandidateHint(
    @SerialName("canonical_food_id") val canonicalFoodId: String,
    @SerialName("english_name") val englishName: String
)

@Serializable
data class AiGenerateInput(
    @SerialName("normalized_query") val normalizedQuery: String,
    val locale: String,
    val country: String,
    /** Broad means produce 2-3 variants; specific means produce exactly 1. */
    @SerialName("broad") val broad: Boolean,
    /** Upstream context — grounds the generation against real label data. */
    val hits: List<UpstreamHitSummary>,
    /** Cross-locale equivalence candidates; LLM may link to one of these. */
    @SerialName("equivalent_candidates") val equivalentCandidates: List<EquivalentCandidateHint> = emptyList()
)

@Serializable
data class AiGenerateResult(
    val items: List<AiGeneratedItem>
)

@Serializable
data class AiGeneratedItem(
    /** Canonical (unbranded) name in the target locale. */
    val name: String,
    /** Confidence 0.0-1.0. Orchestrator drops to catalog when >= threshold. */
    val confidence: Float,
    /**
     * If the LLM judges this canonical equivalent to an existing one in
     * [AiGenerateInput.equivalentCandidates], it returns that id here so the
     * new row is linked to the same canonical_group_id. Server-side nutrition
     * sanity gate may still reject the link.
     */
    @SerialName("link_to_canonical_food_id") val linkToCanonicalFoodId: String? = null,
    /** At minimum a 100g serving; orchestrator enforces this server-side. */
    val servings: List<AiGeneratedServing>
)

@Serializable
data class AiGeneratedServing(
    val name: String,
    @SerialName("weight_grams") val weightGrams: Float,
    @SerialName("calories_kcal") val caloriesKcal: Float,
    @SerialName("protein_g") val proteinG: Float,
    @SerialName("carbs_g") val carbsG: Float,
    @SerialName("fat_g") val fatG: Float,
    @SerialName("fiber_g") val fiberG: Float? = null,
    @SerialName("sodium_mg") val sodiumMg: Float? = null,
    @SerialName("sugar_g") val sugarG: Float? = null,
    @SerialName("saturated_fat_g") val saturatedFatG: Float? = null,
    @SerialName("cholesterol_mg") val cholesterolMg: Float? = null,
    @SerialName("potassium_mg") val potassiumMg: Float? = null,
    @SerialName("calcium_mg") val calciumMg: Float? = null,
    @SerialName("iron_mg") val ironMg: Float? = null
)
