package com.zenthek.model

import kotlinx.serialization.Serializable

@Serializable
enum class FoodSource {
    OPEN_FOOD_FACTS,
    FATSECRET,
    USDA,
    CANONICAL
}

@Serializable
data class NutritionInfo(
    val caloriesKcal: Float,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val fiberG: Float?,         // null = not available from this source
    val sodiumMg: Float?,       // null = not available
    val sugarG: Float?,         // null = not available
    val saturatedFatG: Float?,  // null = not available
    val cholesterolMg: Float? = null,
    val potassiumMg: Float? = null,
    val calciumMg: Float? = null,
    val ironMg: Float? = null
)

@Serializable
data class ServingSize(
    val name: String,           // Human-readable label: "100g", "1 serving (30g)", "1 cup"
    val weightGrams: Float,     // Weight in grams this serving represents (used for scaling)
    val nutrition: NutritionInfo
)

@Serializable
data class FoodItem(
    val id: String,                         // Unique ID: "{SOURCE}_{sourceSpecificId}" e.g. "OFF_0737628064502", "CAT_{uuid}"
    val name: String,                       // Product/food name, trimmed, title-cased if all-caps
    val brand: String?,                     // Brand name, null if unknown or generic food
    val barcode: String?,                   // EAN/UPC barcode, null if not applicable
    val source: FoodSource,                 // Which database this came from
    val imageUrl: String?,                  // Product image URL, null if unavailable
    val servings: List<ServingSize>,        // At least one entry always present
    val aiGenerated: Boolean = false,       // true when synthesized by Smart Food Search; client shows "estimated"
    val confidence: Float? = null,          // 0.0-1.0, present for AI-generated items
    val canonicalGroupId: String? = null    // links cross-locale canonical variants (same group_id = equivalent food)
)

@Serializable
data class SearchResponse(
    val results: List<FoodItem>,
    val totalResults: Int,
    val page: Int,
    val pageSize: Int
)

/**
 * Incremental bestMatch payload for the streaming search endpoint.
 * Emitted as the `bestMatch` SSE event after the `upstream` event.
 * Client swaps this into the UI once AI generation completes.
 * `bestMatch == null` = AI timed out, low-confidence, or no canonical found;
 * the client should drop the loading placeholder but keep upstream results visible.
 */
@Serializable
data class SearchStreamBestMatch(
    val bestMatch: FoodItem? = null,
    val bestMatchCandidates: List<FoodItem> = emptyList()
)

@Serializable
data class SmartSearchResponse(
    val bestMatch: FoodItem? = null,                         // page 0 only; top canonical/generic match
    val bestMatchCandidates: List<FoodItem> = emptyList(),   // up to 3, for broad queries like "sandwich"
    val genericMatches: List<FoodItem> = emptyList(),        // unbranded upstream + catalog; no overlap with bestMatch*
    val brandedMatches: List<FoodItem> = emptyList(),        // branded upstream (OFF, USDA Branded); no overlap with bestMatch*
    val totalResults: Int,                                   // items in THIS response's paginated pool: generic + branded sizes
    val hasMore: Boolean,                                    // true if the paginated pool likely has more pages
    val page: Int,
    val pageSize: Int
)

// ---------------------------------------------------------------------------
// Internal (non-serializable) helper types used inside the Smart Search flow.
// ResultKind is set by upstream mappers/clients and carried through the
// orchestrator so generic-vs-branded classification is decided once at the
// source, not re-derived downstream.
// ---------------------------------------------------------------------------

internal enum class ResultKind { GENERIC, BRANDED }

internal data class InternalFoodItem(
    val foodItem: FoodItem,
    val kind: ResultKind
)

internal data class UpstreamSearchPage(
    val items: List<InternalFoodItem>,
    val hasMore: Boolean
) {
    companion object {
        val EMPTY = UpstreamSearchPage(emptyList(), false)
    }
}

@Serializable
data class ApiError(
    val code: String,   // e.g. "MISSING_QUERY", "UPSTREAM_FAILURE"
    val message: String
)

@Serializable
data class ImageAnalysisItem(
    val name: String,                // SINGULAR form: "Taco al pastor", "Cola", "Sandwich"
    val portionDescription: String,  // Human-readable blurb
    val servingUnit: String,         // "taco" | "slice" | "cookie" | "sandwich" | "piece" |
                                     // "cup" | "tbsp" | "ml" | "g" | "oz" | "portion" | ...
    val servingCount: Double,        // how many of `servingUnit` detected (>0)
    val weightG: Double,             // TOTAL grams for the whole detected portion
    val confidence: String,
    // Per-ONE-servingUnit nutrition:
    val calories: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double?,
    val sodiumMg: Int? = null
)

@Serializable
data class ImageAnalysisResponse(
    val errorCode: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isLikelyRestaurant: Boolean = false,
    val items: List<ImageAnalysisItem>,
    val totalCalories: Int,
    val totalProteinG: Double,
    val totalCarbsG: Double,
    val totalFatG: Double,
    val totalFiberG: Double?,
    val totalSodiumMg: Int? = null,
    val notes: String? = null
)

@Serializable
data class AnalyzeImageRequest(
    val image: String,
    val mealTitle: String? = null,
    val additionalContext: String? = null,
    val locale: String? = null  // BCP 47 locale tag, e.g. "pt-BR", "fr-FR". Affects food names and notes language.
)
