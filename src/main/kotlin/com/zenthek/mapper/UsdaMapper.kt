package com.zenthek.mapper

import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.InternalFoodItem
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ResultKind
import com.zenthek.model.ServingSize
import com.zenthek.upstream.usda.UsdaMirrorProduct
import com.zenthek.upstream.usda.dto.UsdaSearchFoodDto
import com.zenthek.upstream.usda.dto.UsdaSearchNutrientDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

object UsdaMapper {

    private const val DATA_TYPE_FOUNDATION = "foundation_food"
    private const val DATA_TYPE_BRANDED = "branded_food"

    /**
     * Maps a row from the local USDA mirror (`public.usda_food`) into the same
     * `FoodItem` shape `mapSearchItem` produces from the live API. Same id
     * format (`USDA_${fdcId}`) so dedup-by-id with live USDA path is correct.
     *
     * Mirror rows store per-100g macros directly in flat columns (sodium in mg,
     * everything else in grams — see `004_usda_mirror.sql`). Servings are
     * re-computed via [buildServings] so labeled-first ordering matches live.
     *
     * Returns null when the row lacks the minimum fields needed to be useful
     * (no description or no resolvable nutrition macros).
     */
    fun mapMirrorRow(row: UsdaMirrorProduct): FoodItem? {
        val name = row.description.trim().toTitleCase()
        if (name.isBlank()) return null

        val nutritionPer100g = NutritionInfo(
            caloriesKcal = row.energyKcal100g
                ?: estimateCaloriesKcal(row.protein100g, row.carbs100g, row.fat100g),
            proteinG = row.protein100g ?: 0f,
            carbsG = row.carbs100g ?: 0f,
            fatG = row.fat100g ?: 0f,
            fiberG = row.fiber100g,
            // Mirror stores sodium in mg already (USDA's native unit) — pass through.
            sodiumMg = row.sodium100g,
            sugarG = row.sugars100g,
            saturatedFatG = row.saturatedFat100g,
            cholesterolMg = row.nutriments?.numericField(UsdaNutrientId.CHOLESTEROL),
            potassiumMg = row.nutriments?.numericField(UsdaNutrientId.POTASSIUM),
            calciumMg = row.nutriments?.numericField(UsdaNutrientId.CALCIUM),
            ironMg = row.nutriments?.numericField(UsdaNutrientId.IRON),
        )
        if (nutritionPer100g.caloriesKcal == 0f && nutritionPer100g.proteinG == 0f && nutritionPer100g.fatG == 0f) {
            return null
        }

        val brand = row.brandName?.trim()?.ifBlank { null }
            ?: row.brandOwner?.trim()?.ifBlank { null }

        // Mirror macros are per-100g. Reconstruct the same servings shape the
        // live mapper builds (labeled first, 100g second). Branded rows have
        // a serving; Foundation rows fall through to the 100g-only path.
        val servings = buildServingsFromPer100g(row.servingSize, row.servingSizeUnit, nutritionPer100g)

        return FoodItem(
            id = "USDA_${row.fdcId}",
            name = name,
            brand = brand,
            barcode = row.gtinUpc,
            source = FoodSource.USDA,
            imageUrl = null,
            servings = servings,
        )
    }

    /**
     * Same as [mapMirrorRow] but wraps the result with [ResultKind] derived
     * from `data_type` — Foundation → GENERIC, Branded → BRANDED. Matches
     * `mapSearchItemWithKind`'s classification so SmartSearch's
     * GENERIC/BRANDED bucketing applies uniformly across mirror + live.
     */
    internal fun mapMirrorRowWithKind(row: UsdaMirrorProduct): InternalFoodItem? {
        val item = mapMirrorRow(row) ?: return null
        val kind = when (row.dataType) {
            DATA_TYPE_FOUNDATION -> ResultKind.GENERIC
            else -> ResultKind.BRANDED
        }
        return InternalFoodItem(item, kind)
    }

    // Atwater factors: 4 kcal/g protein, 4 kcal/g carbs, 9 kcal/g fat.
    // Used when the source omits an explicit energy value (e.g. FDC foundation foods).
    private fun estimateCaloriesKcal(protein: Float?, carbs: Float?, fat: Float?): Float =
        4f * (protein ?: 0f) + 4f * (carbs ?: 0f) + 9f * (fat ?: 0f)

    private fun JsonObject.numericField(nutrientId: Int): Float? {
        val key = nutrientId.toString()
        val prim = this[key] as? JsonPrimitive ?: return null
        prim.doubleOrNull?.let { return it.toFloat() }
        return prim.contentOrNull?.toFloatOrNull()
    }


    object UsdaNutrientId {
        const val ENERGY_KCAL = 1008
        const val PROTEIN = 1003
        const val CARBOHYDRATE = 1005  // "Carbohydrate, by difference"
        const val FAT = 1004            // "Total lipid (fat)"
        const val FIBER = 1079
        const val SODIUM = 1093         // Unit is MG
        const val SUGARS = 2000
        const val SATURATED_FAT = 1258
        const val CHOLESTEROL = 1253    // Unit is MG
        const val POTASSIUM = 1092      // Unit is MG
        const val CALCIUM = 1087        // Unit is MG
        const val IRON = 1089           // Unit is MG
    }

    fun mapSearchItem(item: UsdaSearchFoodDto): FoodItem? {
        val name = item.description.toTitleCase().trim()
        if (name.isBlank()) return null

        val brand = item.brandName ?: item.brandOwner
        val nutritionFromNutrients = extractNutritionFromSearch(item.foodNutrients)

        val servings = buildServings(item.servingSize, item.servingSizeUnit, nutritionFromNutrients)

        return FoodItem(
            id = "USDA_${item.fdcId}",
            name = name,
            brand = brand,
            barcode = item.gtinUpc,
            source = FoodSource.USDA,
            imageUrl = null,
            servings = servings
        )
    }

    /**
     * Same as [mapSearchItem] but also classifies the result as GENERIC vs BRANDED
     * based on USDA's `dataType`. Used by the Smart Search path; the old barcode
     * flow still calls [mapSearchItem].
     */
    internal fun mapSearchItemWithKind(item: UsdaSearchFoodDto): InternalFoodItem? {
        val foodItem = mapSearchItem(item) ?: return null
        val kind = when (item.dataType) {
            "Foundation", "SR Legacy", "Survey (FNDDS)" -> ResultKind.GENERIC
            else -> ResultKind.BRANDED  // "Branded" and any unknown dataType default to branded
        }
        return InternalFoodItem(foodItem, kind)
    }

    private fun extractNutritionFromSearch(nutrients: List<UsdaSearchNutrientDto>): NutritionInfo {
        return NutritionInfo(
            caloriesKcal = nutrients.findValue(UsdaNutrientId.ENERGY_KCAL) ?: 0f,
            proteinG = nutrients.findValue(UsdaNutrientId.PROTEIN) ?: 0f,
            carbsG = nutrients.findValue(UsdaNutrientId.CARBOHYDRATE) ?: 0f,
            fatG = nutrients.findValue(UsdaNutrientId.FAT) ?: 0f,
            fiberG = nutrients.findValue(UsdaNutrientId.FIBER),
            sodiumMg = nutrients.findValue(UsdaNutrientId.SODIUM), // already mg
            sugarG = nutrients.findValue(UsdaNutrientId.SUGARS),
            saturatedFatG = nutrients.findValue(UsdaNutrientId.SATURATED_FAT),
            cholesterolMg = nutrients.findValue(UsdaNutrientId.CHOLESTEROL), // already mg
            potassiumMg = nutrients.findValue(UsdaNutrientId.POTASSIUM),     // already mg
            calciumMg = nutrients.findValue(UsdaNutrientId.CALCIUM),         // already mg
            ironMg = nutrients.findValue(UsdaNutrientId.IRON)                // already mg
        )
    }

    private fun List<UsdaSearchNutrientDto>.findValue(id: Int): Float? =
        firstOrNull { it.nutrientId == id }?.value

    /**
     * Mirror analog of [buildServings]: input nutrition is per-100g (not
     * per-serving). Emits the labeled serving first (scaled DOWN from 100g)
     * and 100g second, matching the live mapper's order so AI ranking and
     * UI default-portion logic see the same shape regardless of source.
     */
    private fun buildServingsFromPer100g(
        servingSize: Float?,
        servingSizeUnit: String?,
        nutritionPer100g: NutritionInfo,
    ): List<ServingSize> {
        val baseServingWeight = when (servingSizeUnit?.lowercase()) {
            "g", "ml" -> servingSize
            "oz" -> (servingSize ?: 0f) * 28.3495f
            else -> servingSize
        }

        if (servingSize != null && baseServingWeight != null && baseServingWeight > 0f) {
            val labeledNutrition = scaleNutrition(nutritionPer100g, baseServingWeight / 100f)
            val labeled = ServingSize(
                name = "1 serving (${servingSize.toInt()}${servingSizeUnit ?: "g"})",
                weightGrams = baseServingWeight,
                nutrition = labeledNutrition,
            )
            return if (baseServingWeight == 100f) {
                listOf(labeled)
            } else {
                listOf(
                    labeled,
                    ServingSize(name = "100g", weightGrams = 100f, nutrition = nutritionPer100g),
                )
            }
        }
        // Foundation rows + any branded row missing serving context fall back
        // to a single 100g entry — same as the live mapper.
        return listOf(ServingSize(name = "100g", weightGrams = 100f, nutrition = nutritionPer100g))
    }

    private fun buildServings(
        servingSize: Float?,
        servingSizeUnit: String?,
        nutrition: NutritionInfo
    ): List<ServingSize> {
        val baseServingWeight = when (servingSizeUnit?.lowercase()) {
            "g" -> servingSize
            "ml" -> servingSize
            "oz" -> (servingSize ?: 0f) * 28.3495f
            else -> servingSize
        } ?: 100f // Fallback to 100g if size missing

        val servings = mutableListOf<ServingSize>()

        if (servingSize != null) {
            // USDA values align to their specified serving amount per search item.
            servings.add(
                ServingSize(
                    name = "1 serving (${servingSize.toInt()}${servingSizeUnit ?: "g"})",
                    weightGrams = baseServingWeight,
                    nutrition = nutrition
                )
            )
            // Add normalized 100g chunk
            if (baseServingWeight > 0f && baseServingWeight != 100f) {
                 servings.add(
                     ServingSize(
                         name = "100g",
                         weightGrams = 100f,
                         nutrition = scaleNutrition(nutrition, 100f / baseServingWeight)
                     )
                 )
            }
        } else {
            // Default 100g format (Foundations missing quantity context)
             servings.add(
                 ServingSize(
                     name = "100g",
                     weightGrams = 100f,
                     nutrition = nutrition
                 )
             )
        }

        return servings
    }

    private fun scaleNutrition(nutrition: NutritionInfo, scaleFactor: Float): NutritionInfo {
        return NutritionInfo(
            caloriesKcal = nutrition.caloriesKcal * scaleFactor,
            proteinG = nutrition.proteinG * scaleFactor,
            carbsG = nutrition.carbsG * scaleFactor,
            fatG = nutrition.fatG * scaleFactor,
            fiberG = nutrition.fiberG?.let { it * scaleFactor },
            sodiumMg = nutrition.sodiumMg?.let { it * scaleFactor },
            sugarG = nutrition.sugarG?.let { it * scaleFactor },
            saturatedFatG = nutrition.saturatedFatG?.let { it * scaleFactor },
            cholesterolMg = nutrition.cholesterolMg?.let { it * scaleFactor },
            potassiumMg = nutrition.potassiumMg?.let { it * scaleFactor },
            calciumMg = nutrition.calciumMg?.let { it * scaleFactor },
            ironMg = nutrition.ironMg?.let { it * scaleFactor }
        )
    }

    private fun String.toTitleCase(): String = split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }
}
