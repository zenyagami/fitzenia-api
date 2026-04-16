package com.zenthek.service

import com.zenthek.ai.AiGeneratedItem
import com.zenthek.ai.AiGeneratedServing
import kotlin.test.Test
import kotlin.test.assertTrue

class NutritionValidatorTest {

    @Test
    fun `accepts a well-formed item with consistent calorie math`() {
        val item = item100g(calories = 200f, protein = 10f, carbs = 20f, fat = 8f)
        // 4*10 + 4*20 + 9*8 = 192. |200 - 192| = 8, tolerance = 40. OK.
        assertTrue(NutritionValidator.validate(item) is NutritionValidator.Result.Valid)
    }

    @Test
    fun `rejects when calorie-macro error exceeds 20 percent`() {
        val item = item100g(calories = 500f, protein = 10f, carbs = 20f, fat = 8f)
        // expected = 192, error = 308, tolerance = 100 → invalid
        val result = NutritionValidator.validate(item)
        assertTrue(result is NutritionValidator.Result.Invalid)
    }

    @Test
    fun `rejects when 100g serving is missing`() {
        val item = AiGeneratedItem(
            name = "Cheesecake",
            confidence = 0.9f,
            servings = listOf(
                AiGeneratedServing(
                    name = "1 slice", weightGrams = 80f,
                    caloriesKcal = 320f, proteinG = 5f, carbsG = 30f, fatG = 20f
                )
            )
        )
        val result = NutritionValidator.validate(item)
        assertTrue(result is NutritionValidator.Result.Invalid)
    }

    @Test
    fun `rejects negative macros`() {
        val item = item100g(calories = 200f, protein = -1f, carbs = 20f, fat = 8f)
        val result = NutritionValidator.validate(item)
        assertTrue(result is NutritionValidator.Result.Invalid)
    }

    @Test
    fun `rejects zero or negative calories on 100g`() {
        val item = item100g(calories = 0f, protein = 10f, carbs = 20f, fat = 8f)
        val result = NutritionValidator.validate(item)
        assertTrue(result is NutritionValidator.Result.Invalid)
    }

    @Test
    fun `rejects empty servings`() {
        val item = AiGeneratedItem("x", 0.9f, servings = emptyList())
        val result = NutritionValidator.validate(item)
        assertTrue(result is NutritionValidator.Result.Invalid)
    }

    @Test
    fun `accepts 100g serving identified by weight even if name differs`() {
        val item = AiGeneratedItem(
            name = "Banana",
            confidence = 0.85f,
            servings = listOf(
                AiGeneratedServing(
                    name = "per 100 g", weightGrams = 100f,
                    caloriesKcal = 89f, proteinG = 1.1f, carbsG = 22.8f, fatG = 0.3f
                )
            )
        )
        // 4*1.1 + 4*22.8 + 9*0.3 = 98.3. |89 - 98.3| = 9.3, tolerance = 17.8. OK.
        assertTrue(NutritionValidator.validate(item) is NutritionValidator.Result.Valid)
    }

    private fun item100g(calories: Float, protein: Float, carbs: Float, fat: Float): AiGeneratedItem =
        AiGeneratedItem(
            name = "Test",
            confidence = 0.9f,
            servings = listOf(
                AiGeneratedServing(
                    name = "100g", weightGrams = 100f,
                    caloriesKcal = calories, proteinG = protein, carbsG = carbs, fatG = fat
                )
            )
        )
}
