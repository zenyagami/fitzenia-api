package com.zenthek.ingest.usda

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Projects a raw FDC bulk JSON food object into a slim [UsdaMirrorRow].
 *
 * FDC bulk JSON differs from the live API:
 *   - Top-level wrapper is `{"BrandedFoods":[...]}` or `{"FoundationFoods":[...]}`.
 *   - `foodNutrients[]` reports per-100g for both Branded and Foundation
 *     (different from the search API which is per-serving for Branded).
 *   - Branded entries also have `labelNutrients` per-serving — only consulted
 *     when the per-100g entry for a given nutrient is missing.
 *
 * Hard requirements (return null if missing):
 *   - `fdcId`
 *   - `description`
 *
 * Strings are NUL-sanitized — Postgres rejects U+0000 in TEXT/JSONB with
 * `22P05`, taking down the entire batch.
 */
object UsdaMirrorMapper {

    /** FDC nutrientId constants — matches `UsdaMapper.UsdaNutrientId`. */
    private object NutrientId {
        const val ENERGY_KCAL = 1008
        const val PROTEIN = 1003
        const val CARBOHYDRATE = 1005
        const val FAT = 1004
        const val FIBER = 1079
        const val SODIUM = 1093          // mg
        const val SUGARS = 2000
        const val SATURATED_FAT = 1258
    }

    /** Branded labelNutrients keys (FDC label-block schema, all per-serving). */
    private object LabelKey {
        const val ENERGY_KCAL = "calories"
        const val PROTEIN = "protein"
        const val CARBOHYDRATE = "carbohydrates"
        const val FAT = "fat"
        const val FIBER = "fiber"
        const val SODIUM = "sodium"
        const val SUGARS = "sugars"
        const val SATURATED_FAT = "saturatedFat"
    }

    fun mapBrandedFood(node: JsonObject): UsdaMirrorRow? = mapInternal(node, UsdaDataType.BRANDED)

    fun mapFoundationFood(node: JsonObject): UsdaMirrorRow? = mapInternal(node, UsdaDataType.FOUNDATION)

    private fun mapInternal(node: JsonObject, dataType: String): UsdaMirrorRow? {
        val fdcId = node["fdcId"]?.longOrNumberOrNull() ?: return null
        val description = node["description"]?.stringOrNull()?.takeIf(String::isNotBlank)?.let(::sanitize)
            ?: return null

        val foodNutrients = node["foodNutrients"]?.jsonArrayOrNull() ?: JsonArray(emptyList())
        val perHundred = extractPerHundred(foodNutrients)

        val labelNutrients = if (dataType == UsdaDataType.BRANDED) {
            node["labelNutrients"]?.jsonObjectOrNull()
        } else {
            null
        }
        val servingSize = node["servingSize"]?.numericOrNull()
        val servingSizeUnit = node["servingSizeUnit"]?.stringOrNull()?.let(::sanitize)
        val resolved = applyLabelFallback(perHundred, labelNutrients, servingSize, servingSizeUnit)

        // Long-tail nutrients keyed by FDC nutrientId as string. Skip the
        // eight covered by flat columns to avoid duplication.
        val flatNutrientIds = setOf(
            NutrientId.ENERGY_KCAL, NutrientId.PROTEIN, NutrientId.CARBOHYDRATE, NutrientId.FAT,
            NutrientId.FIBER, NutrientId.SODIUM, NutrientId.SUGARS, NutrientId.SATURATED_FAT,
        )
        val longTail = buildJsonObject {
            foodNutrients.forEach { entry ->
                val obj = entry as? JsonObject ?: return@forEach
                val id = obj["nutrient"]?.jsonObjectOrNull()?.get("id")?.longOrNumberOrNull()?.toInt()
                    ?: obj["nutrientId"]?.longOrNumberOrNull()?.toInt()
                    ?: return@forEach
                if (id in flatNutrientIds) return@forEach
                val value = obj["amount"]?.numericOrNull() ?: obj["value"]?.numericOrNull() ?: return@forEach
                put(id.toString(), JsonPrimitive(value))
            }
        }.takeIf { it.isNotEmpty() }

        return UsdaMirrorRow(
            fdcId = fdcId,
            dataType = dataType,
            description = description,
            brandOwner = node["brandOwner"]?.stringOrNull()?.let(::sanitize),
            brandName = node["brandName"]?.stringOrNull()?.let(::sanitize),
            brandedFoodCategory = node["brandedFoodCategory"]?.stringOrNull()?.let(::sanitize),
            marketCountry = node["marketCountry"]?.stringOrNull()?.let(::sanitize),
            gtinUpc = node["gtinUpc"]?.stringOrNull()?.takeIf(String::isNotBlank)?.let(::sanitize),
            ingredients = node["ingredients"]?.stringOrNull()?.let(::sanitize),
            servingSize = servingSize,
            servingSizeUnit = servingSizeUnit,
            householdServingFullText = node["householdServingFullText"]?.stringOrNull()?.let(::sanitize),
            energyKcal100g = resolved.energyKcal,
            protein100g = resolved.protein,
            carbs100g = resolved.carbs,
            sugars100g = resolved.sugars,
            fat100g = resolved.fat,
            saturatedFat100g = resolved.saturatedFat,
            fiber100g = resolved.fiber,
            sodium100g = resolved.sodiumMg,
            nutriments = longTail,
            publicationDate = node["publicationDate"]?.stringOrNull()?.let(::isoDateOrNull),
            modifiedDate = node["modifiedDate"]?.stringOrNull()?.let(::isoDateOrNull),
            availableDate = node["availableDate"]?.stringOrNull()?.let(::isoDateOrNull),
        )
    }

    private data class PerHundred(
        val energyKcal: Double? = null,
        val protein: Double? = null,
        val carbs: Double? = null,
        val sugars: Double? = null,
        val fat: Double? = null,
        val saturatedFat: Double? = null,
        val fiber: Double? = null,
        val sodiumMg: Double? = null,
    )

    /**
     * Extracts the eight flat-column macros from `foodNutrients[]`. FDC bulk
     * stores per-100g values here for both Branded and Foundation. Each entry
     * is `{"nutrient": {"id": 1008, ...}, "amount": 250}` OR (legacy shape)
     * `{"nutrientId": 1008, "value": 250}`.
     */
    private fun extractPerHundred(foodNutrients: JsonArray): PerHundred {
        var energyKcal: Double? = null
        var protein: Double? = null
        var carbs: Double? = null
        var sugars: Double? = null
        var fat: Double? = null
        var saturatedFat: Double? = null
        var fiber: Double? = null
        var sodiumMg: Double? = null

        foodNutrients.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val id = obj["nutrient"]?.jsonObjectOrNull()?.get("id")?.longOrNumberOrNull()?.toInt()
                ?: obj["nutrientId"]?.longOrNumberOrNull()?.toInt()
                ?: return@forEach
            val value = obj["amount"]?.numericOrNull() ?: obj["value"]?.numericOrNull() ?: return@forEach
            when (id) {
                NutrientId.ENERGY_KCAL -> energyKcal = value
                NutrientId.PROTEIN -> protein = value
                NutrientId.CARBOHYDRATE -> carbs = value
                NutrientId.SUGARS -> sugars = value
                NutrientId.FAT -> fat = value
                NutrientId.SATURATED_FAT -> saturatedFat = value
                NutrientId.FIBER -> fiber = value
                NutrientId.SODIUM -> sodiumMg = value // mg
            }
        }

        return PerHundred(energyKcal, protein, carbs, sugars, fat, saturatedFat, fiber, sodiumMg)
    }

    /**
     * Per user decision: prefer per-100g foodNutrients, fall back to label-
     * nutrients-divided-by-serving when a value is missing. Label values are
     * per-serving in the food's own units; we scale by `100/serving_in_grams`.
     *
     * If we can't compute a base-grams figure (no servingSize or unit not in
     * {g, ml, oz}) the label fallback is skipped — the per-100g column stays
     * null rather than risk an incorrect figure.
     */
    private fun applyLabelFallback(
        per: PerHundred,
        labelNutrients: JsonObject?,
        servingSize: Double?,
        servingSizeUnit: String?,
    ): PerHundred {
        if (labelNutrients == null || servingSize == null || servingSize <= 0.0) return per
        val grams = servingSize.toGrams(servingSizeUnit) ?: return per
        if (grams <= 0.0) return per
        val scale = 100.0 / grams

        fun scaleLabel(key: String, factorMg: Boolean = false): Double? {
            val raw = labelNutrients[key]?.jsonObjectOrNull()?.get("value")?.numericOrNull() ?: return null
            val per100 = raw * scale
            return if (factorMg) per100 else per100
        }

        return PerHundred(
            energyKcal = per.energyKcal ?: scaleLabel(LabelKey.ENERGY_KCAL),
            protein = per.protein ?: scaleLabel(LabelKey.PROTEIN),
            carbs = per.carbs ?: scaleLabel(LabelKey.CARBOHYDRATE),
            sugars = per.sugars ?: scaleLabel(LabelKey.SUGARS),
            fat = per.fat ?: scaleLabel(LabelKey.FAT),
            saturatedFat = per.saturatedFat ?: scaleLabel(LabelKey.SATURATED_FAT),
            fiber = per.fiber ?: scaleLabel(LabelKey.FIBER),
            // Label sodium is reported in mg already (matches USDA convention).
            sodiumMg = per.sodiumMg ?: scaleLabel(LabelKey.SODIUM, factorMg = true),
        )
    }

    private fun Double.toGrams(unit: String?): Double? = when (unit?.lowercase()) {
        "g", "gram", "grams" -> this
        "ml" -> this // 1:1 approximation for water-based foods, same as UsdaMapper.kt
        "oz" -> this * 28.3495
        else -> null
    }

    // -----------------------------------------------------------------------
    // Sanitization (NUL-stripping). Same pattern as OffMirrorMapper.
    // -----------------------------------------------------------------------

    private val NUL_CHAR: Char = Char(0)
    private val NUL_STRING: String = NUL_CHAR.toString()

    private fun sanitize(s: String): String =
        if (s.indexOf(NUL_CHAR) < 0) s else s.replace(NUL_STRING, "")

    /**
     * FDC publication/modified/available dates ship as MM/DD/YYYY strings.
     * Convert to ISO YYYY-MM-DD for Postgres DATE columns; return null on any
     * unrecognized format so the column stays null rather than rejecting the
     * row.
     */
    private fun isoDateOrNull(raw: String): String? {
        val trimmed = raw.trim().takeIf(String::isNotBlank) ?: return null
        // Already ISO?
        if (Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(trimmed)) return trimmed.substring(0, 10)
        val mdy = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").find(trimmed) ?: return null
        val (m, d, y) = mdy.destructured
        return "%s-%02d-%02d".format(y, m.toInt(), d.toInt())
    }

    // -----------------------------------------------------------------------
    // JsonElement coercion helpers — same shape as OffMirrorMapper.
    // -----------------------------------------------------------------------

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

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
}
