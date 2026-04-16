package com.zenthek.mapper

import com.zenthek.model.ResultKind
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsNutriments
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchProduct
import com.zenthek.upstream.usda.dto.UsdaSearchFoodDto
import com.zenthek.upstream.usda.dto.UsdaSearchNutrientDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultKindClassificationTest {

    @Test
    fun `USDA Foundation maps to GENERIC`() {
        val dto = usdaItem(dataType = "Foundation")
        val result = UsdaMapper.mapSearchItemWithKind(dto)
        assertEquals(ResultKind.GENERIC, result?.kind)
    }

    @Test
    fun `USDA SR Legacy maps to GENERIC`() {
        val dto = usdaItem(dataType = "SR Legacy")
        val result = UsdaMapper.mapSearchItemWithKind(dto)
        assertEquals(ResultKind.GENERIC, result?.kind)
    }

    @Test
    fun `USDA Survey FNDDS maps to GENERIC`() {
        val dto = usdaItem(dataType = "Survey (FNDDS)")
        val result = UsdaMapper.mapSearchItemWithKind(dto)
        assertEquals(ResultKind.GENERIC, result?.kind)
    }

    @Test
    fun `USDA Branded maps to BRANDED`() {
        val dto = usdaItem(dataType = "Branded")
        val result = UsdaMapper.mapSearchItemWithKind(dto)
        assertEquals(ResultKind.BRANDED, result?.kind)
    }

    @Test
    fun `USDA unknown dataType defaults to BRANDED`() {
        val dto = usdaItem(dataType = null)
        val result = UsdaMapper.mapSearchItemWithKind(dto)
        assertEquals(ResultKind.BRANDED, result?.kind)
    }

    @Test
    fun `USDA mapping returns null on blank description`() {
        val dto = usdaItem(dataType = "Foundation", description = "   ")
        assertNull(UsdaMapper.mapSearchItemWithKind(dto))
    }

    @Test
    fun `OFF always maps to BRANDED regardless of product data`() {
        val product = offProduct(brands = listOf("Acme"))
        val result = OpenFoodFactsMapper.mapV3SearchWithKind(product)
        assertEquals(ResultKind.BRANDED, result?.kind)
    }

    @Test
    fun `OFF with no brand still maps to BRANDED - orchestrator decides promotion`() {
        val product = offProduct(brands = null)
        val result = OpenFoodFactsMapper.mapV3SearchWithKind(product)
        assertEquals(ResultKind.BRANDED, result?.kind)
    }

    // ----- fixtures -----

    private fun usdaItem(
        dataType: String?,
        description: String = "Some food"
    ) = UsdaSearchFoodDto(
        fdcId = 1L,
        description = description,
        dataType = dataType,
        gtinUpc = null,
        brandOwner = null,
        brandName = null,
        servingSize = 100f,
        servingSizeUnit = "g",
        foodNutrients = listOf(
            UsdaSearchNutrientDto(nutrientId = 1008, nutrientName = "Energy", unitName = "KCAL", value = 100f),
            UsdaSearchNutrientDto(nutrientId = 1003, nutrientName = "Protein", unitName = "G", value = 5f),
            UsdaSearchNutrientDto(nutrientId = 1005, nutrientName = "Carbs", unitName = "G", value = 15f),
            UsdaSearchNutrientDto(nutrientId = 1004, nutrientName = "Fat", unitName = "G", value = 2f)
        )
    )

    private fun offProduct(brands: List<String>?) = OpenFoodFactsV3SearchProduct(
        code = "1234567890",
        productName = "Some Product",
        brands = brands,
        servingSize = null,
        servingQuantity = null,
        imageUrl = null,
        nutriments = OpenFoodFactsNutriments(
            energyKcal100g = 200f,
            proteins100g = 10f,
            carbohydrates100g = 20f,
            fat100g = 8f
        )
    )
}
