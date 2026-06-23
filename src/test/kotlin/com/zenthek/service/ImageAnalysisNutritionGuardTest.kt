package com.zenthek.service

import com.zenthek.model.ImageAnalysisItem
import com.zenthek.model.ImageAnalysisResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageAnalysisNutritionGuardTest {

    @Test
    fun `repairs ml item when total portion nutrition was returned as per ml`() {
        val response = response(
            totalCaloriesExact = 62_970.0,
            totalProteinG = 1_016.0,
            totalCarbsG = 3_818.0,
            totalFatG = 4_514.0,
            items = listOf(
                item(
                    name = "Creamy soup",
                    servingUnit = "ml",
                    servingCount = 250.0,
                    weightG = 250.0,
                    caloriesExact = 250.0,
                    proteinG = 4.0,
                    carbsG = 15.0,
                    fatG = 18.0,
                    fiberG = 0.5,
                    sodiumMg = 500,
                ),
                item("Bread slice", "slice", 1.0, 30.0, 80.0, 3.0, 15.0, 1.0, 2.0, 150),
                item("Fusilli pasta", "portion", 1.0, 250.0, 360.0, 12.0, 48.0, 12.0, 3.0, 400),
                item("Garden salad", "portion", 1.0, 100.0, 30.0, 1.0, 5.0, 1.0, 2.0, 50),
            ),
        )

        val outcome = ImageAnalysisNutritionGuard.sanitize(response)

        assertTrue(outcome.repaired)
        assertTrue(outcome.reasons.any { it.contains("dividing by servingCount") })
        val soup = outcome.response.items.first()
        assertEquals(1.0, soup.caloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(0.016, soup.proteinG, absoluteTolerance = 0.000_001)
        assertEquals(720.0, outcome.response.totalCaloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(20.0, outcome.response.totalProteinG, absoluteTolerance = 0.000_001)
        assertEquals(720, outcome.response.totalCalories)
    }

    @Test
    fun `repairs gram item when per 100g nutrition was returned as per gram`() {
        val response = response(
            totalCaloriesExact = 300.0,
            totalProteinG = 6.0,
            totalCarbsG = 60.0,
            totalFatG = 2.0,
            items = listOf(
                item(
                    name = "Cooked rice",
                    servingUnit = "g",
                    servingCount = 200.0,
                    weightG = 200.0,
                    caloriesExact = 150.0,
                    proteinG = 3.0,
                    carbsG = 30.0,
                    fatG = 1.0,
                    fiberG = 1.0,
                    sodiumMg = 2,
                ),
            ),
        )

        val outcome = ImageAnalysisNutritionGuard.sanitize(response)

        assertTrue(outcome.repaired)
        assertTrue(outcome.reasons.single().contains("dividing by 100"))
        val rice = outcome.response.items.single()
        assertEquals(1.5, rice.caloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(0.03, rice.proteinG, absoluteTolerance = 0.000_001)
        assertEquals(300.0, outcome.response.totalCaloriesExact, absoluteTolerance = 0.000_001)
    }

    @Test
    fun `keeps valid per gram nutrition unchanged`() {
        val response = response(
            totalCaloriesExact = 62.0,
            totalProteinG = 3.8,
            totalCarbsG = 14.0,
            totalFatG = 0.2,
            items = listOf(
                item("Wax bean", "g", 200.0, 200.0, 0.31, 0.019, 0.07, 0.001, 0.027, 0),
            ),
        )

        val outcome = ImageAnalysisNutritionGuard.sanitize(response)

        assertFalse(outcome.repaired)
        assertEquals(0.31, outcome.response.items.single().caloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(62.0, outcome.response.totalCaloriesExact, absoluteTolerance = 0.000_001)
    }

    @Test
    fun `keeps count based serving nutrition unchanged`() {
        val response = response(
            totalCaloriesExact = 240.0,
            totalProteinG = 9.0,
            totalCarbsG = 45.0,
            totalFatG = 3.0,
            items = listOf(
                item("Bread slice", "slice", 3.0, 90.0, 80.0, 3.0, 15.0, 1.0, 2.0, 150),
            ),
        )

        val outcome = ImageAnalysisNutritionGuard.sanitize(response)

        assertFalse(outcome.repaired)
        assertEquals(80.0, outcome.response.items.single().caloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(240.0, outcome.response.totalCaloriesExact, absoluteTolerance = 0.000_001)
    }

    @Test
    fun `recomputes and reports mismatched response totals`() {
        val response = response(
            totalCaloriesExact = 2_000.0,
            totalProteinG = 200.0,
            totalCarbsG = 200.0,
            totalFatG = 200.0,
            items = listOf(
                item("Bread slice", "slice", 1.0, 30.0, 80.0, 3.0, 15.0, 1.0, 2.0, 150),
            ),
        )

        val outcome = ImageAnalysisNutritionGuard.sanitize(response)

        assertTrue(outcome.repaired)
        assertTrue(outcome.reasons.single().contains("recomputed response totals"))
        assertEquals(80.0, outcome.response.totalCaloriesExact, absoluteTolerance = 0.000_001)
        assertEquals(3.0, outcome.response.totalProteinG, absoluteTolerance = 0.000_001)
    }

    @Test
    fun `rejects impossible unrecoverable response`() {
        val response = response(
            totalCaloriesExact = 20_000.0,
            totalProteinG = 20.0,
            totalCarbsG = 20.0,
            totalFatG = 20.0,
            items = listOf(
                item("Mystery portion", "portion", 1.0, 200.0, 20_000.0, 20.0, 20.0, 20.0, 0.0, 0),
            ),
        )

        assertFailsWith<UpstreamFailureException> {
            ImageAnalysisNutritionGuard.sanitize(response)
        }
    }

    private fun response(
        totalCaloriesExact: Double,
        totalProteinG: Double,
        totalCarbsG: Double,
        totalFatG: Double,
        items: List<ImageAnalysisItem>,
    ) = ImageAnalysisResponse(
        errorCode = null,
        title = "Meal",
        subtitle = "Meal scan",
        isLikelyRestaurant = false,
        items = items,
        totalCalories = totalCaloriesExact.toInt(),
        totalCaloriesExact = totalCaloriesExact,
        totalProteinG = totalProteinG,
        totalCarbsG = totalCarbsG,
        totalFatG = totalFatG,
        totalFiberG = items.sumOf { it.fiberG ?: 0.0 },
        totalSodiumMg = items.sumOf { it.sodiumMg ?: 0 },
        totalSugarG = null,
        notes = null,
    )

    private fun item(
        name: String,
        servingUnit: String,
        servingCount: Double,
        weightG: Double,
        caloriesExact: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        fiberG: Double?,
        sodiumMg: Int?,
    ) = ImageAnalysisItem(
        name = name,
        portionDescription = "$servingCount $servingUnit",
        servingUnit = servingUnit,
        servingCount = servingCount,
        weightG = weightG,
        confidence = "high",
        calories = caloriesExact.toInt(),
        caloriesExact = caloriesExact,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        fiberG = fiberG,
        sodiumMg = sodiumMg,
        sugarG = null,
    )
}
