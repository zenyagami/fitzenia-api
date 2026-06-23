package com.zenthek.service

import com.zenthek.model.ImageAnalysisItem
import com.zenthek.model.ImageAnalysisResponse
import kotlin.math.abs
import kotlin.math.roundToInt

object ImageAnalysisNutritionGuard {
    private const val PROTEIN_KCAL_PER_G = 4.0
    private const val CARBS_KCAL_PER_G = 4.0
    private const val FAT_KCAL_PER_G = 9.0
    private const val MAX_MEAL_KCAL = 8_000.0
    private const val MAX_ITEM_KCAL = 6_000.0
    private const val MAX_GRAM_OR_ML_KCAL_PER_UNIT = 9.5
    private const val MAX_GRAM_OR_ML_MACROS_PER_UNIT = 1.35
    private const val MAX_GRAM_OR_ML_SODIUM_MG_PER_UNIT = 100.0
    private const val TOTAL_PORTION_ATWATER_TOLERANCE = 0.35

    data class Outcome(
        val response: ImageAnalysisResponse,
        val repaired: Boolean,
        val reasons: List<String>,
        val before: Totals,
        val after: Totals,
    )

    data class Totals(
        val calories: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
    ) {
        companion object {
            fun from(response: ImageAnalysisResponse) = Totals(
                calories = response.totalCaloriesExact,
                protein = response.totalProteinG,
                carbs = response.totalCarbsG,
                fat = response.totalFatG,
            )
        }
    }

    fun sanitize(response: ImageAnalysisResponse): Outcome {
        val before = Totals.from(response)
        if (response.errorCode != null) {
            return Outcome(response, repaired = false, reasons = emptyList(), before = before, after = before)
        }

        val reasons = mutableListOf<String>()
        val repairedItems = response.items.map { item ->
            validateItemShape(item)
            repairGramOrMlItem(item, response)?.also { reasons += "${item.name}: ${it.reason}" }?.item ?: item
        }
        val recomputed = response.copy(items = repairedItems).recomputeTotals()
        validateResponse(recomputed)
        val after = Totals.from(recomputed)
        if (totalsDifferSignificantly(before, after)) {
            reasons += "recomputed response totals from item nutrition"
        }
        return Outcome(
            response = recomputed,
            repaired = reasons.isNotEmpty(),
            reasons = reasons,
            before = before,
            after = after,
        )
    }

    private fun validateItemShape(item: ImageAnalysisItem) {
        requireFinite(item.name, "servingCount", item.servingCount)
        requireFinite(item.name, "weightG", item.weightG)
        requireFinite(item.name, "caloriesExact", item.caloriesExact)
        requireFinite(item.name, "proteinG", item.proteinG)
        requireFinite(item.name, "carbsG", item.carbsG)
        requireFinite(item.name, "fatG", item.fatG)
        item.fiberG?.let { requireFinite(item.name, "fiberG", it) }
        item.sodiumMg?.let { requireFinite(item.name, "sodiumMg", it.toDouble()) }
        item.sugarG?.let { requireFinite(item.name, "sugarG", it) }

        if (item.servingCount <= 0.0) throw UpstreamFailureException("Invalid image nutrition: ${item.name} has non-positive servingCount")
        if (item.weightG < 0.0) throw UpstreamFailureException("Invalid image nutrition: ${item.name} has negative weightG")
        if (item.caloriesExact < 0.0) throw UpstreamFailureException("Invalid image nutrition: ${item.name} has negative calories")
        if (item.proteinG < 0.0 || item.carbsG < 0.0 || item.fatG < 0.0) {
            throw UpstreamFailureException("Invalid image nutrition: ${item.name} has negative macros")
        }
        if ((item.fiberG ?: 0.0) < 0.0 || (item.sodiumMg ?: 0) < 0 || (item.sugarG ?: 0.0) < 0.0) {
            throw UpstreamFailureException("Invalid image nutrition: ${item.name} has negative optional nutrients")
        }
    }

    private data class Repair(val item: ImageAnalysisItem, val reason: String)

    private fun repairGramOrMlItem(item: ImageAnalysisItem, response: ImageAnalysisResponse): Repair? {
        if (!item.isGramOrMlUnit() || item.servingCount <= 1.0) return null
        if (!item.hasImpossibleGramOrMlPerUnit()) return null

        val totalPortion = item.scalePerUnit(1.0 / item.servingCount)
        val perHundred = item.scalePerUnit(1.0 / 100.0)
        val totalPortionResponse = response.withItem(item, totalPortion).recomputeTotals()
        val perHundredResponse = response.withItem(item, perHundred).recomputeTotals()

        val responseTotal = response.totalCaloriesExact
        val totalPortionMatchesResponse = responseTotal.closeTo(totalPortionResponse.totalCaloriesExact)
        val perHundredMatchesResponse = responseTotal.closeTo(perHundredResponse.totalCaloriesExact)

        return when {
            perHundredMatchesResponse && perHundred.isPlausibleGramOrMlPerUnit() ->
                Repair(perHundred, "repaired ${item.servingUnit} per-100 values by dividing by 100")
            totalPortionMatchesResponse && totalPortion.isPlausibleGramOrMlPerUnit() ->
                Repair(totalPortion, "repaired ${item.servingUnit} totals-as-per-unit by dividing by servingCount")
            item.looksLikeTotalPortionNutrition() && totalPortion.isPlausibleGramOrMlPerUnit() ->
                Repair(totalPortion, "repaired ${item.servingUnit} totals-as-per-unit by dividing by servingCount")
            totalPortion.isPlausibleGramOrMlPerUnit() ->
                Repair(totalPortion, "repaired ${item.servingUnit} totals-as-per-unit by dividing by servingCount")
            perHundred.isPlausibleGramOrMlPerUnit() ->
                Repair(perHundred, "repaired ${item.servingUnit} per-100 values by dividing by 100")
            else -> null
        }
    }

    private fun validateResponse(response: ImageAnalysisResponse) {
        if (response.items.isEmpty()) throw UpstreamFailureException("Invalid image nutrition: no items for successful response")
        response.items.forEach { item ->
            validateItemShape(item)
            val itemTotalCalories = item.caloriesExact * item.servingCount
            if (itemTotalCalories > MAX_ITEM_KCAL) {
                throw UpstreamFailureException("Invalid image nutrition: ${item.name} exceeds item calorie ceiling")
            }
            if (item.isGramOrMlUnit() && !item.isPlausibleGramOrMlPerUnit()) {
                throw UpstreamFailureException("Invalid image nutrition: ${item.name} has impossible ${item.servingUnit} per-unit nutrition")
            }
            if (item.caloriesExact >= 80.0 && !item.isAtwaterPlausible()) {
                throw UpstreamFailureException("Invalid image nutrition: ${item.name} calories do not match macros")
            }
        }
        if (!response.totalCaloriesExact.isFinite() || response.totalCaloriesExact < 0.0) {
            throw UpstreamFailureException("Invalid image nutrition: invalid total calories")
        }
        if (response.totalCaloriesExact > MAX_MEAL_KCAL) {
            throw UpstreamFailureException("Invalid image nutrition: total calories exceed meal ceiling")
        }
    }

    private fun ImageAnalysisResponse.recomputeTotals(): ImageAnalysisResponse {
        val calories = items.sumOf { it.caloriesExact * it.servingCount }
        val protein = items.sumOf { it.proteinG * it.servingCount }
        val carbs = items.sumOf { it.carbsG * it.servingCount }
        val fat = items.sumOf { it.fatG * it.servingCount }
        val fiber = sumNullable { it.fiberG }
        val sodium = sumNullable { it.sodiumMg?.toDouble() }?.roundToInt()
        val sugar = sumNullable { it.sugarG }
        return copy(
            totalCalories = calories.roundToInt(),
            totalCaloriesExact = calories,
            totalProteinG = protein,
            totalCarbsG = carbs,
            totalFatG = fat,
            totalFiberG = fiber,
            totalSodiumMg = sodium,
            totalSugarG = sugar,
        )
    }

    private fun ImageAnalysisResponse.sumNullable(selector: (ImageAnalysisItem) -> Double?): Double? {
        if (items.any { selector(it) == null }) return null
        return items.sumOf { (selector(it) ?: 0.0) * it.servingCount }
    }

    private fun ImageAnalysisResponse.withItem(old: ImageAnalysisItem, new: ImageAnalysisItem): ImageAnalysisResponse =
        copy(items = items.map { if (it === old) new else it })

    private fun ImageAnalysisItem.scalePerUnit(factor: Double): ImageAnalysisItem {
        val exactCalories = caloriesExact * factor
        return copy(
            calories = exactCalories.roundToInt(),
            caloriesExact = exactCalories,
            proteinG = proteinG * factor,
            carbsG = carbsG * factor,
            fatG = fatG * factor,
            fiberG = fiberG?.let { it * factor },
            sodiumMg = sodiumMg?.let { (it * factor).roundToInt() },
            sugarG = sugarG?.let { it * factor },
        )
    }

    private fun ImageAnalysisItem.isGramOrMlUnit(): Boolean {
        val normalized = servingUnit.trim().lowercase()
        return normalized == "g" || normalized == "gram" || normalized == "grams" ||
            normalized == "ml" || normalized == "milliliter" || normalized == "milliliters" ||
            normalized == "millilitre" || normalized == "millilitres"
    }

    private fun ImageAnalysisItem.hasImpossibleGramOrMlPerUnit(): Boolean =
        caloriesExact > MAX_GRAM_OR_ML_KCAL_PER_UNIT ||
            proteinG + carbsG + fatG > MAX_GRAM_OR_ML_MACROS_PER_UNIT ||
            (sodiumMg ?: 0) > MAX_GRAM_OR_ML_SODIUM_MG_PER_UNIT

    private fun ImageAnalysisItem.isPlausibleGramOrMlPerUnit(): Boolean =
        caloriesExact <= MAX_GRAM_OR_ML_KCAL_PER_UNIT &&
            proteinG + carbsG + fatG <= MAX_GRAM_OR_ML_MACROS_PER_UNIT &&
            (sodiumMg ?: 0) <= MAX_GRAM_OR_ML_SODIUM_MG_PER_UNIT

    private fun ImageAnalysisItem.looksLikeTotalPortionNutrition(): Boolean {
        if (caloriesExact <= 0.0 || caloriesExact > MAX_ITEM_KCAL) return false
        val divisor = weightG.takeIf { it > 0.0 } ?: servingCount
        if (caloriesExact / divisor > MAX_GRAM_OR_ML_KCAL_PER_UNIT) return false
        if ((proteinG + carbsG + fatG) / divisor > MAX_GRAM_OR_ML_MACROS_PER_UNIT) return false
        val expected = atwaterCalories()
        val tolerance = caloriesExact * TOTAL_PORTION_ATWATER_TOLERANCE
        return abs(caloriesExact - expected) <= tolerance
    }

    private fun ImageAnalysisItem.isAtwaterPlausible(): Boolean {
        val expected = atwaterCalories()
        val tolerance = maxOf(80.0, caloriesExact * 0.55)
        return abs(caloriesExact - expected) <= tolerance
    }

    private fun ImageAnalysisItem.atwaterCalories(): Double =
        PROTEIN_KCAL_PER_G * proteinG + CARBS_KCAL_PER_G * carbsG + FAT_KCAL_PER_G * fatG

    private fun requireFinite(itemName: String, field: String, value: Double) {
        if (!value.isFinite()) throw UpstreamFailureException("Invalid image nutrition: $itemName has non-finite $field")
    }

    private fun Double.closeTo(expected: Double): Boolean {
        val tolerance = maxOf(80.0, expected * 0.20)
        return abs(this - expected) <= tolerance
    }

    private fun totalsDifferSignificantly(before: Totals, after: Totals): Boolean =
        abs(before.calories - after.calories) > maxOf(5.0, after.calories * 0.02) ||
            abs(before.protein - after.protein) > maxOf(2.0, after.protein * 0.05) ||
            abs(before.carbs - after.carbs) > maxOf(2.0, after.carbs * 0.05) ||
            abs(before.fat - after.fat) > maxOf(2.0, after.fat * 0.05)
}
