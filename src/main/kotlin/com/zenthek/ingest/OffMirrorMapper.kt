package com.zenthek.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Projects a raw OFF JSONL line into the slim [OffMirrorRow] that maps 1:1 to
 * `public.off_food`.
 *
 * OFF rows contain ~hundreds of fields; we keep only what the API actually uses
 * plus the full `nutriments` block (long-tail nutrients). The mapper is
 * permissive — any per-field decode error is swallowed and the field becomes
 * null. Hard requirements are `code` and `last_modified_t`; rows missing either
 * are dropped (mapper returns null).
 */
object OffMirrorMapper {

    fun mapJsonlLine(obj: JsonObject): OffMirrorRow? {
        val code = obj["code"]?.stringOrNull()?.takeIf(String::isNotBlank) ?: return null
        val lastModified = obj["last_modified_t"]?.longOrNumberOrNull() ?: return null

        val nutriments = obj["nutriments"]?.jsonObjectOrNull()

        return OffMirrorRow(
            code = sanitize(code),
            productName = obj["product_name"]?.stringOrNull()?.takeIf(String::isNotBlank)?.let(::sanitize),
            brands = parseBrands(obj)?.map(::sanitize),
            countriesTags = obj["countries_tags"]?.stringList()?.map(::sanitize),
            lang = obj["lang"]?.stringOrNull()?.let(::sanitize),
            servingSize = obj["serving_size"]?.stringOrNull()?.let(::sanitize),
            servingQuantity = obj["serving_quantity"]?.numericOrNull(),
            imageUrl = obj["image_url"]?.stringOrNull()?.let(::sanitize),
            energyKcal100g = nutriments?.get("energy-kcal_100g")?.numericOrNull(),
            protein100g = nutriments?.get("proteins_100g")?.numericOrNull(),
            carbs100g = nutriments?.get("carbohydrates_100g")?.numericOrNull(),
            sugars100g = nutriments?.get("sugars_100g")?.numericOrNull(),
            fat100g = nutriments?.get("fat_100g")?.numericOrNull(),
            saturatedFat100g = nutriments?.get("saturated-fat_100g")?.numericOrNull(),
            fiber100g = nutriments?.get("fiber_100g")?.numericOrNull(),
            sodium100g = nutriments?.get("sodium_100g")?.numericOrNull(),
            nutriments = nutriments?.let(::sanitizeJson)?.jsonObjectOrNull(),
            lastModifiedT = lastModified,
        )
    }

    // -----------------------------------------------------------------------
    // Sanitization
    // -----------------------------------------------------------------------
    // OFF product data occasionally contains literal U+0000 bytes (often inside
    // ingredient lists or auto-extracted text). Postgres rejects U+0000 in both
    // TEXT and JSONB with `22P05: unsupported Unicode escape sequence`, taking
    // down the entire 500-row batch. Strip them at the mapper boundary so a
    // poison row never reaches Supabase.
    //
    // The NUL char is constructed via Char(0) at runtime rather than written
    // as a string literal — keeping NUL out of the source file keeps editors,
    // diff tools, and code-review tooling well-behaved.

    private val NUL_CHAR: Char = Char(0)
    private val NUL_STRING: String = NUL_CHAR.toString()

    private fun sanitize(s: String): String =
        if (s.indexOf(NUL_CHAR) < 0) s else s.replace(NUL_STRING, "")

    /**
     * Recursively strips U+0000 from every string leaf in a [JsonElement].
     * Returns the same element when no change is needed (cheap fast path) and
     * a newly built element otherwise. Numbers, booleans, and nulls pass
     * through unchanged.
     */
    private fun sanitizeJson(element: JsonElement): JsonElement = when (element) {
        is JsonNull -> element
        is JsonPrimitive -> {
            val s = element.contentOrNull
            if (element.isString && s != null && s.indexOf(NUL_CHAR) >= 0) {
                JsonPrimitive(s.replace(NUL_STRING, ""))
            } else element
        }
        is JsonArray -> JsonArray(element.map(::sanitizeJson))
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> sanitizeJson(v) })
    }

    /**
     * OFF JSONL `brands` is a comma-separated string in the v1 export shape.
     * We split it; `brands_tags` (already a list) is preferred when present
     * because it survives display-text changes. Falls back to splitting the
     * csv. Whitespace-only entries are dropped.
     */
    private fun parseBrands(obj: JsonObject): List<String>? {
        val tags = obj["brands_tags"]?.stringList()?.filter(String::isNotBlank)
        if (!tags.isNullOrEmpty()) return tags
        val csv = obj["brands"]?.stringOrNull() ?: return null
        return csv.split(',').map(String::trim).filter(String::isNotBlank).ifEmpty { null }
    }

    // -----------------------------------------------------------------------
    // JsonElement coercion helpers — OFF mixes string/numeric/array shapes,
    // so each accessor tolerates the wrong type and returns null instead of
    // throwing. A row-level try/catch in the reader handles anything else.
    // -----------------------------------------------------------------------

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.numericOrNull(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        prim.doubleOrNull?.let { return it }
        return prim.contentOrNull?.toDoubleOrNull()
    }

    private fun JsonElement.longOrNumberOrNull(): Long? {
        val prim = this as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return prim.contentOrNull?.toLongOrNull()
    }

    private fun JsonElement.stringList(): List<String>? {
        val arr = (this as? JsonArray) ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
