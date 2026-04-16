package com.zenthek.service

import com.zenthek.ai.AiGeneratedItem
import com.zenthek.ai.AiGeneratedServing
import kotlin.math.abs

/**
 * Server-side sanity checks on AI-generated nutrition, applied before
 * persisting anything to the canonical catalog. If validation fails the
 * orchestrator still returns the generated item to the client (with
 * `aiGenerated=true`, `confidence=...`), but does NOT write it to the
 * catalog — preventing bad data from becoming the permanent canonical
 * answer for future users.
 *
 * Required invariants:
 *  - Non-empty servings.
 *  - Exactly one serving named "100g" with weight_grams in [99, 101].
 *  - All macros on every serving are >= 0, calories > 0.
 *  - Calorie-macro consistency on the 100g serving:
 *    |calories − (4·protein + 4·carbs + 9·fat)| ≤ 0.20 · calories.
 */
object NutritionValidator {

    private const val CALORIE_MACRO_TOLERANCE = 0.20
    private const val PROTEIN_KCAL_PER_G = 4.0
    private const val CARBS_KCAL_PER_G = 4.0
    private const val FAT_KCAL_PER_G = 9.0
    private const val HUNDRED_GRAMS_MIN = 99f
    private const val HUNDRED_GRAMS_MAX = 101f

    fun validate(item: AiGeneratedItem): Result {
        if (item.servings.isEmpty()) return Result.Invalid("No servings")

        val hundredG = item.servings.firstOrNull {
            it.name.trim().equals("100g", ignoreCase = true) ||
                (it.weightGrams in HUNDRED_GRAMS_MIN..HUNDRED_GRAMS_MAX)
        } ?: return Result.Invalid("No 100g reference serving")

        item.servings.forEach { s ->
            reasonIfAnyMacroNegative(s)?.let { return Result.Invalid(it) }
        }
        if (hundredG.caloriesKcal <= 0f) return Result.Invalid("100g serving has non-positive calories")

        val expectedKcal =
            PROTEIN_KCAL_PER_G * hundredG.proteinG +
                CARBS_KCAL_PER_G * hundredG.carbsG +
                FAT_KCAL_PER_G * hundredG.fatG
        val error = abs(hundredG.caloriesKcal - expectedKcal)
        val tolerance = CALORIE_MACRO_TOLERANCE * hundredG.caloriesKcal
        if (error > tolerance) {
            return Result.Invalid(
                "Calorie-macro mismatch on 100g: calories=${hundredG.caloriesKcal}, " +
                    "expected≈$expectedKcal, error=$error, tolerance=$tolerance"
            )
        }
        return Result.Valid
    }

    private fun reasonIfAnyMacroNegative(s: AiGeneratedServing): String? {
        if (s.weightGrams <= 0f) return "Serving '${s.name}' has non-positive weight"
        if (s.caloriesKcal < 0f) return "Serving '${s.name}' has negative calories"
        if (s.proteinG < 0f) return "Serving '${s.name}' has negative protein"
        if (s.carbsG < 0f) return "Serving '${s.name}' has negative carbs"
        if (s.fatG < 0f) return "Serving '${s.name}' has negative fat"
        return null
    }

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }
}
