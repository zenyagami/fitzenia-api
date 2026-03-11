package com.zenthek.upstream.fatsecret.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class FatSecretTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long
)

// --- Barcode lookup ---

@Serializable
data class FatSecretBarcodeResponse(
    val food_id: FatSecretFoodIdDto? = null
)

@Serializable
data class FatSecretFoodIdDto(val value: String = "")

// --- Food detail (used by barcode lookup via food.get.v4) ---

@Serializable
data class FatSecretFoodDetailResponse(
    val food: FatSecretFoodDetailDto? = null
)

@Serializable
data class FatSecretFoodDetailDto(
    @SerialName("food_id") val foodId: String,
    @SerialName("food_name") val foodName: String,
    @SerialName("food_type") val foodType: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    val servings: FatSecretServingsWrapper? = null
)

private object ServingListSerializer :
    JsonTransformingSerializer<List<FatSecretServingDto>>(
        ListSerializer(FatSecretServingDto.serializer())
    ) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable
data class FatSecretServingsWrapper(
    @Serializable(with = ServingListSerializer::class)
    val serving: List<FatSecretServingDto> = emptyList()
)

@Serializable
data class FatSecretServingDto(
    @SerialName("serving_id") val servingId: String? = null,
    @SerialName("serving_description") val servingDescription: String? = null,
    @SerialName("metric_serving_amount") val metricServingAmount: String? = null,
    @SerialName("metric_serving_unit") val metricServingUnit: String? = null,
    @SerialName("is_default") val isDefault: String? = null,
    val calories: String? = null,
    val carbohydrate: String? = null,
    val protein: String? = null,
    val fat: String? = null,
    @SerialName("saturated_fat") val saturatedFat: String? = null,
    val fiber: String? = null,
    val sodium: String? = null,
    val sugar: String? = null
)

// --- foods.search.v5 ---

private object FoodDetailListSerializer :
    JsonTransformingSerializer<List<FatSecretFoodDetailDto>>(
        ListSerializer(FatSecretFoodDetailDto.serializer())
    ) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable
data class FatSecretV5SearchResponse(
    @SerialName("foods_search") val foodsSearch: FatSecretV5FoodsSearch
)

@Serializable
data class FatSecretV5FoodsSearch(
    @SerialName("max_results") val maxResults: String? = null,
    @SerialName("total_results") val totalResults: String? = null,
    @SerialName("page_number") val pageNumber: String? = null,
    val results: FatSecretV5Results? = null
)

@Serializable
data class FatSecretV5Results(
    @Serializable(with = FoodDetailListSerializer::class)
    val food: List<FatSecretFoodDetailDto> = emptyList()
)

// --- foods.autocomplete.v2 ---

private object SuggestionListSerializer :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable
data class FatSecretAutocompleteResponse(
    val suggestions: FatSecretSuggestionsWrapper? = null
)

@Serializable
data class FatSecretSuggestionsWrapper(
    @Serializable(with = SuggestionListSerializer::class)
    val suggestion: List<String> = emptyList()
)
