package com.zenthek.mapper

import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.InternalFoodItem
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ResultKind
import com.zenthek.model.ServingSize
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsNutriments
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsProduct
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchProduct
import com.zenthek.upstream.supabase.OffMirrorProduct

object OpenFoodFactsMapper {

    /**
     * Maps a row from the local OFF mirror (`public.off_food`) into the same
     * `FoodItem` shape we'd build from a live OFF API hit. Mirror rows always
     * carry `FoodSource.OPEN_FOOD_FACTS`; the bestMatch caller decides
     * `ResultKind` (BRANDED if `primaryBrand` is non-null else GENERIC).
     *
     * Returns null when the row lacks the minimum fields needed to be useful
     * (no name, or no resolvable nutrition macros). Same gate the live mappers
     * apply so a corrupt mirror row never leaks an empty card to the client.
     */
    fun mapMirrorRow(row: OffMirrorProduct): FoodItem? {
        val name = row.productName?.trim()
        if (name.isNullOrBlank()) return null

        val nutritionPer100g = NutritionInfo(
            caloriesKcal = row.energyKcal100g
                ?: estimateCaloriesKcal(row.protein100g, row.carbs100g, row.fat100g),
            proteinG = row.protein100g ?: 0f,
            carbsG = row.carbs100g ?: 0f,
            fatG = row.fat100g ?: 0f,
            fiberG = row.fiber100g,
            sodiumMg = row.sodium100g?.let { it * 1000f }, // mirror stores grams; client expects mg
            sugarG = row.sugars100g,
            saturatedFatG = row.saturatedFat100g,
            cholesterolMg = null,
            potassiumMg = null,
            calciumMg = null,
            ironMg = null,
        )
        if (nutritionPer100g.caloriesKcal == 0f && nutritionPer100g.proteinG == 0f && nutritionPer100g.fatG == 0f) {
            return null
        }

        val brand = row.primaryBrand?.trim()?.ifBlank { null }
            ?: row.brands?.firstOrNull()?.trim()?.ifBlank { null }
        val servings = buildServings(row.servingSize, row.servingQuantity, nutritionPer100g)

        return FoodItem(
            id = "OFF_${row.code}",
            name = name,
            brand = brand,
            barcode = row.code,
            source = FoodSource.OPEN_FOOD_FACTS,
            imageUrl = row.imageUrl,
            servings = servings,
        )
    }

    fun map(product: OpenFoodFactsProduct): FoodItem? {
        val name = product.productName?.trim()
        if (name.isNullOrBlank()) return null

        val nutritionPer100g = extractNutrition(product.nutriments)

        if (nutritionPer100g.caloriesKcal == 0f && nutritionPer100g.proteinG == 0f && nutritionPer100g.fatG == 0f) {
            return null
        }

        val code = product.code ?: return null
        val brand = product.brands?.split(",")?.firstOrNull()?.trim()?.ifBlank { null }
        val servings = buildServings(product.servingSize, product.servingQuantity, nutritionPer100g)

        return FoodItem(
            id = "OFF_$code",
            name = name,
            brand = brand,
            barcode = code,
            source = FoodSource.OPEN_FOOD_FACTS,
            imageUrl = product.imageUrl,
            servings = servings
        )
    }

    fun mapV3Search(product: OpenFoodFactsV3SearchProduct): FoodItem? {
        val name = product.productName?.trim()
        if (name.isNullOrBlank()) return null

        val nutritionPer100g = extractNutrition(product.nutriments)

        if (nutritionPer100g.caloriesKcal == 0f && nutritionPer100g.proteinG == 0f && nutritionPer100g.fatG == 0f) {
            return null
        }

        val code = product.code ?: return null
        val brand = product.brands?.firstOrNull()?.trim()?.ifBlank { null }
        val servings = buildServings(product.servingSize, product.servingQuantity, nutritionPer100g)

        return FoodItem(
            id = "OFF_$code",
            name = name,
            brand = brand,
            barcode = code,
            source = FoodSource.OPEN_FOOD_FACTS,
            imageUrl = product.imageUrl,
            servings = servings
        )
    }

    /**
     * Same as [mapV3Search] but wraps the result with [ResultKind]. OFF is a
     * branded-products database, so every item is classified BRANDED here.
     * Any promotion to GENERIC (exact query match with no brand) happens in
     * the orchestrator, which has access to the query. Do not add query-aware
     * logic in this mapper.
     */
    internal fun mapV3SearchWithKind(product: OpenFoodFactsV3SearchProduct): InternalFoodItem? {
        val foodItem = mapV3Search(product) ?: return null
        return InternalFoodItem(foodItem, ResultKind.BRANDED)
    }

    // Atwater factors: 4 kcal/g protein, 4 kcal/g carbs, 9 kcal/g fat.
    // Used when the source omits an explicit energy value.
    private fun estimateCaloriesKcal(protein: Float?, carbs: Float?, fat: Float?): Float =
        4f * (protein ?: 0f) + 4f * (carbs ?: 0f) + 9f * (fat ?: 0f)

    private fun extractNutrition(nutriments: OpenFoodFactsNutriments?): NutritionInfo {
        // OFF reports minerals per 100g as grams; convert to mg.
        return NutritionInfo(
            caloriesKcal = nutriments?.energyKcal100g ?: 0f,
            proteinG = nutriments?.proteins100g ?: 0f,
            carbsG = nutriments?.carbohydrates100g ?: 0f,
            fatG = nutriments?.fat100g ?: 0f,
            fiberG = nutriments?.fiber100g,
            sodiumMg = nutriments?.sodium100g?.let { it * 1000f },
            sugarG = nutriments?.sugars100g,
            saturatedFatG = nutriments?.saturatedFat100g,
            cholesterolMg = nutriments?.cholesterol100g?.let { it * 1000f },
            potassiumMg = nutriments?.potassium100g?.let { it * 1000f },
            calciumMg = nutriments?.calcium100g?.let { it * 1000f },
            ironMg = nutriments?.iron100g?.let { it * 1000f }
        )
    }

    private fun buildServings(servingSize: String?, servingQuantity: Float?, nutritionPer100g: NutritionInfo): List<ServingSize> {
        val servings = mutableListOf<ServingSize>()

        // Labeled household serving (when present) goes first — it's the user-facing default.
        // "100g" is a reference unit and goes last. Falls back to "100g" only when no labeled
        // serving is available.
        if (servingQuantity != null && servingQuantity > 0f && servingQuantity != 100f) {
            servings.add(
                ServingSize(
                    name = servingSize ?: "${servingQuantity}g",
                    weightGrams = servingQuantity,
                    nutrition = scaleNutrition(nutritionPer100g, servingQuantity / 100f)
                )
            )
        }

        servings.add(ServingSize(name = "100g", weightGrams = 100f, nutrition = nutritionPer100g))

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
}
