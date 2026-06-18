package com.zenthek.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ImageAnalysisSugarTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sugar is optional when decoding an image analysis response`() {
        val response = json.decodeFromString<ImageAnalysisResponse>(responseJson())

        assertNull(response.items.single().sugarG)
        assertNull(response.totalSugarG)
    }

    @Test
    fun `sugar is returned when the analyzer provides an estimate`() {
        val response = json.decodeFromString<ImageAnalysisResponse>(
            responseJson(itemSugar = 18.5, totalSugar = 18.5)
        )

        assertEquals(18.5, response.items.single().sugarG)
        assertEquals(18.5, response.totalSugarG)
    }

    @Test
    fun `sugar schema fields are nullable and not required`() {
        val schema = ImageAnalyzerFactory.imageAnalysisResponseSchema()
        val properties = schema["properties"]!!.jsonObject
        val itemSchema = properties["items"]!!.jsonObject["items"]!!.jsonObject
        val itemProperties = itemSchema["properties"]!!.jsonObject
        val itemRequired = itemSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val responseRequired = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("number", "null"), itemProperties["sugarG"]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("sugarG" in itemRequired)
        assertEquals(listOf("number", "null"), properties["totalSugarG"]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("totalSugarG" in responseRequired)
    }

    private fun responseJson(itemSugar: Double? = null, totalSugar: Double? = null): String {
        val itemSugarJson = itemSugar?.let { ", \"sugarG\": $it" }.orEmpty()
        val totalSugarJson = totalSugar?.let { ", \"totalSugarG\": $it" }.orEmpty()
        return """
            {
              "errorCode": null,
              "title": "Cake",
              "subtitle": "One slice",
              "isLikelyRestaurant": false,
              "items": [{
                "name": "Cake",
                "portionDescription": "One slice",
                "servingUnit": "slice",
                "servingCount": 1.0,
                "weightG": 90.0,
                "confidence": "high",
                "calories": 300,
                "proteinG": 3.0,
                "carbsG": 40.0,
                "fatG": 14.0,
                "fiberG": 1.0,
                "sodiumMg": 200
                $itemSugarJson
              }],
              "totalCalories": 300,
              "totalProteinG": 3.0,
              "totalCarbsG": 40.0,
              "totalFatG": 14.0,
              "totalFiberG": 1.0,
              "totalSodiumMg": 200
              $totalSugarJson
            }
        """.trimIndent()
    }
}
