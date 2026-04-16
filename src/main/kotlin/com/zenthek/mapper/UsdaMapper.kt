package com.zenthek.mapper

import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.InternalFoodItem
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ResultKind
import com.zenthek.model.ServingSize
import com.zenthek.upstream.usda.dto.UsdaSearchFoodDto
import com.zenthek.upstream.usda.dto.UsdaSearchNutrientDto

object UsdaMapper {

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
