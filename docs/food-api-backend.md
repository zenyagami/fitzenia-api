# Food API Backend — Implementation Spec

> **Purpose of this document:** This is a complete implementation spec for an AI coding agent (Claude or Gemini) to build a Ktor JVM backend that proxies food database lookups on behalf of the Fitzenio mobile app. Read every section before writing any code.

---

## Overview

The mobile app (Kotlin Multiplatform — Android + iOS) needs to search for foods by name and look up foods by barcode. Rather than calling third-party food APIs from the device (which would expose API keys), a dedicated backend proxies these calls, normalizes the responses into a unified schema, and returns clean JSON to the app.

**What this backend does:**
- Accepts food search queries and barcode lookups from the mobile app
- Calls up to three upstream food databases in sequence / parallel (Open Food Facts, FatSecret, USDA)
- Normalizes all responses into a single `FoodItem` schema
- Returns the merged, deduplicated list to the app

**What this backend does NOT do (yet):**
- Caching (call upstream on every request — add later)
- AI food photo recognition (separate concern)

**Authentication:**
- `/health` is public for probes
- `/api/food/*` and `/api/user/*` require `Authorization: Bearer <Supabase access token>`
- The backend validates Supabase JWTs against `SUPABASE_URL/auth/v1/.well-known/jwks.json`

---

## Deployment target

- **Platform:** Google Cloud Run (containerized JVM)
- **Language:** Kotlin (JVM)
- **Framework:** Ktor 3.x (server)
- **Build tool:** Gradle (Kotlin DSL)
- **JDK:** 21 (LTS)
- **Container base image:** `eclipse-temurin:21-jre-alpine`

---

## Tech stack

| Concern | Library | Version |
|---|---|---|
| HTTP server | `io.ktor:ktor-server-netty` | `3.1.x` |
| HTTP client (upstream calls) | `io.ktor:ktor-client-cio` | `3.1.x` |
| JSON serialization | `io.ktor:ktor-serialization-kotlinx-json` + `org.jetbrains.kotlinx:kotlinx-serialization-json` | `3.1.x` / `1.7.x` |
| Content negotiation (server) | `io.ktor:ktor-server-content-negotiation` | `3.1.x` |
| Content negotiation (client) | `io.ktor:ktor-client-content-negotiation` | `3.1.x` |
| Logging | `io.ktor:ktor-server-call-logging` + `ch.qos.logback:logback-classic` | `3.1.x` / `1.5.x` |
| Config | `io.ktor:ktor-server-config-yaml` | `3.1.x` |
| Status pages | `io.ktor:ktor-server-status-pages` | `3.1.x` |
| DI | None — use plain constructor injection / object | — |

---

## Project structure

```
food-api/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── application.yaml                    # Ktor config (port, env var refs)
└── src/main/kotlin/com/zenthek/foodapi/
    ├── Application.kt                  # main() — configureServer()
    ├── plugins/
    │   ├── Serialization.kt            # install(ContentNegotiation) { json() }
    │   ├── Routing.kt                  # install all route modules
    │   ├── StatusPages.kt              # global error → HTTP response mapping
    │   └── CallLogging.kt              # install(CallLogging)
    ├── config/
    │   └── ApiKeys.kt                  # reads env vars into a data class
    ├── model/
    │   ├── FoodItem.kt                 # unified response model (see §Response models)
    │   └── ApiError.kt                 # error envelope
    ├── routes/
    │   └── FoodRoutes.kt               # GET /food/search, GET /food/barcode/{barcode}
    ├── service/
    │   └── FoodService.kt              # orchestrates all upstream calls, deduplication
    ├── upstream/
    │   ├── openfoodfacts/
    │   │   ├── OpenFoodFactsClient.kt  # HTTP calls to OFF
    │   │   └── dto/
    │   │       └── OpenFoodFactsDto.kt # OFF response DTOs
    │   ├── fatsecret/
    │   │   ├── FatSecretClient.kt      # OAuth token mgmt + HTTP calls
    │   │   ├── FatSecretTokenManager.kt
    │   │   └── dto/
    │   │       └── FatSecretDto.kt     # FatSecret response DTOs
    │   └── usda/
    │       ├── UsdaClient.kt           # HTTP calls to USDA
    │       └── dto/
    │           └── UsdaDto.kt          # USDA response DTOs
    └── mapper/
        ├── OpenFoodFactsMapper.kt      # OFF DTO → FoodItem
        ├── FatSecretMapper.kt          # FatSecret DTO → FoodItem
        └── UsdaMapper.kt               # USDA DTO → FoodItem
```

---

## Endpoints

### `GET /food/search`

Search for foods by text query. Aggregates results from all three sources.

**Query parameters:**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `q` | String | Yes | — | Search query (e.g. "chicken breast") |
| `page` | Int | No | `0` | Zero-based page number |
| `pageSize` | Int | No | `25` | Results per page (max 50) |

**Success response — `200 OK`:**

```json
{
  "results": [ /* array of FoodItem — see §Response models */ ],
  "totalResults": 73,
  "page": 0,
  "pageSize": 25
}
```

**Error responses:**

| Status | Condition |
|---|---|
| `400` | `q` is blank or missing |
| `502` | All upstream APIs failed |
| `500` | Unexpected server error |

---

### `GET /food/barcode/{barcode}`

Look up a single food by barcode (EAN-13, UPC-A, UPC-E, etc.).

**Path parameter:**

| Parameter | Type | Description |
|---|---|---|
| `barcode` | String | Barcode string (digits only) |

**Success response — `200 OK`:**

```json
{
  "result": { /* single FoodItem or null */ }
}
```

Returns `{ "result": null }` with `200` when the barcode is not found in any database (not a `404`, because "not found in databases" is a valid successful lookup).

**Error responses:**

| Status | Condition |
|---|---|
| `400` | Barcode contains non-digit characters or is empty |
| `502` | All upstream APIs failed with network/server errors |
| `500` | Unexpected server error |

---

## Response models

### `FoodItem`

Unified model returned for every food from every source.

```kotlin
@Serializable
data class FoodItem(
    val id: String,             // Unique ID: "{SOURCE}_{sourceSpecificId}" e.g. "OFF_0737628064502"
    val name: String,           // Product/food name, trimmed, title-cased if all-caps
    val brand: String?,         // Brand name, null if unknown or generic food
    val barcode: String?,       // EAN/UPC barcode, null if not applicable
    val source: FoodSource,     // Which database this came from
    val imageUrl: String?,      // Product image URL, null if unavailable
    val servings: List<ServingSize>  // At least one entry always present
)

@Serializable
enum class FoodSource {
    OPEN_FOOD_FACTS,
    FATSECRET,
    USDA
}

@Serializable
data class ServingSize(
    val name: String,           // Human-readable label: "100g", "1 serving (30g)", "1 cup"
    val weightGrams: Float,     // Weight in grams this serving represents (used for scaling)
    val nutrition: NutritionInfo
)

@Serializable
data class NutritionInfo(
    val caloriesKcal: Float,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val fiberG: Float?,         // null = not available from this source
    val sodiumMg: Float?,       // null = not available
    val sugarG: Float?,         // null = not available
    val saturatedFatG: Float?   // null = not available
)
```

### `ApiError`

```kotlin
@Serializable
data class ApiError(
    val code: String,   // e.g. "MISSING_QUERY", "UPSTREAM_FAILURE"
    val message: String
)
```

---

## Upstream API 1: Open Food Facts

**Documentation:** https://world.openfoodfacts.org/data

### Authentication
None required. Add a `User-Agent` header to be a good API citizen:
```
User-Agent: Fitzenio/1.0 (Android/iOS app; contact@fitzenio.com)
```

### Rate limit
~100 requests/minute. No API key needed.

### Base URL
```
https://world.openfoodfacts.org
```

### Barcode lookup

```
GET /api/v2/product/{barcode}?fields=code,product_name,brands,serving_size,serving_quantity,image_url,nutriments
```

**Response — product found (`status: 1`):**
```json
{
  "code": "0737628064502",
  "status": 1,
  "product": {
    "code": "0737628064502",
    "product_name": "Organic Whole Milk",
    "brands": "Horizon Organic",
    "serving_size": "240 ml",
    "serving_quantity": 240.0,
    "image_url": "https://images.openfoodfacts.org/...",
    "nutriments": {
      "energy-kcal_100g": 61.0,
      "proteins_100g": 3.2,
      "carbohydrates_100g": 4.7,
      "sugars_100g": 4.7,
      "fat_100g": 3.3,
      "saturated-fat_100g": 2.1,
      "fiber_100g": 0.0,
      "sodium_100g": 0.043
    }
  }
}
```

**Product not found (`status: 0`):**
```json
{
  "code": "1234567890123",
  "status": 0,
  "statusVerbose": "product not found"
}
```

### Text search

Use the **v2 search endpoint** (not the legacy `/cgi/search.pl`). Pages are 1-indexed.

```
GET /api/v2/search?search_terms={query}&page={page+1}&page_size={pageSize}&fields=code,product_name,brands,serving_size,serving_quantity,image_url,nutriments&sort_by=unique_scans_n
```

`sort_by=unique_scans_n` surfaces the most scanned (most complete/reliable) products first.

**Response:**
```json
{
  "count": 134,
  "page": 1,
  "page_size": 25,
  "products": [ /* array of product objects same shape as above */ ]
}
```

### Fields to extract

| OFF field | Maps to |
|---|---|
| `code` | `FoodItem.barcode`, `FoodItem.id` = `"OFF_{code}"` |
| `product_name` | `FoodItem.name` |
| `brands` | `FoodItem.brand` (take first brand if comma-separated) |
| `image_url` | `FoodItem.imageUrl` |
| `serving_quantity` + `serving_size` | `ServingSize.name` = `serving_size` string, `ServingSize.weightGrams` = `serving_quantity` |
| `nutriments.energy-kcal_100g` | `NutritionInfo.caloriesKcal` (for 100g serving) |
| `nutriments.proteins_100g` | `NutritionInfo.proteinG` |
| `nutriments.carbohydrates_100g` | `NutritionInfo.carbsG` |
| `nutriments.fat_100g` | `NutritionInfo.fatG` |
| `nutriments.fiber_100g` | `NutritionInfo.fiberG` |
| `nutriments.sodium_100g` × 1000 | `NutritionInfo.sodiumMg` (convert g → mg) |
| `nutriments.sugars_100g` | `NutritionInfo.sugarG` |
| `nutriments.saturated-fat_100g` | `NutritionInfo.saturatedFatG` |

### ServingSize strategy for OFF

Always include at least a "100g" serving (use the `_100g` nutriment values directly):
```kotlin
ServingSize(name = "100g", weightGrams = 100f, nutrition = nutritionPer100g)
```
If `serving_quantity` > 0, also add a second serving:
```kotlin
ServingSize(
    name = serving_size ?: "${serving_quantity}g",
    weightGrams = serving_quantity,
    nutrition = scale(nutritionPer100g, serving_quantity / 100f)
)
```

### Filtering bad results from OFF

Skip a product if:
- `product_name` is blank
- `nutriments.energy-kcal_100g` is 0 AND `proteins_100g` is 0 AND `fat_100g` is 0 (no nutrition data)

---

## Upstream API 2: FatSecret

**Documentation:** https://platform.fatsecret.com/api/Default.aspx?screen=rapih

### Authentication
OAuth 2.0 **Client Credentials** flow. Tokens expire after `expires_in` seconds (typically 86400s = 24h).

**Token endpoint:**
```
POST https://oauth.fatsecret.com/connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=basic&client_id={clientId}&client_secret={clientSecret}
```

Send credentials as **form body fields** — confirmed working from mobile implementation. The `Authorization: Basic` header approach also works per the OAuth2 spec, but form body is what's battle-tested here.

**Token response:**
```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

**Token management requirements:**
- Cache the token in memory (instance variable with expiry timestamp)
- Refresh 60 seconds before expiry
- Token manager must be thread-safe (use `Mutex` or `@Synchronized`)
- All FatSecret API calls use `Authorization: Bearer {access_token}`

### Rate limit
Free tier: **5,000 calls/day**. No per-minute rate limit.

### Base URL
```
https://platform.fatsecret.com/rest/server.api
```
All calls are POST (despite being reads). Use `Content-Type: application/x-www-form-urlencoded`.

### Barcode lookup

```
POST https://platform.fatsecret.com/rest/server.api
Content-Type: application/x-www-form-urlencoded

method=food.find_id_for_barcode&barcode={barcode}&format=json
```

**Response — found:**
```json
{
  "food_id": {
    "value": "34529"
  }
}
```

**Response — not found:** `food_id` field is simply absent (null after deserialization). Treat `food_id == null` as "not found" — no separate error code to check. The DTO should declare it nullable:
```kotlin
@Serializable
data class FatSecretBarcodeResponse(
    val food_id: FatSecretFoodIdDto? = null,  // null = barcode not in FatSecret
)
@Serializable
data class FatSecretFoodIdDto(val value: String = "")
```

After getting the `food_id`, call **food.get.v4** to retrieve full details (see below).

### Food detail by ID

```
POST https://platform.fatsecret.com/rest/server.api
Content-Type: application/x-www-form-urlencoded

method=food.get.v4&food_id={foodId}&format=json
```

**Response:**
```json
{
  "food": {
    "food_id": "34529",
    "food_name": "Whole Milk",
    "food_type": "Generic",
    "brand_name": null,
    "servings": {
      "serving": [
        {
          "serving_id": "30112",
          "serving_description": "100 g",
          "metric_serving_amount": "100.000",
          "metric_serving_unit": "g",
          "calories": "61",
          "carbohydrate": "4.80",
          "protein": "3.15",
          "fat": "3.25",
          "saturated_fat": "1.865",
          "fiber": "0",
          "sodium": "43",
          "sugar": "5.05"
        }
      ]
    }
  }
}
```

**Important:** `serving` can be either a JSON object (single serving) or a JSON array (multiple servings). The same is true for the `food` field in search results. Handle both with `JsonTransformingSerializer`:

```kotlin
private object ServingListSerializer :
    JsonTransformingSerializer<List<FatSecretServingDto>>(
        ListSerializer(FatSecretServingDto.serializer())
    ) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

// Apply to the wrapper:
@Serializable
data class FatSecretServingsWrapper(
    @Serializable(with = ServingListSerializer::class)
    val serving: List<FatSecretServingDto> = emptyList(),
)
```

Apply the same pattern to the `food` field in search results (`FoodSearchListSerializer`).

### Text search

```
POST https://platform.fatsecret.com/rest/server.api
Content-Type: application/x-www-form-urlencoded

method=foods.search&search_expression={query}&page_number={page}&max_results={pageSize}&format=json
```

**Response** — top-level key is `foods` (not `foods_search`):
```json
{
  "foods": {
    "max_results": "25",
    "total_results": "200",
    "page_number": "0",
    "food": [
      {
        "food_id": "34529",
        "food_name": "Whole Milk",
        "food_type": "Generic",
        "brand_name": null,
        "food_url": "https://...",
        "food_description": "Per 100g - Calories: 61kcal | Fat: 3.25g | Carbs: 4.80g | Prot: 3.15g"
      }
    ]
  }
}
```

Note: `food` array is directly inside `foods`, not nested under `results`.

The search response only returns summary data. For full nutrition in search results, **parse `food_description`** to extract the macros (simpler) OR call `food.get.v4` for each result (expensive — avoid for search, do on demand).

**Parsing `food_description`:**
The format is always: `"Per {amount}{unit} - Calories: {n}kcal | Fat: {n}g | Carbs: {n}g | Prot: {n}g"`
Parse with regex: `Calories: ([\d.]+)kcal \| Fat: ([\d.]+)g \| Carbs: ([\d.]+)g \| Prot: ([\d.]+)g`
Also extract the serving amount/unit from the "Per X" prefix.

### Fields to extract

| FatSecret field | Maps to |
|---|---|
| `food_id` | `FoodItem.id` = `"FS_{food_id}"` |
| `food_name` | `FoodItem.name` |
| `brand_name` (null for generics) | `FoodItem.brand` |
| No image in free tier | `FoodItem.imageUrl = null` |
| `serving_description` | `ServingSize.name` |
| `metric_serving_amount` (Float) | `ServingSize.weightGrams` |
| `calories` | `NutritionInfo.caloriesKcal` |
| `protein` | `NutritionInfo.proteinG` |
| `carbohydrate` | `NutritionInfo.carbsG` |
| `fat` | `NutritionInfo.fatG` |
| `fiber` | `NutritionInfo.fiberG` (null if "0" and no fiber data) |
| `sodium` | `NutritionInfo.sodiumMg` (FatSecret sodium is already in mg) |
| `sugar` | `NutritionInfo.sugarG` |
| `saturated_fat` | `NutritionInfo.saturatedFatG` |

All numeric fields from FatSecret are **strings** — parse with `toFloatOrNull() ?: 0f`.

---

## Upstream API 3: USDA FoodData Central

**Documentation:** https://fdc.nal.usda.gov/api-guide.html

### Authentication
API key passed as query parameter `?api_key={key}`. Free, register at https://fdc.nal.usda.gov/api-key-signup.html

### Rate limit
**1,000 requests/hour**.

### Base URL
```
https://api.nal.usda.gov/fdc/v1
```

### Text search

Use **GET with query parameters** (confirmed working; USDA also accepts POST+JSON but GET is simpler). Pages are 1-indexed.

```
GET https://api.nal.usda.gov/fdc/v1/foods/search?api_key={key}&query={query}&pageNumber={page+1}&pageSize={pageSize}&dataType=Branded,Foundation,SR+Legacy
```

**Response:**
```json
{
  "totalHits": 342,
  "currentPage": 1,
  "totalPages": 14,
  "foods": [
    {
      "fdcId": 2341823,
      "description": "WHOLE MILK",
      "dataType": "Branded",
      "gtinUpc": "070038619253",
      "brandOwner": "Organic Valley",
      "brandName": "ORGANIC VALLEY",
      "ingredients": "ORGANIC WHOLE MILK",
      "foodCategory": "Dairy and Egg Products",
      "servingSize": 240.0,
      "servingSizeUnit": "ml",
      "publishedDate": "2021-10-28",
      "foodNutrients": [
        { "nutrientId": 1008, "nutrientName": "Energy", "unitName": "KCAL", "value": 150.0 },
        { "nutrientId": 1003, "nutrientName": "Protein", "unitName": "G", "value": 8.0 },
        { "nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "unitName": "G", "value": 12.0 },
        { "nutrientId": 1004, "nutrientName": "Total lipid (fat)", "unitName": "G", "value": 8.0 },
        { "nutrientId": 1079, "nutrientName": "Fiber, total dietary", "unitName": "G", "value": 0.0 },
        { "nutrientId": 1093, "nutrientName": "Sodium, Na", "unitName": "MG", "value": 105.0 },
        { "nutrientId": 2000, "nutrientName": "Sugars, total including NLEA", "unitName": "G", "value": 12.0 },
        { "nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "unitName": "G", "value": 5.0 }
      ]

**⚠️ IMPORTANT — search `foodNutrients` shape is flat (fields at top level):**
```json
{ "nutrientId": 1008, "nutrientName": "Energy", "unitName": "KCAL", "value": 150.0 }
```
This is **different** from the detail endpoint (see below) where nutrients are nested.
    }
  ]
}
```

### Barcode lookup via search

USDA doesn't have a dedicated barcode endpoint. Use the search endpoint with the barcode as query and filter by `gtinUpc`:

```
GET /fdc/v1/foods/search?api_key={key}&query={barcode}&dataType=Branded
```

Then find the item where `gtinUpc == barcode`. If none match exactly, treat as not found.

### Food detail by FDC ID

```
GET https://api.nal.usda.gov/fdc/v1/food/{fdcId}?format=abridged&api_key={key}
```

Only needed if the search response is missing nutrition data. The search endpoint includes `foodNutrients` already.

**⚠️ IMPORTANT — detail `foodNutrients` shape is NESTED (different from search):**
```json
{
  "nutrient": { "id": 1008, "name": "Energy", "unitName": "KCAL" },
  "amount": 150.0
}
```
Use `nutrient.id` (not `nutrientId`) and `amount` (not `value`) when parsing detail responses.

**`labelNutrients` — prefer for branded items (per-serving label data, more accurate):**
```json
{
  "labelNutrients": {
    "calories":      { "value": 150.0 },
    "fat":           { "value": 8.0 },
    "saturatedFat":  { "value": 5.0 },
    "carbohydrates": { "value": 12.0 },
    "fiber":         { "value": 0.0 },
    "sugars":        { "value": 12.0 },
    "protein":       { "value": 8.0 },
    "sodium":        { "value": 105.0 }
  }
}
```
If `labelNutrients` is present and non-null, use it instead of `foodNutrients` — it matches the Nutrition Facts label exactly. Foundation/SR Legacy items won't have it.

### Nutrient IDs — mandatory mapping

```kotlin
object UsdaNutrientId {
    const val ENERGY_KCAL = 1008
    const val PROTEIN = 1003
    const val CARBOHYDRATE = 1005  // "Carbohydrate, by difference"
    const val FAT = 1004            // "Total lipid (fat)"
    const val FIBER = 1079
    const val SODIUM = 1093         // Unit is MG
    const val SUGARS = 2000
    const val SATURATED_FAT = 1258
}
```

Search response helper (flat shape — `nutrientId` + `value`):
```kotlin
fun List<UsdaSearchNutrientDto>.findValue(id: Int): Float? =
    firstOrNull { it.nutrientId == id }?.value
```

Detail response helper (nested shape — `nutrient.id` + `amount`):
```kotlin
fun List<UsdaFoodNutrientDto>.findValue(id: Int): Float? =
    firstOrNull { it.nutrient.id == id }?.amount
```

### Fields to extract

| USDA field | Maps to |
|---|---|
| `fdcId` | `FoodItem.id` = `"USDA_{fdcId}"` |
| `description` | `FoodItem.name` (apply title-case: all-caps descriptions are common) |
| `brandOwner` or `brandName` | `FoodItem.brand` (prefer `brandName`, fall back to `brandOwner`) |
| `gtinUpc` | `FoodItem.barcode` |
| No image available | `FoodItem.imageUrl = null` |
| `servingSize` + `servingSizeUnit` | `ServingSize` — see strategy below |
| nutrientId 1008 | `NutritionInfo.caloriesKcal` |
| nutrientId 1003 | `NutritionInfo.proteinG` |
| nutrientId 1005 | `NutritionInfo.carbsG` |
| nutrientId 1004 | `NutritionInfo.fatG` |
| nutrientId 1079 | `NutritionInfo.fiberG` |
| nutrientId 1093 | `NutritionInfo.sodiumMg` (already in mg) |
| nutrientId 2000 | `NutritionInfo.sugarG` |
| nutrientId 1258 | `NutritionInfo.saturatedFatG` |

### ServingSize strategy for USDA

USDA nutrients in search results are **per serving** (not per 100g). The `servingSize` + `servingSizeUnit` fields define what one serving is.

```kotlin
// Step 1: Calculate weight in grams for 1 serving
val weightGrams = when (servingSizeUnit?.lowercase()) {
    "g" -> servingSize
    "ml" -> servingSize  // treat ml ≈ g for water-based foods
    "oz" -> servingSize * 28.3495f
    else -> servingSize  // assume grams
}

// Step 2: Build serving size entry
ServingSize(
    name = "1 serving (${servingSize.toInt()}${servingSizeUnit})",
    weightGrams = weightGrams,
    nutrition = nutritionFromNutrients  // nutrients are already per-serving
)

// Step 3: Also add a 100g serving by scaling
ServingSize(
    name = "100g",
    weightGrams = 100f,
    nutrition = scale(nutritionFromNutrients, 100f / weightGrams)
)
```

For Foundation/SR Legacy items (no brand, no serving size), `servingSize` may be null — default to 100g only.

---

## Aggregation and fallback logic

### Barcode lookup (`GET /food/barcode/{barcode}`)

Try each source in order. Return the **first successful result**. Only try the next source if the current one returns "not found" (not if it returns an error).

```
1. Try Open Food Facts
   → Found: normalize + return immediately
   → Not found (status == 0): try next
   → Network/server error: log + try next

2. Try FatSecret
   → Get food_id by barcode
   → Found: call food.get.v4 for full details, normalize + return
   → Not found (error code 106): try next
   → Network/server error: log + try next

3. Try USDA
   → Search by barcode string, filter by gtinUpc match
   → Found: normalize + return
   → Not found: return { "result": null }
   → Network/server error: log, return { "result": null }

If ALL sources fail with network errors → return 502
```

### Text search (`GET /food/search`)

Call **all three sources concurrently** (using `async`/`coroutineScope`). Collect all results, merge, deduplicate.

```kotlin
coroutineScope {
    val offDeferred = async { openFoodFactsClient.search(query, page, pageSize) }
    val fsDeferred = async { fatSecretClient.search(query, page, pageSize) }
    val usdaDeferred = async { usdaClient.search(query, page, pageSize) }

    val offResults = offDeferred.await().getOrElse { emptyList() }
    val fsResults = fsDeferred.await().getOrElse { emptyList() }
    val usdaResults = usdaDeferred.await().getOrElse { emptyList() }

    merge(offResults, fsResults, usdaResults)
}
```

If all three fail → return `502`.
If at least one succeeds → return the available results (don't fail the whole request).

### Deduplication

For text search, different sources often return the same product. Deduplicate by:
1. **Barcode match**: if two items have the same non-null barcode, keep only one — prefer OFF → FatSecret → USDA (in that priority)
2. **Name + brand fuzzy match**: if two items have the same `brand` and `name` (case-insensitive, trimmed), keep only one using the same priority

### Result ordering

After deduplication, return results in this interleaved order to surface variety:
- Index 0: best OFF result
- Index 1: best FatSecret result
- Index 2: best USDA result
- Index 3: second OFF result
- Index 4: second FatSecret result
- ... and so on (round-robin)

---

## Normalization helpers

### Title-case conversion

USDA descriptions are all-caps (e.g., `"WHOLE MILK, ORGANIC"`). Apply title-case:
```kotlin
fun String.toTitleCase(): String = split(" ").joinToString(" ") { word ->
    word.lowercase().replaceFirstChar { it.uppercase() }
}
```
Apply this to all food names from USDA. OFF and FatSecret names are already mixed-case.

### Brand extraction from OFF

OFF `brands` field is comma-separated. Take the first entry only:
```kotlin
val brand = brands?.split(",")?.firstOrNull()?.trim()?.ifBlank { null }
```

### Sodium unit unification

- OFF: `sodium_100g` is in **grams** → multiply by 1000 to get mg
- FatSecret: `sodium` is already in **mg**
- USDA: nutrient 1093 is already in **mg**

Always store `sodiumMg` in the response model.

---

## Environment variables

The server reads all secrets from environment variables. **Never hardcode API keys.**

| Variable | Description | Required |
|---|---|---|
| `FATSECRET_CLIENT_ID` | FatSecret OAuth2 client ID | Yes |
| `FATSECRET_CLIENT_SECRET` | FatSecret OAuth2 client secret | Yes |
| `USDA_API_KEY` | USDA FoodData Central API key | Yes |
| `PORT` | Server port (Cloud Run injects this automatically) | No (default 8080) |

Open Food Facts requires no API key.

```kotlin
// config/ApiKeys.kt
data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String
) {
    companion object {
        fun fromEnv(): ApiKeys = ApiKeys(
            fatSecretClientId = System.getenv("FATSECRET_CLIENT_ID")
                ?: error("FATSECRET_CLIENT_ID env var not set"),
            fatSecretClientSecret = System.getenv("FATSECRET_CLIENT_SECRET")
                ?: error("FATSECRET_CLIENT_SECRET env var not set"),
            usdaApiKey = System.getenv("USDA_API_KEY")
                ?: error("USDA_API_KEY env var not set")
        )
    }
}
```

---

## Error handling

### `StatusPages` plugin

Register a global handler in `StatusPages.kt`:

```kotlin
exception<IllegalArgumentException> { call, cause ->
    call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", cause.message ?: "Invalid input"))
}
exception<UpstreamFailureException> { call, cause ->
    call.respond(HttpStatusCode.BadGateway, ApiError("UPSTREAM_FAILURE", cause.message ?: "All upstream APIs failed"))
}
exception<Throwable> { call, cause ->
    // Log the full stack trace here
    call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Unexpected error"))
}
```

### `UpstreamFailureException`

```kotlin
class UpstreamFailureException(message: String) : Exception(message)
```

Thrown by `FoodService` when all sources fail.

### Input validation

In route handlers, validate inputs before calling the service:
```kotlin
val query = call.request.queryParameters["q"]?.trim()
    ?: throw IllegalArgumentException("Missing required parameter: q")
if (query.isBlank()) throw IllegalArgumentException("Parameter 'q' must not be blank")
```

---

## `application.yaml`

```yaml
ktor:
  application:
    modules:
      - com.zenthek.foodapi.ApplicationKt.module
  deployment:
    port: ${PORT:8080}
    watch:
      - classes
      - resources

food-api:
  version: "1.0.0"
```

---

## `Application.kt` wiring

```kotlin
fun main() {
    val apiKeys = ApiKeys.fromEnv()
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) {
        module(apiKeys)
    }.start(wait = true)
}

fun Application.module(apiKeys: ApiKeys = ApiKeys.fromEnv()) {
    val httpClient = buildHttpClient()          // shared CIO client for all upstream calls
    val offClient = OpenFoodFactsClient(httpClient)
    val fsTokenManager = FatSecretTokenManager(httpClient, apiKeys)
    val fsClient = FatSecretClient(httpClient, fsTokenManager)
    val usdaClient = UsdaClient(httpClient, apiKeys.usdaApiKey)
    val foodService = FoodService(offClient, fsClient, usdaClient)

    configureSerialization()
    configureStatusPages()
    configureCallLogging()
    configureRouting(foodService)
}

fun buildHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
    }
}
```

---

## Dockerfile

```dockerfile
FROM gradle:8.7-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Use the `shadow` Gradle plugin to produce a fat JAR:
```kotlin
// build.gradle.kts
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.zenthek.foodapi.ApplicationKt"
    }
}
```

---

## Health check endpoint

Add a simple health check for Cloud Run liveness probes:

```
GET /health
→ 200 OK  { "status": "ok" }
```

---

## Future extensions (not in scope now)

The following are **explicitly out of scope** for the initial implementation but the architecture should support them:

### Caching
- Add Redis (e.g., via `lettuce` or `jedis`) as an optional layer in `FoodService`
- Barcode lookup results: 24h TTL
- Search results: 1h TTL
- Cache key: `"barcode:{barcode}"`, `"search:{query}:{page}:{pageSize}"`

### Authentication
- Add Ktor `Authentication` plugin
- Validate Supabase JWT: check `iss` claim = Supabase project URL, verify signature with Supabase JWT secret
- Return `401` for missing/invalid tokens

### Rate limiting
- Add per-IP rate limiting middleware if the API is exposed publicly
- Use a token bucket or sliding window algorithm

---

## Mobile app integration

The mobile app (`core:network` module) needs a new `FoodApiService` that calls **this backend** instead of the three upstream APIs directly.

**Base URL:** Configure via environment/build config (e.g., `https://food-api-xxx.run.app`)

### App-side request examples

```
GET https://food-api-xxx.run.app/food/search?q=chicken+breast&page=0&pageSize=25
GET https://food-api-xxx.run.app/food/barcode/0737628064502
GET https://food-api-xxx.run.app/health
```

The `FoodItem` JSON schema returned by this API is intentionally identical to the `FoodItem` domain model in the app — no additional mapping needed beyond JSON deserialization.

---

## Checklist before deployment

- [ ] All three upstream API credentials set as Cloud Run env vars
- [ ] `FATSECRET_CLIENT_ID` and `FATSECRET_CLIENT_SECRET` obtained from developer.fatsecret.com
- [ ] `USDA_API_KEY` obtained from fdc.nal.usda.gov/api-key-signup
- [ ] `GET /health` returns 200
- [ ] `GET /food/search?q=milk` returns results from at least one source
- [ ] `GET /food/barcode/0737628064502` returns Horizon Organic milk (OFF product)
- [ ] FatSecret token refresh works (check logs after 24h or force expiry in test)
- [ ] All nutrient values are in correct units (kcal, g, mg — not mixed)
- [ ] `sodium_100g` from OFF is multiplied by 1000 (g → mg)
- [ ] FatSecret `serving` field handles both object and array JSON shapes
- [ ] Invalid barcode (letters) returns 400, not 500
- [ ] All three sources failing returns 502, not 500
