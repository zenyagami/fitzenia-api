package com.zenthek.coach.agent.tools

import com.zenthek.coach.agent.safety.OutputSanitizer
import com.zenthek.coach.persistence.NotesGateway
import com.zenthek.coach.rag.HybridRetriever
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class CoachToolRunner(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    private val bearerToken: String,
    val userLocalDate: LocalDate,
    private val hybridRetriever: HybridRetriever,
    private val notesGateway: NotesGateway? = null,
    private val userId: String? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CoachToolRunner::class.java)
        private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val MAX_RESULT_CHARS = 8 * 1024
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun run(toolName: String, argsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return when (toolName) {
            "getUserProfile"     -> getUserProfile()
            "getUserGoal"        -> getUserGoal()
            "getCurrentTargets"  -> getCurrentTargets()
            "getTodayMacros"     -> getTodayMacros()
            "getRecentWeight"    -> getRecentWeight(days = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 14)
            "getRecentSteps"     -> getRecentSteps(days = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 14)
            "getCurrentPhase"    -> getCurrentPhase()
            "getUserCoachNotes"   -> getUserCoachNotes()
            "writeUserCoachNote" -> writeUserCoachNote(
                category = args["category"]?.jsonPrimitive?.content ?: "other",
                note     = args["note"]?.jsonPrimitive?.content ?: "",
            )
            "getWeightTrend"     -> getWeightTrend(weeks = args["weeks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4)
            "getDiaryForDate"    -> getDiaryForDate(
                date = args["date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: userLocalDate.format(ISO_DATE)
            )
            "searchDiary"        -> searchDiary(
                query = args["query"]?.jsonPrimitive?.content ?: "",
                days  = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 365,
                limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50,
            )
            "getBodyMeasurements" -> getBodyMeasurements(days = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 180)
            "getPastPhases"      -> getPastPhases(limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5)
            "searchKnowledgeBase" -> searchKnowledgeBase(
                query    = args["query"]?.jsonPrimitive?.content ?: "",
                sections = args["sections"]?.jsonPrimitive?.content,
            )
            else -> """{"error":"unknown_tool","name":"$toolName"}"""
        }
    }

    // Public pre-fetch shortcuts used by ChatRoutes before starting the LLM turn.
    suspend fun getCurrentTargets(): String {
        val raw = postgrestGet(
            "calorie_target",
            "select=target_kcal,target_min_kcal,target_max_kcal,protein_target_g,carbs_target_g,fat_target_g," +
                "tdee_kcal,bmr_kcal,tdee_mode,tdee_confidence,applied_pace_tier" +
                "&order=last_modified_at.desc&limit=1",
        )
        return if (raw.trim() == "[]") """{"targets":[],"count":0}""" else raw
    }

    suspend fun getUserProfile(): String {
        val row = postgrestGetArray(
            "user_profile",
            "select=name,sex,birth_date,height_cm&limit=1",
        ).firstOrNull() ?: return """{"profile":null}"""
        // Compute age server-side — LLMs are unreliable at date arithmetic.
        val age: Long? = row["birth_date"]?.jsonPrimitive?.content?.let { birthDate ->
            runCatching {
                ChronoUnit.YEARS.between(LocalDate.parse(birthDate.take(10), ISO_DATE), userLocalDate)
            }.getOrNull()
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("name",      row["name"] ?: JsonNull)
            put("sex",       row["sex"] ?: JsonNull)
            put("height_cm", row["height_cm"] ?: JsonNull)
            put("age",       age?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }

    suspend fun getUserGoal(): String {
        val raw = postgrestGet(
            "user_goal",
            "select=goal_direction,target_phase,goal_weight_kg,pace_tier,activity_level," +
                "body_fat_percent,protein_preference,lifting_experience,adaptive_tdee_enabled" +
                "&order=last_modified_at.desc&limit=1",
        )
        return if (raw.trim() == "[]") """{"goal":null}""" else raw
    }

    suspend fun getTodayMacros(): String {
        val dateStr = userLocalDate.format(ISO_DATE)
        val entries = postgrestGetArray(
            "diary_entry",
            "select=calories_kcal,protein_g,carbs_g,fat_g&date=eq.$dateStr",
        )
        val sumKcal    = entries.sumOf { it["calories_kcal"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }
        val sumProtein = entries.sumOf { it["protein_g"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }
        val sumCarbs   = entries.sumOf { it["carbs_g"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }
        val sumFat     = entries.sumOf { it["fat_g"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("date", dateStr)
            put("consumed_kcal",      sumKcal.toInt())
            put("consumed_protein_g", sumProtein.toInt())
            put("consumed_carbs_g",   sumCarbs.toInt())
            put("consumed_fat_g",     sumFat.toInt())
            put("items_logged",       entries.size)
        })
    }

    private suspend fun getRecentWeight(days: Int): String {
        val start = userLocalDate.minusDays(days.toLong()).format(ISO_DATE)
        val end   = userLocalDate.format(ISO_DATE)
        val raw = postgrestGet(
            "weight_entry",
            "select=date,weight_kg,body_fat_percent" +
                "&is_deleted=eq.false&date=gte.$start&date=lte.$end&order=date.desc",
        )
        return if (raw.trim() == "[]") """{"entries":[],"count":0,"period_days":$days}""" else raw
    }

    private suspend fun getRecentSteps(days: Int): String {
        val start = userLocalDate.minusDays(days.toLong()).format(ISO_DATE)
        val end   = userLocalDate.format(ISO_DATE)
        val entries = postgrestGetArray(
            "daily_activity",
            "select=date,steps_count&is_deleted=eq.false&date=gte.$start&date=lte.$end&order=date.desc",
        )
        // No unique (user_id, date) constraint on daily_activity — collapse to one figure per date.
        val byDate = entries
            .mapNotNull { row ->
                val date = row["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val steps = row["steps_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                date to steps
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.max() }
            .toSortedMap(compareByDescending { it })

        if (byDate.isEmpty()) return """{"entries":[],"count":0,"period_days":$days}"""
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("period_days", days)
            put("count", byDate.size)
            putJsonArray("entries") {
                byDate.forEach { (date, steps) ->
                    addJsonObject { put("date", date); put("steps", steps) }
                }
            }
        })
    }

    private suspend fun getCurrentPhase(): String {
        val rows = postgrestGetArray(
            "journey",
            "select=target_phase,goal_direction,pace_tier,started_at,goal_date," +
                "target_weight_kg,target_body_fat_percent,start_weight_kg,start_body_fat_percent" +
                "&ended_at=is.null&is_deleted=eq.false&order=started_at.desc&limit=1",
        )
        val row = rows.firstOrNull() ?: return """{"phase":null}"""
        val startedAt = row["started_at"]?.jsonPrimitive?.content
        val daysIntoPhase: Long? = startedAt?.let {
            runCatching {
                val startDate = LocalDate.parse(it.take(10), ISO_DATE)
                ChronoUnit.DAYS.between(startDate, userLocalDate)
            }.getOrNull()
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            row.forEach { (k, v) -> put(k, v) }
            put("days_into_phase", daysIntoPhase?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }

    private suspend fun getUserCoachNotes(): String {
        val raw = postgrestGet("coach_user_note", "select=id,category,note,created_at&order=created_at.desc&limit=10")
        return if (raw.trim() == "[]") """{"notes":[],"count":0}""" else raw
    }

    private suspend fun writeUserCoachNote(category: String, note: String): String {
        val gateway = notesGateway ?: return """{"error":"write_not_configured"}"""
        val uid = userId ?: return """{"error":"write_not_configured"}"""
        val validCategories = setOf("preference", "restriction", "goal_context", "other")
        if (category !in validCategories) {
            return """{"error":"invalid_category","valid":["preference","restriction","goal_context","other"]}"""
        }
        if (note.isBlank()) return """{"error":"note_empty"}"""
        if (note.length > 500) return """{"error":"note_too_long","max":500}"""
        if (OutputSanitizer.isMostlyPii(note)) return """{"error":"mostly_pii"}"""
        val stripped = OutputSanitizer.stripPii(note)
        if (stripped.isBlank()) return """{"error":"note_empty_after_strip"}"""
        val id = gateway.writeNote(uid, category, stripped) ?: return """{"error":"write_failed"}"""
        return """{"id":"$id"}"""
    }

    suspend fun getWeightTrend(weeks: Int): String {
        val days  = (weeks * 7).toLong()
        val start = userLocalDate.minusDays(days).format(ISO_DATE)
        val end   = userLocalDate.format(ISO_DATE)
        val entries = postgrestGetArray(
            "weight_entry",
            "select=date,weight_kg,body_fat_percent&is_deleted=eq.false&date=gte.$start&date=lte.$end&order=date.asc",
        )
        val weights = entries.mapNotNull { it["weight_kg"]?.jsonPrimitive?.content?.toDoubleOrNull() }
        if (weights.isEmpty()) return """{"weeks":$weeks,"entries":0}"""

        // EMA: α = 2/(n+1), applied left-to-right (oldest first)
        val alpha = 2.0 / (weights.size + 1)
        val ema = weights.drop(1).fold(weights.first()) { acc, w -> alpha * w + (1.0 - alpha) * acc }
        val slopePerWeek = if (weeks > 0) (weights.last() - weights.first()) / weeks else 0.0

        val bodyFats = entries.mapNotNull { it["body_fat_percent"]?.jsonPrimitive?.content?.toDoubleOrNull() }

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("weeks",              weeks)
            put("entries",            weights.size)
            put("start_weight_kg",    weights.first())
            put("end_weight_kg",      weights.last())
            put("ema_kg",             Math.round(ema * 10) / 10.0)
            put("slope_kg_per_week",  Math.round(slopePerWeek * 100) / 100.0)
            if (bodyFats.isNotEmpty()) {
                val bfSlopePerWeek = if (weeks > 0) (bodyFats.last() - bodyFats.first()) / weeks else 0.0
                put("body_fat_entries",             bodyFats.size)
                put("start_body_fat_percent",       bodyFats.first())
                put("end_body_fat_percent",         bodyFats.last())
                put("body_fat_slope_per_week",      Math.round(bfSlopePerWeek * 100) / 100.0)
            }
        })
    }

    private suspend fun getDiaryForDate(date: String): String {
        val raw = postgrestGet(
            "diary_entry",
            "select=meal_type,food_name_snapshot,calories_kcal,protein_g,carbs_g,fat_g" +
                "&date=eq.$date&order=meal_type,created_at.asc",
        )
        return if (raw.trim() == "[]") """{"date":"$date","entries":[],"count":0}""" else raw
    }

    /**
     * Free-text search across the user's whole diary history.
     *
     * Matches BOTH `food_name_snapshot` and `my_meal_name_snapshot`: a saved My Meal is not
     * guaranteed to mirror its name into the food column on every client version, so a
     * single-column ILIKE would silently miss those rows.
     *
     * The query is reduced to letter/digit/hyphen tokens and rejoined with the PostgREST
     * `ilike` wildcard (`*`). That keeps the filter free of the `,` `.` `(` `)` characters
     * that would break `or=(...)` syntax, and free of raw spaces that would need escaping.
     * Multi-word queries therefore match in order: "chicken broccoli" also finds
     * "chicken and broccoli", but not "broccoli chicken".
     */
    private suspend fun searchDiary(query: String, days: Int, limit: Int): String {
        val tokens = query.split(Regex("""[^\p{L}\p{N}-]+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return """{"error":"query_empty"}"""
        val pattern = tokens.joinToString(separator = "*", prefix = "*", postfix = "*")

        val safeDays  = days.coerceIn(1, 3650)
        val safeLimit = limit.coerceIn(1, 100)
        val start = userLocalDate.minusDays(safeDays.toLong()).format(ISO_DATE)
        val end   = userLocalDate.format(ISO_DATE)

        val rows = postgrestGetArray(
            "diary_entry",
            "select=date,meal_type,food_name_snapshot,my_meal_name_snapshot," +
                "calories_kcal,protein_g,carbs_g,fat_g" +
                "&date=gte.$start&date=lte.$end" +
                "&or=(food_name_snapshot.ilike.$pattern,my_meal_name_snapshot.ilike.$pattern)" +
                "&order=date.desc&limit=$safeLimit",
        )

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("query", query)
            put("period_days", safeDays)
            put("count", rows.size)
            put("last_eaten_on", rows.firstOrNull()?.str("date")?.let { JsonPrimitive(it) } ?: JsonNull)
            putJsonArray("matches") {
                rows.forEach { row ->
                    addJsonObject {
                        put("date", row.str("date")?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("meal", prettyEnum(row.str("meal_type")))
                        put("name", (row.str("my_meal_name_snapshot") ?: row.str("food_name_snapshot"))
                            ?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("kcal", row.num("calories_kcal")?.let { JsonPrimitive(it.toInt()) } ?: JsonNull)
                        put("protein_g", row.num("protein_g")?.let { JsonPrimitive(it.toInt()) } ?: JsonNull)
                        put("carbs_g",   row.num("carbs_g")?.let { JsonPrimitive(it.toInt()) } ?: JsonNull)
                        put("fat_g",     row.num("fat_g")?.let { JsonPrimitive(it.toInt()) } ?: JsonNull)
                    }
                }
            }
        })
    }

    /** Latest / earliest / delta per circumference metric over the window. */
    private suspend fun getBodyMeasurements(days: Int): String {
        val safeDays = days.coerceIn(1, 3650)
        val start = userLocalDate.minusDays(safeDays.toLong()).format(ISO_DATE)
        val end   = userLocalDate.format(ISO_DATE)

        val byMetric = postgrestGetArray(
            "body_measurement",
            "select=date,metric,value_cm&is_deleted=eq.false&date=gte.$start&date=lte.$end&order=date.asc",
        ).mapNotNull { row ->
            val metric = row.str("metric") ?: return@mapNotNull null
            val date   = row.str("date") ?: return@mapNotNull null
            val value  = row.num("value_cm") ?: return@mapNotNull null
            Triple(metric, date, value)
        }.groupBy { it.first }

        if (byMetric.isEmpty()) return """{"period_days":$safeDays,"metrics":[],"count":0}"""

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("period_days", safeDays)
            put("count", byMetric.size)
            putJsonArray("metrics") {
                byMetric.forEach { (metric, entries) ->
                    val sorted = entries.sortedBy { it.second }
                    val first = sorted.first()
                    val last  = sorted.last()
                    addJsonObject {
                        put("metric",      metric.lowercase().replace('_', ' '))
                        put("latest_cm",   last.third)
                        put("latest_date", last.second)
                        put("first_cm",    first.third)
                        put("first_date",  first.second)
                        put("change_cm",   Math.round((last.third - first.third) * 10) / 10.0)
                        put("entries",     sorted.size)
                    }
                }
            }
        })
    }

    /** Completed journeys, newest-first, with duration and rate derived server-side. */
    private suspend fun getPastPhases(limit: Int): String {
        val safeLimit = limit.coerceIn(1, 20)
        val rows = postgrestGetArray(
            "journey",
            "select=target_phase,goal_direction,pace_tier,started_at,ended_at," +
                "start_weight_kg,end_weight_kg,start_body_fat_percent,end_body_fat_percent" +
                "&ended_at=not.is.null&is_deleted=eq.false&order=ended_at.desc&limit=$safeLimit",
        )
        if (rows.isEmpty()) return """{"phases":[],"count":0}"""

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("count", rows.size)
            putJsonArray("phases") {
                rows.forEach { row ->
                    val startedAt = row.str("started_at")
                    val endedAt   = row.str("ended_at")
                    val weeks: Double? = if (startedAt != null && endedAt != null) {
                        runCatching {
                            val d = ChronoUnit.DAYS.between(
                                LocalDate.parse(startedAt.take(10), ISO_DATE),
                                LocalDate.parse(endedAt.take(10), ISO_DATE),
                            )
                            Math.round(d / 7.0 * 10) / 10.0
                        }.getOrNull()
                    } else null
                    val startW = row.num("start_weight_kg")
                    val endW   = row.num("end_weight_kg")
                    val deltaW = if (startW != null && endW != null) {
                        Math.round((endW - startW) * 10) / 10.0
                    } else null

                    addJsonObject {
                        put("target_phase",   prettyEnum(row.str("target_phase")))
                        put("goal_direction", prettyEnum(row.str("goal_direction")))
                        put("pace_tier",      prettyEnum(row.str("pace_tier")))
                        put("started_at",     startedAt?.take(10)?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("ended_at",       endedAt?.take(10)?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("duration_weeks", weeks?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("start_weight_kg",   startW?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("end_weight_kg",     endW?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("weight_change_kg",  deltaW?.let { JsonPrimitive(it) } ?: JsonNull)
                        put(
                            "kg_per_week",
                            if (deltaW != null && weeks != null && weeks > 0.0) {
                                JsonPrimitive(Math.round(deltaW / weeks * 100) / 100.0)
                            } else JsonNull,
                        )
                        put("start_body_fat_percent", row.num("start_body_fat_percent")?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("end_body_fat_percent",   row.num("end_body_fat_percent")?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                }
            }
        })
    }

    /** `MORNING_SNACK` -> `morning snack`; the prompt forbids echoing raw enum values. */
    private fun prettyEnum(raw: String?): JsonElement =
        raw?.lowercase()?.replace('_', ' ')?.let { JsonPrimitive(it) } ?: JsonNull

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()

    private suspend fun searchKnowledgeBase(query: String, sections: String?): String {
        if (query.isBlank()) return """{"chunks":[]}"""
        val sectionSet = sections?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
        val chunks = hybridRetriever.retrieve(query)
        val filtered = if (sectionSet != null) chunks.filter { it.section in sectionSet } else chunks
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            putJsonArray("chunks") {
                filtered.take(6).forEach { chunk ->
                    addJsonObject {
                        put("source",  chunk.docId)
                        put("section", chunk.section)
                        put("score",   chunk.score)
                        put("text",    chunk.text)
                    }
                }
            }
        })
    }

    private suspend fun postgrestGet(table: String, params: String): String {
        return try {
            val response = httpClient.get("$supabaseUrl/rest/v1/$table?$params") {
                header("apikey",        supabaseAnonKey)
                header("Authorization", "Bearer $bearerToken")
                header("Accept",        "application/json")
            }
            if (response.status.isSuccess()) {
                response.body<String>().take(MAX_RESULT_CHARS)
            } else {
                """{"error":"query_failed","status":${response.status.value}}"""
            }
        } catch (e: Exception) {
            log.warn("[COACH-TOOL] postgrestGet failed table={}", table, e)
            """{"error":"request_failed"}"""
        }
    }

    private suspend fun postgrestGetArray(table: String, params: String): List<JsonObject> {
        return try {
            json.parseToJsonElement(postgrestGet(table, params))
                .jsonArray
                .mapNotNull { it as? JsonObject }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
