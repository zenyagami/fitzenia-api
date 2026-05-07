package com.zenthek.ingest.usda

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Projected row destined for `public.usda_food`. Field names + JSON layout
 * match the `upsert_usda_foods(items JSONB)` RPC signature exactly — the
 * REST writer encodes a list of these as the `items` payload.
 *
 * Unit conventions match `db/migrations/004_usda_mirror.sql`: macros in grams
 * per 100g except [sodium100g] which is milligrams per 100g (FDC nutrient 1093
 * reports in mg natively). Long-tail nutrients live in [nutriments] keyed by
 * FDC nutrientId as a string ("1087" = calcium, "1089" = iron, etc.).
 */
@Serializable
data class UsdaMirrorRow(
    @SerialName("fdc_id") val fdcId: Long,
    @SerialName("data_type") val dataType: String,
    val description: String,
    @SerialName("brand_owner") val brandOwner: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    @SerialName("branded_food_category") val brandedFoodCategory: String? = null,
    @SerialName("market_country") val marketCountry: String? = null,
    @SerialName("gtin_upc") val gtinUpc: String? = null,
    val ingredients: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_size_unit") val servingSizeUnit: String? = null,
    @SerialName("household_serving_full_text") val householdServingFullText: String? = null,
    @SerialName("energy_kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("protein_100g") val protein100g: Double? = null,
    @SerialName("carbs_100g") val carbs100g: Double? = null,
    @SerialName("sugars_100g") val sugars100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("sodium_100g") val sodium100g: Double? = null,
    val nutriments: JsonObject? = null,
    @SerialName("publication_date") val publicationDate: String? = null,
    @SerialName("modified_date") val modifiedDate: String? = null,
    @SerialName("available_date") val availableDate: String? = null,
)

/** Discriminator for `data_type` column. */
object UsdaDataType {
    const val BRANDED = "branded_food"
    const val FOUNDATION = "foundation_food"
}
