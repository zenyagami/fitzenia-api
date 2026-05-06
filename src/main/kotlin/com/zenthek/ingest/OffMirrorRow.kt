package com.zenthek.ingest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Projected row destined for `public.off_food`. Field names + JSON layout
 * match the `upsert_off_products(items JSONB)` RPC signature exactly — the
 * REST writer encodes a list of these as the `items` payload.
 *
 * Generated columns (`primary_brand`) are computed Postgres-side and therefore
 * absent here.
 */
@Serializable
data class OffMirrorRow(
    val code: String,
    @SerialName("product_name") val productName: String? = null,
    val brands: List<String>? = null,
    @SerialName("countries_tags") val countriesTags: List<String>? = null,
    val lang: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("serving_quantity") val servingQuantity: Double? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("energy_kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("protein_100g") val protein100g: Double? = null,
    @SerialName("carbs_100g") val carbs100g: Double? = null,
    @SerialName("sugars_100g") val sugars100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("sodium_100g") val sodium100g: Double? = null,
    val nutriments: JsonObject? = null,
    @SerialName("last_modified_t") val lastModifiedT: Long,
)
