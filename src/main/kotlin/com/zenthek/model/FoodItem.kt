package com.zenthek.model

import kotlinx.serialization.Serializable

@Serializable
enum class FoodSource {
    OPEN_FOOD_FACTS,
    FATSECRET,
    USDA
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
    val saturatedFatG: Float?   // null = not available
)

@Serializable
data class ServingSize(
    val name: String,           // Human-readable label: "100g", "1 serving (30g)", "1 cup"
    val weightGrams: Float,     // Weight in grams this serving represents (used for scaling)
    val nutrition: NutritionInfo
)

@Serializable
data class FoodItem(
    val id: String,             // Unique ID: "{SOURCE}_{sourceSpecificId}" e.g. "OFF_0737628064502"
    val name: String,           // Product/food name, trimmed, title-cased if all-caps
    val brand: String?,         // Brand name, null if unknown or generic food
    val barcode: String?,       // EAN/UPC barcode, null if not applicable
    val source: FoodSource,     // Which database this came from
    val imageUrl: String?,      // Product image URL, null if unavailable
    val servings: List<ServingSize>  // At least one entry always present
)

@Serializable
data class SearchResponse(
    val results: List<FoodItem>,
    val totalResults: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class ApiError(
    val code: String,   // e.g. "MISSING_QUERY", "UPSTREAM_FAILURE"
    val message: String
)
