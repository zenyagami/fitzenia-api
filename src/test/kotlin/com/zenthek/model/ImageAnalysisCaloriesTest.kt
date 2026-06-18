package com.zenthek.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageAnalysisCaloriesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `exact decimal calories preserve legacy integer fields`() {
        val response = json.decodeFromString<ImageAnalysisResponse>(
            responseJson(calories = 0, caloriesExact = 0.31, totalCalories = 62, totalCaloriesExact = 62.0)
        )

        assertEquals(0, response.items.single().calories)
        assertEquals(0.31, response.items.single().caloriesExact)
        assertEquals(62, response.totalCalories)
        assertEquals(62.0, response.totalCaloriesExact)
    }

    @Test
    fun `Atwater fallback recovers zero per-gram calories and total`() {
        val response = json.decodeFromString<ImageAnalysisResponse>(
            responseJson(calories = 0, caloriesExact = 0.0, totalCalories = 0, totalCaloriesExact = 0.0)
        ).applyAtwaterFallback()

        assertEquals(0, response.items.single().calories)
        assertEquals(0.365, response.items.single().caloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(73, response.totalCalories)
        assertEquals(73.0, response.totalCaloriesExact, absoluteTolerance = 0.000_001)
    }

    @Test
    fun `Atwater fallback preserves positive analyzer calories`() {
        val response = json.decodeFromString<ImageAnalysisResponse>(
            responseJson(calories = 0, caloriesExact = 0.31, totalCalories = 62, totalCaloriesExact = 62.0)
        ).applyAtwaterFallback()

        assertEquals(0, response.items.single().calories)
        assertEquals(0.31, response.items.single().caloriesExact)
        assertEquals(62, response.totalCalories)
        assertEquals(62.0, response.totalCaloriesExact)
    }

    private fun responseJson(
        calories: Int,
        caloriesExact: Double,
        totalCalories: Int,
        totalCaloriesExact: Double
    ) = """
        {
          "errorCode": null,
          "title": "Wax Beans",
          "subtitle": "A serving of yellow wax beans.",
          "isLikelyRestaurant": false,
          "items": [{
            "name": "Wax bean",
            "portionDescription": "200 g serving",
            "servingUnit": "g",
            "servingCount": 200.0,
            "weightG": 200.0,
            "confidence": "high",
            "calories": $calories,
            "caloriesExact": $caloriesExact,
            "proteinG": 0.019,
            "carbsG": 0.07,
            "fatG": 0.001,
            "fiberG": 0.027,
            "sodiumMg": 0
          }],
          "totalCalories": $totalCalories,
          "totalCaloriesExact": $totalCaloriesExact,
          "totalProteinG": 3.8,
          "totalCarbsG": 14.0,
          "totalFatG": 0.2,
          "totalFiberG": 5.4,
          "totalSodiumMg": 0
        }
    """.trimIndent()
}
