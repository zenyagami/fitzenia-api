package com.zenthek.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Supabase catalog entities (read side). See db/migrations/001_canonical_food_catalog.sql.

@Serializable
data class CanonicalFoodEntity(
    val id: String,
    @SerialName("canonical_group_id") val canonicalGroupId: String,
    @SerialName("primary_locale") val primaryLocale: String,
    @SerialName("primary_country") val primaryCountry: String,
    @SerialName("ai_generated") val aiGenerated: Boolean,
    @SerialName("model_provider") val modelProvider: String,
    @SerialName("model_name") val modelName: String,
    val confidence: Float,
    // Embedded via PostgREST: ?select=*,servings:canonical_food_serving(*),terms:canonical_food_term(*)
    val servings: List<CanonicalServingEntity> = emptyList(),
    val terms: List<CanonicalTermEntity> = emptyList()
)

@Serializable
data class CanonicalServingEntity(
    val id: String,
    @SerialName("canonical_food_id") val canonicalFoodId: String,
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

@Serializable
data class CanonicalTermEntity(
    val id: String,
    @SerialName("canonical_food_id") val canonicalFoodId: String,
    val locale: String,
    val name: String,
    @SerialName("is_alias") val isAlias: Boolean
)

@Serializable
data class CanonicalQueryMapRow(
    @SerialName("canonical_food_id") val canonicalFoodId: String,
    val rank: Short
)

// RPC payload + result (write side)

@Serializable
data class InsertCanonicalFoodsPayload(
    @SerialName("normalized_query") val normalizedQuery: String,
    val locale: String,
    val country: String,
    val items: List<InsertCanonicalItem>
)

@Serializable
data class InsertCanonicalItem(
    val rank: Short,
    @SerialName("canonical_food") val canonicalFood: InsertCanonicalFood,
    val servings: List<InsertCanonicalServing>,
    val terms: List<InsertCanonicalTerm>,
    @SerialName("link_to_canonical_group_id") val linkToCanonicalGroupId: String? = null
)

@Serializable
data class InsertCanonicalFood(
    @SerialName("ai_generated") val aiGenerated: Boolean = true,
    @SerialName("model_provider") val modelProvider: String,
    @SerialName("model_name") val modelName: String,
    val confidence: Float
)

@Serializable
data class InsertCanonicalServing(
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

@Serializable
data class InsertCanonicalTerm(
    val locale: String,
    val name: String,
    @SerialName("is_alias") val isAlias: Boolean = false
)

@Serializable
data class InsertCanonicalFoodsResult(
    @SerialName("rank_to_canonical_food_id") val rankToCanonicalFoodId: Map<String, String>,
    val status: String  // "inserted" | "reused" | "partial"
) {
    val isInserted: Boolean get() = status == STATUS_INSERTED
    val isReused: Boolean get() = status == STATUS_REUSED
    val isPartial: Boolean get() = status == STATUS_PARTIAL

    companion object {
        const val STATUS_INSERTED = "inserted"
        const val STATUS_REUSED = "reused"
        const val STATUS_PARTIAL = "partial"
    }
}

/** Result of a trigram / ILIKE lookup for cross-locale equivalent canonicals. */
data class CanonicalEquivalentCandidate(
    val canonicalFoodId: String,
    val englishName: String
)
