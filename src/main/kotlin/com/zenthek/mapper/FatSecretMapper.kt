package com.zenthek.mapper

import com.zenthek.model.FoodItem
import com.zenthek.model.FoodSource
import com.zenthek.model.NutritionInfo
import com.zenthek.model.ServingSize
import com.zenthek.upstream.fatsecret.dto.FatSecretFoodDetailDto
import com.zenthek.upstream.fatsecret.dto.FatSecretServingDto

object FatSecretMapper {

    fun mapDetail(detail: FatSecretFoodDetailDto, barcode: String? = null): FoodItem? {
        val name = detail.foodName.trim()
        if (name.isBlank()) return null

        val servings = detail.servings?.serving?.mapNotNull { mapServing(it) } ?: emptyList()
        if (servings.isEmpty()) return null

        return FoodItem(
            id = "FS_${detail.foodId}",
            name = name,
            brand = detail.brandName,
            barcode = barcode,
            source = FoodSource.FATSECRET,
            imageUrl = null,
            servings = servings
        )
    }

    private fun mapServing(dto: FatSecretServingDto): ServingSize? {
        val weight = dto.metricServingAmount?.toFloatOrNull() ?: return null

        return ServingSize(
            name = dto.servingDescription ?: "${weight}${dto.metricServingUnit ?: "g"}",
            weightGrams = weight,
            nutrition = NutritionInfo(
                caloriesKcal = dto.calories?.toFloatOrNull() ?: 0f,
                proteinG = dto.protein?.toFloatOrNull() ?: 0f,
                carbsG = dto.carbohydrate?.toFloatOrNull() ?: 0f,
                fatG = dto.fat?.toFloatOrNull() ?: 0f,
                fiberG = dto.fiber?.toFloatOrNull(),
                sodiumMg = dto.sodium?.toFloatOrNull(), // FatSecret sodium is already in mg
                sugarG = dto.sugar?.toFloatOrNull(),
                saturatedFatG = dto.saturatedFat?.toFloatOrNull()
            )
        )
    }
}
