package com.zenthek.coach.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.auth.requireBearerAccessToken
import com.zenthek.coach.agent.CoachAgentFactory
import com.zenthek.coach.agent.CoachFrame
import com.zenthek.coach.agent.CoachPromptVersion
import com.zenthek.coach.agent.SystemPromptV1
import com.zenthek.coach.agent.safety.ClassifyResult
import com.zenthek.coach.agent.safety.HardBlockClassifier
import com.zenthek.coach.agent.safety.InputSanitizer
import com.zenthek.coach.agent.safety.OutputSanitizer
import com.zenthek.coach.agent.tools.CoachToolRunner
import com.zenthek.coach.auth.CoachPlan
import com.zenthek.coach.auth.PremiumGate
import com.zenthek.coach.compaction.ConversationCompactor
import com.zenthek.coach.config.CoachModels
import com.zenthek.coach.persistence.BudgetGateway
import com.zenthek.coach.persistence.ChatGateway
import com.zenthek.coach.persistence.ChatSummaryRow
import com.zenthek.coach.persistence.MessageDetailRow
import com.zenthek.coach.persistence.NotesGateway
import com.zenthek.coach.rag.HybridRetriever
import com.zenthek.coach.rag.RetrievedChunk
import com.zenthek.coach.stream.BudgetExceededPayload
import com.zenthek.coach.stream.ChatCreatedPayload
import com.zenthek.coach.stream.CitationPayload
import com.zenthek.coach.stream.DonePayload
import com.zenthek.coach.stream.SafetyPayload
import com.zenthek.coach.stream.SseErrorPayload
import com.zenthek.coach.stream.TitlePayload
import com.zenthek.coach.stream.ToolDonePayload
import com.zenthek.coach.stream.ToolStartPayload
import com.zenthek.coach.stream.TokenPayload
import com.zenthek.coach.stream.TokenUsage
import com.zenthek.coach.stream.sendPing as rawSendPing
import com.zenthek.coach.stream.sendSseEvent as rawSendSseEvent
import com.zenthek.routes.RateLimitNames
import io.ktor.client.HttpClient
import io.ktor.http.CacheControl
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.zenthek.coach.routes.ChatRoutes")
private val inFlight = ConcurrentHashMap<String, Unit>()
private val sseJson = Json { ignoreUnknownKeys = true }
private val classifier = HardBlockClassifier()

// ~20k token input cap (heuristic: 4 chars/token)
private const val INPUT_CHAR_CAP = 80_000
private const val STREAM_TIMEOUT_MS = 120_000L
// The whole turn is buffered (the real `token` event is only emitted after generation
// completes), so an existing-chat send writes NO bytes for the full turn — RAG embed + a
// Gemini stream + a possible Pro escalation (a second full pass). Ktor's Netty engine closes
// the socket if the response goes unwritten for `responseWriteTimeoutSeconds` (default 10s),
// which Cloud Run surfaces to the client as a 503 "connection to the instance had an error".
// A periodic comment-only ping (SSE spec: lines starting with ":" are ignored by EventSource
// clients) keeps bytes flowing. MUST be < the write timeout, and the first ping is sent
// immediately (see pingJob) so the connection never goes silent from t=0.
private const val SSE_PING_INTERVAL_MS = 5_000L
// Flat token estimate for the system prompt + tool descriptions + KB envelope,
// added to the (chars/4) estimate of the message + history when reserving budget.
// Over-reserving is safe — reconcile corrects to actuals at turn-end.
private const val BUDGET_SYSTEM_PROMPT_TOKEN_ESTIMATE = 1_500
// Chunks below this hybrid score are noise (e.g. recipes returned for diary/personal queries).
private const val MIN_KB_SCORE = 0.10

// The system prompt asks the model to cite grounding inline as "(KB: doc-id)" for retrieval
// faithfulness. Those raw doc-ids are internal — strip them before display/persistence.
// Structured `citation` SSE events carry the same source attribution for the client instead.
// Tolerant of the model's formatting drift: "( KB:nutrition/x)", "(KB: a/b, c/d)", any case.
private val KB_CITATION_REGEX = Regex("""\(\s*KB\s*:[^)]*\)""", RegexOption.IGNORE_CASE)

private fun stripKbCitations(text: String): String =
    text.replace(KB_CITATION_REGEX, "")
        .replace(Regex("""[ \t]+([.,;:!?])"""), "$1") // drop space left before punctuation
        .replace(Regex("""[ \t]{2,}"""), " ")          // collapse doubled spaces
        .replace(Regex("""[ \t]+\n"""), "\n")           // trailing spaces before newline
        .trim()

// The coach's answers are Markdown rendered on mobile, where h1–h3 headers look oversized.
// Demote every ATX header a fixed number of levels (clamped at h6) so the app renders them
// smaller while relative hierarchy is preserved. Deterministic — independent of how well the
// model follows formatting guidance. Only leading `#`s followed by whitespace are treated as
// headers, so "#1" and mid-line "#" are left untouched.
private const val HEADER_DEMOTE_LEVELS = 2

private val ATX_HEADER_REGEX = Regex("""^(#{1,6})(?=\s)""", RegexOption.MULTILINE)

private fun demoteMarkdownHeaders(text: String): String =
    ATX_HEADER_REGEX.replace(text) { match ->
        "#".repeat(minOf(match.value.length + HEADER_DEMOTE_LEVELS, 6))
    }

// Maps an internal tool id to a stable, machine-readable status key the client localizes
// into a "working" indicator (e.g. "reading_diary" -> "Analyzing your diary…"). These keys
// are part of the client wire contract — keep them and docs/AI_COACH_CLIENT.md in sync.
// Unknown / future tools fall back to the generic "thinking".
private fun publicToolStatusName(internalName: String): String = when (internalName) {
    "getUserProfile"      -> "reading_profile"
    "getUserGoal"         -> "reading_goal"
    "getCurrentTargets"   -> "checking_targets"
    "getTodayMacros"      -> "checking_today"
    "getRecentWeight",
    "getWeightTrend"      -> "analyzing_weight"
    "getRecentSteps"      -> "checking_activity"
    "getCurrentPhase"     -> "reading_plan"
    "getDiaryForDate"     -> "reading_diary"
    "getUserCoachNotes"   -> "reading_notes"
    "writeUserCoachNote"  -> "saving_note"
    "searchKnowledgeBase" -> "searching_knowledge"
    else                  -> "thinking"
}

private const val GENERIC_FALLBACK =
    "I'm sorry, I wasn't able to generate a safe response for that. Please try rephrasing, or ask a general nutrition or fitness question."

@Serializable
data class SendMessageRequest(
    val content: String,
    val locale: String,
    val userTz: String? = null,
    /**
     * Model selection: `auto` (default — Lite with automatic Pro escalation),
     * `fast` (Lite only, escalation triggers ignored; cheapest), or `pro`
     * (straight to the Pro model — ~6× the credit burn; see docs/AI_COACH.md).
     * Safety hard-blocks apply in every mode.
     */
    val mode: String? = null,
)

fun Application.configureCoachRouting(
    chatGateway: ChatGateway,
    premiumGate: PremiumGate,
    agentFactory: CoachAgentFactory,
    hybridRetriever: HybridRetriever,
    httpClient: HttpClient,
    supabaseUrl: String,
    supabaseAnonKey: String,
    notesGateway: NotesGateway,
    compactor: ConversationCompactor,
    budgetGateway: BudgetGateway,
) {
    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        authenticate(SUPABASE_AUTH_PROVIDER) {
            rateLimit(RateLimitName(RateLimitNames.COACH_MESSAGE)) {
                post("/api/coach/messages") {
                    processMessage(call, chatIdFromPath = null, chatGateway, premiumGate, agentFactory, hybridRetriever, httpClient, supabaseUrl, supabaseAnonKey, notesGateway, compactor, budgetGateway)
                }
                route("/api/coach/chats/{chatId}") {
                    post("/messages") {
                        val chatId = call.parameters["chatId"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Missing chatId")
                            )
                        processMessage(call, chatIdFromPath = chatId, chatGateway, premiumGate, agentFactory, hybridRetriever, httpClient, supabaseUrl, supabaseAnonKey, notesGateway, compactor, budgetGateway)
                    }
                }
            }

            rateLimit(RateLimitName(RateLimitNames.COACH_MANAGEMENT)) {
                get("/api/coach/chats") {
                    premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val chats: List<ChatSummaryRow> = chatGateway.listChats(user.userId)
                    call.respond(HttpStatusCode.OK, chats)
                }
                get("/api/coach/chats/{chatId}/messages") {
                    premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val chatId = call.parameters["chatId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId"))
                    val messages: List<MessageDetailRow> = chatGateway.getMessagesFull(chatId, user.userId)
                    call.respond(HttpStatusCode.OK, messages)
                }
                delete("/api/coach/chats/{chatId}") {
                    premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val chatId = call.parameters["chatId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId"))
                    val archived = chatGateway.archiveChat(chatId, user.userId)
                    if (!archived) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
                        return@delete
                    }
                    chatGateway.deleteChatMessages(chatId, user.userId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private suspend fun processMessage(
    call: ApplicationCall,
    chatIdFromPath: String?,
    chatGateway: ChatGateway,
    premiumGate: PremiumGate,
    agentFactory: CoachAgentFactory,
    hybridRetriever: HybridRetriever,
    httpClient: HttpClient,
    supabaseUrl: String,
    supabaseAnonKey: String,
    notesGateway: NotesGateway,
    compactor: ConversationCompactor,
    budgetGateway: BudgetGateway,
) {
    // Premium check happens before SSE so StatusPages can map ForbiddenException → 403.
    // The returned plan picks the credit cap (trial entitlements get the reduced one).
    val plan = premiumGate.requirePremium(call)

    val user = call.requireAuthenticatedUser()
    val bearerToken = call.requireBearerAccessToken()
    val body = call.receive<SendMessageRequest>()
    val content = body.content.trim()
    if (content.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "content must not be blank"))
        return
    }

    // Reject oversized input before opening the SSE stream.
    if (content.length > INPUT_CHAR_CAP) {
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "INPUT_TOO_LONG", "message" to "Message exceeds the maximum input length")
        )
        return
    }

    // Model selector — validated before the SSE stream opens.
    val mode = CoachMode.fromWire(body.mode) ?: CoachMode.AUTO.also {
        log.warn("[COACH] no mode selected, default to Auto")
    }
    val proMode = mode == CoachMode.PRO

    val flightKey = "${user.userId}:${chatIdFromPath ?: "__new__"}"
    if (inFlight.putIfAbsent(flightKey, Unit) != null) {
        log.warn("[COACH] IN_FLIGHT conflict userId={} key={}", user.userId, flightKey)
        call.respond(
            HttpStatusCode.Conflict,
            mapOf("error" to "IN_FLIGHT", "message" to "A message is already being processed for this chat")
        )
        return
    }

    var lockReleased = false
    try {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val channel: ByteWriteChannel = this
            // Guards every write to the channel — the ping ticker below and the turn's own
            // sendSseEvent calls run on different coroutines and must never interleave bytes.
            val sseWriteMutex = Mutex()
            suspend fun sendSseEventGuarded(event: String, data: String) =
                sseWriteMutex.withLock { channel.rawSendSseEvent(event, data) }

            coroutineScope {
                val pingJob = launch {
                    // Ping first, THEN delay — flushes the response headers + a keepalive byte at
                    // t≈0 so the connection is never silent long enough to hit the write timeout,
                    // even before the pre-stream work (history, RAG, first LLM byte) produces output.
                    while (isActive) {
                        runCatching { sseWriteMutex.withLock { channel.rawSendPing() } }
                        delay(SSE_PING_INTERVAL_MS)
                    }
                }

            // Budget reservation lifecycle: settle each reservation exactly once.
            var reservationId: String? = null
            var budgetSettled = false
            // Set once the user's turn row is persisted (see below); declared here — rather than
            // inside the try block — so both the try body and the catch clause can roll it back.
            var userMsgRow: com.zenthek.coach.persistence.MessageRow? = null
            // Flipped true only once the assistant's reply is durably persisted. Guards a narrow
            // race: `done` can still throw (client disconnects at that exact instant) AFTER the
            // reply row is written — in that case the turn genuinely completed and must NOT be
            // rolled back, or a real answer would be orphaned with its question deleted out from
            // under it.
            var assistantPersisted = false
            // Set only when THIS request creates a brand-new chat (chatIdFromPath == null). If the
            // first turn then fails, the chat is a childless container that never should have
            // survived — rolled back alongside the message, below.
            var createdNewChatId: String? = null
            // Deletes the just-persisted user row (and, for a brand-new chat, the now-childless
            // chat container) on a turn failure (budget/LLM/stream) so the server never holds an
            // unanswered user message: a client re-pull would otherwise content-match it as
            // "delivered" and silently resurrect a locally-FAILED bubble as sent, with no reply and
            // no way to retry.
            suspend fun rollbackUserMessage() {
                if (assistantPersisted) return
                userMsgRow?.let { row ->
                    runCatching { chatGateway.deleteMessage(row.id, user.userId) }
                        .onFailure { e ->
                            log.warn("[COACH] user_msg_rollback_failed chatId={}", chatIdFromPath, e)
                        }
                }
                createdNewChatId?.let { newChatId ->
                    runCatching { chatGateway.deleteChat(newChatId, user.userId) }
                        .onFailure { e ->
                            log.warn("[COACH] new_chat_rollback_failed chatId={}", newChatId, e)
                        }
                }
            }
            try {
                // Lazy chat creation — emit chat_created before persisting the first message
                val chatId: String
                if (chatIdFromPath == null) {
                    val chatRow = chatGateway.createChat(user.userId, body.locale)
                    chatId = chatRow.id
                    createdNewChatId = chatId
                    sendSseEventGuarded(
                        "chat_created",
                        sseJson.encodeToString(
                            ChatCreatedPayload.serializer(),
                            ChatCreatedPayload(chatId = chatId, title = chatRow.title)
                        )
                    )
                } else {
                    chatId = chatIdFromPath
                }

                val requestId = java.util.UUID.randomUUID().toString()
                // Non-fatal: dev Supabase cold-starts can exceed 30s; proceed with empty history rather than failing.
                val rawHistory = runCatching { chatGateway.getMessages(chatId, limit = 50) }
                    .onFailure { e -> log.warn("[COACH] history_load_failed chatId={}", chatId, e) }
                    .getOrElse { emptyList() }
                    .filter { it.role in setOf("user", "assistant") }

                val isFirstTurn = rawHistory.isEmpty()

                val (history, summaryContext) = runCatching {
                    compactor.prepareHistory(chatId, user.userId, rawHistory)
                }.onFailure { e -> log.warn("[COACH] compaction_failed chatId={}", chatId, e) }
                    .getOrElse { com.zenthek.coach.compaction.PreparedHistory(rawHistory, null) }
                    .let { Pair(it.history, it.summaryContext) }

                // Persist the raw user message — non-fatal so the LLM turn still runs on save failure.
                // The row id is kept (see [userMsgRow] above) so a subsequent turn failure can roll
                // it back via rollbackUserMessage() instead of leaving an unanswered user row
                // server-side.
                userMsgRow = runCatching { chatGateway.insertMessage(chatId, user.userId, "user", content, requestId) }
                    .onFailure { e -> log.warn("[COACH] user_msg_persist_failed chatId={}", chatId, e) }
                    .getOrNull()

                // Input sanitization.
                val sanitized = InputSanitizer.sanitize(content)

                // Hard-block classifier + pre-LLM escalation signal.
                var classifierWantsEscalation = false
                when (val classifyResult = classifier.classify(sanitized, body.locale)) {
                    is ClassifyResult.Escalate -> {
                        // COMPLEX_REASONING: don't block, but flag the turn for a Pro retry.
                        log.info(
                            "[COACH-ESCALATE] classifier_signal userId={} class={} chatId={}",
                            user.userId, classifyResult.blockClass, chatId
                        )
                        classifierWantsEscalation = true
                    }
                    is ClassifyResult.HardBlock -> {
                        val safetyMsg = classifyResult.message
                        log.info(
                            "[COACH-SAFETY] hard_block userId={} class={} chatId={}",
                            user.userId, classifyResult.blockClass, chatId
                        )
                        // Persist canned assistant response with safety_action
                        val msgRow = chatGateway.insertMessage(
                            chatId = chatId,
                            userId = user.userId,
                            role = "assistant",
                            content = safetyMsg,
                            requestId = requestId,
                            safetyAction = "hard_block",
                        )
                        sendSseEventGuarded(
                            "safety",
                            sseJson.encodeToString(
                                SafetyPayload.serializer(),
                                SafetyPayload(action = "hard_block", message = safetyMsg)
                            )
                        )
                        sendSseEventGuarded(
                            "done",
                            sseJson.encodeToString(
                                DonePayload.serializer(),
                                DonePayload(
                                    chatId = chatId,
                                    messageId = msgRow.id,
                                    tokens = TokenUsage(input = 0, output = 0, cached = 0),
                                    model = "",
                                    escalated = false,
                                    mode = mode.wire,
                                )
                            )
                        )
                        return@coroutineScope
                    }
                    is ClassifyResult.Pass -> Unit // continue to LLM
                }

                // Atomic monthly budget: enforced before any LLM work. Hard-blocks
                // above return early and are never charged. The reservation is keyed on the
                // per-turn requestId, so a retried turn reuses its reservation (idempotent).
                val (capMessages, capCredits) = CoachModels.budgetCapsFor(isTrial = plan == CoachPlan.TRIAL)
                val nowUtc = LocalDate.now(ZoneOffset.UTC)
                val periodYyyymm = BudgetPeriod.currentYyyymm(nowUtc)
                val estimatedInputTokens =
                    ((sanitized.length + history.sumOf { it.content.length } + (summaryContext?.length ?: 0)) / 4) +
                        BUDGET_SYSTEM_PROMPT_TOKEN_ESTIMATE
                // auto/fast reserve Lite-weighted (turns start on the Lite model; an automatic
                // Pro escalation is corrected at reconcile — worst overshoot is one escalated
                // turn past the cap). mode=pro is known upfront, so it reserves Pro-weighted.
                val reservedOutputTokens =
                    if (proMode) CoachModels.RESERVED_OUTPUT_TOKENS_PRO else CoachModels.RESERVED_OUTPUT_TOKENS
                val estimatedCredits = if (proMode) {
                    estimatedInputTokens.toLong() * CoachModels.WEIGHT_PRO_INPUT +
                        reservedOutputTokens.toLong() * CoachModels.WEIGHT_PRO_OUTPUT
                } else {
                    estimatedInputTokens.toLong() * CoachModels.WEIGHT_LITE_INPUT +
                        reservedOutputTokens.toLong() * CoachModels.WEIGHT_LITE_OUTPUT
                }
                // Fail-open: a transient budget RPC error must not block a paying premium user.
                val reserveResult = runCatching {
                    budgetGateway.reserve(
                        userId           = user.userId,
                        requestId        = requestId,
                        period           = periodYyyymm,
                        inputMax         = estimatedInputTokens,
                        outputMax        = reservedOutputTokens,
                        estimatedCredits = estimatedCredits,
                        capMessages      = capMessages,
                        capCredits       = capCredits,
                    )
                }.onFailure { e ->
                    log.warn("[COACH-BUDGET] reserve_failed userId={} chatId={}, proceeding uncharged", user.userId, chatId, e)
                }.getOrNull()

                if (reserveResult != null && !reserveResult.allowed) {
                    val nextMonth = nowUtc.withDayOfMonth(1).plusMonths(1)
                    val resetAtIso = BudgetPeriod.nextResetIso(nowUtc)
                    val resetLabel = "${nextMonth.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} 1"
                    log.info(
                        "[COACH-BUDGET] budget_exceeded userId={} chatId={} reason={}",
                        user.userId, chatId, reserveResult.reason
                    )
                    sendSseEventGuarded(
                        "error",
                        sseJson.encodeToString(
                            BudgetExceededPayload.serializer(),
                            BudgetExceededPayload(
                                code = "BUDGET_EXCEEDED",
                                resetAt = resetAtIso,
                                message = if (plan == CoachPlan.TRIAL) {
                                    "You've reached your trial coach limit. Subscribe to unlock the full monthly allowance."
                                } else {
                                    "You've reached this month's coach limit. Resets $resetLabel."
                                },
                                plan = plan.wire,
                            )
                        )
                    )
                    rollbackUserMessage()
                    return@coroutineScope
                }
                reservationId = reserveResult?.reservationId

                // Per-request tool runner: bearer token is per-request and never cached.
                val userTz = body.userTz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: ZoneId.of(CoachModels.USER_TZ_FALLBACK)
                val userLocalDate = LocalDate.now(userTz)
                val toolRunner = CoachToolRunner(
                    httpClient      = httpClient,
                    supabaseUrl     = supabaseUrl,
                    supabaseAnonKey = supabaseAnonKey,
                    bearerToken     = bearerToken,
                    userLocalDate   = userLocalDate,
                    hybridRetriever = hybridRetriever,
                    notesGateway    = notesGateway,
                    userId          = user.userId,
                )

                // Pre-fetch the user's core stats in parallel; non-fatal.
                // Profile/goal/weight-trend are pre-loaded every turn so identity questions
                // ("what's my weight / ideal weight") never depend on the tool loop.
                // On the first turn only, also inject the user's last 10 coach notes.
                val userContext: String? = runCatching {
                    coroutineScope {
                        val t = async { toolRunner.getCurrentTargets() }
                        val m = async { toolRunner.getTodayMacros() }
                        val p = async { toolRunner.getUserProfile() }
                        val g = async { toolRunner.getUserGoal() }
                        val w = async { toolRunner.getWeightTrend(weeks = 4) }
                        val n = if (history.isEmpty()) async { notesGateway.getUserNotes(user.userId, limit = 10) } else null
                        val prefetched = listOf(
                            "getCurrentTargets" to t.await(),
                            "getTodayMacros"    to m.await(),
                            "getUserProfile"    to p.await(),
                            "getUserGoal"       to g.await(),
                            "getWeightTrend"    to w.await(),
                        )
                        val notes = n?.await() ?: emptyList()
                        // Defense in depth: discard pre-fetched context if it contains closing tags.
                        if (prefetched.any { (_, v) -> v.contains("</tool_output>") || v.contains("</kb_context>") }) {
                            error("pre-fetch result contained injection tag")
                        }
                        buildString {
                            prefetched.forEachIndexed { index, (name, value) ->
                                if (index > 0) appendLine()
                                append("""<tool_output name="$name" format="json">""")
                                appendLine(); append(value)
                                appendLine(); append("</tool_output>")
                            }
                            if (notes.isNotEmpty()) {
                                val notesJson = buildJsonArray {
                                    notes.forEach { nr ->
                                        add(buildJsonObject {
                                            put("id", nr.id)
                                            put("note", nr.note)
                                            put("category", nr.category)
                                            put("created_at", nr.createdAt)
                                        })
                                    }
                                }.toString()
                                if (!notesJson.contains("</tool_output>") && !notesJson.contains("</kb_context>")) {
                                    appendLine()
                                    append("""<tool_output name="getUserCoachNotes" format="json">""")
                                    appendLine(); append(notesJson)
                                    appendLine(); append("</tool_output>")
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    log.warn("[COACH] pre-fetch failed userId={}", user.userId, e)
                }.getOrNull()

                // RAG retrieval is non-fatal; an empty list degrades gracefully.
                val retrievedChunks: List<RetrievedChunk> = hybridRetriever.retrieve(sanitized)

                // Build kb_context envelope; reject injection tags + low-relevance noise.
                // Scores below MIN_KB_SCORE are irrelevant matches (e.g. recipes returned for diary queries)
                // that cause the model to pivot away from the tool result.
                val safeChunks = retrievedChunks
                    .filter { !it.text.contains("</kb_context>") }
                    .filter { it.score >= MIN_KB_SCORE }
                val kbContext: String? = if (safeChunks.isNotEmpty()) {
                    val items = safeChunks.joinToString(",\n  ") { chunk ->
                        val scoreStr = String.format(Locale.ROOT, "%.4f", chunk.score)
                        """{"source":"${chunk.docId}","score":$scoreStr,"text":${sseJson.encodeToString(chunk.text)}}"""
                    }
                    "<kb_context format=\"json\">\n[$items]\n</kb_context>"
                } else null

                // Build citations JSON for persistence
                val citationsJson: JsonArray? = if (safeChunks.isNotEmpty()) {
                    buildJsonArray {
                        safeChunks.forEach { chunk ->
                            add(buildJsonObject {
                                put("chunkId", chunk.chunkId)
                                put("source", chunk.docId)
                                put("score", chunk.score)
                            })
                        }
                    }
                } else null

                // Buffer the full LLM response before sending it to the client.
                // inputTokens/outputTokens accumulate the TOTAL across all passes (Lite first
                // pass + Pro escalation retry + strict-mode retry); proInputTokens/proOutputTokens
                // hold the Pro-pass share so cost-weighted budgeting can price each segment.
                val responseBuilder = StringBuilder()
                var inputTokens = 0
                var outputTokens = 0
                var cachedTokens = 0
                var proInputTokens = 0
                var proOutputTokens = 0
                var finishReason: String? = null
                var toolCallCount = 0

                // mode=pro skips the Lite pass entirely: one Pro pass (read-only tools,
                // same as the escalation retry) whose tokens land in the pro segment.
                // mode=fast never runs the Pro retry, so the self-escalation marker is
                // disabled — Lite must answer as best it can instead of handing off.
                withTimeout(STREAM_TIMEOUT_MS) {
                    agentFactory.streamChat(
                        chatId                = chatId,
                        locale                = body.locale,
                        history               = history,
                        userMessage           = sanitized,
                        escalate              = proMode,
                        kbContext             = kbContext,
                        userContext           = userContext,
                        summaryContext        = summaryContext,
                        isFirstTurn           = isFirstTurn,
                        toolRunner            = toolRunner,
                        allowEscalationMarker = mode != CoachMode.FAST,
                    ).collect { coachFrame ->
                        when (coachFrame) {
                            is CoachFrame.LLMFrame -> when (val frame = coachFrame.frame) {
                                is StreamFrame.TextDelta -> responseBuilder.append(frame.text)
                                is StreamFrame.End -> {
                                    inputTokens  += frame.metaInfo.inputTokensCount ?: 0
                                    outputTokens += frame.metaInfo.outputTokensCount ?: 0
                                    if (proMode) {
                                        proInputTokens  += frame.metaInfo.inputTokensCount ?: 0
                                        proOutputTokens += frame.metaInfo.outputTokensCount ?: 0
                                    }
                                    finishReason  = frame.finishReason
                                }
                                else -> Unit
                            }
                            is CoachFrame.ToolStarted -> {
                                toolCallCount++
                                sendSseEventGuarded(
                                    "tool_start",
                                    sseJson.encodeToString(
                                        ToolStartPayload.serializer(),
                                        ToolStartPayload(name = publicToolStatusName(coachFrame.name)),
                                    )
                                )
                            }
                            is CoachFrame.ToolFinished -> sendSseEventGuarded(
                                "tool_done",
                                sseJson.encodeToString(
                                    ToolDonePayload.serializer(),
                                    ToolDonePayload(name = publicToolStatusName(coachFrame.name), ms = coachFrame.ms),
                                )
                            )
                        }
                    }
                }

                // Escalation to Pro. Always run Flash Lite first (above), then evaluate the
                // four triggers and retry once on the Pro model (2k cap) if any fired. Running Flash
                // first uniformly — even for the pre-LLM classifier signal — keeps a bad escalation
                // model id from killing the turn: a failed Pro call gracefully falls back to Flash.
                val firstPass = responseBuilder.toString()
                val markerPresent = firstPass.contains(SystemPromptV1.NEEDS_ESCALATION_MARKER)
                val truncated = finishReason?.let {
                    it.equals("length", true) || it.equals("MAX_TOKENS", true) ||
                        it.contains("length", true) || it.contains("max_token", true)
                } ?: false
                var escalated = proMode

                val escalationTriggered =
                    classifierWantsEscalation || markerPresent || truncated || toolCallCount > 3
                if (escalationTriggered && mode != CoachMode.AUTO) {
                    // pro already ran on the Pro model; fast explicitly opts out of the retry.
                    log.info(
                        "[COACH-ESCALATE] retry_suppressed mode={} userId={} chatId={}",
                        mode.wire, user.userId, chatId
                    )
                }
                if (escalationTriggered && mode == CoachMode.AUTO) {
                    val reason = when {
                        classifierWantsEscalation -> "classifier_complex_reasoning"
                        markerPresent -> "needs_escalation_marker"
                        truncated -> "finish_reason_length"
                        else -> "tool_calls_gt_3"
                    }
                    log.info(
                        "[COACH-ESCALATE] retry_on_pro userId={} chatId={} reason={} toolCalls={} finishReason={}",
                        user.userId, chatId, reason, toolCallCount, finishReason
                    )
                    val proBuilder = StringBuilder()
                    var proInput = 0
                    var proOutput = 0
                    var proFinish: String? = null
                    // The Pro pass gets the READ-ONLY tools (streamChat filters out
                    // writeUserCoachNote on escalate) so complex data questions keep live data
                    // access without risking a duplicated note write.
                    runCatching {
                        withTimeout(STREAM_TIMEOUT_MS) {
                            agentFactory.streamChat(
                                chatId         = chatId,
                                locale         = body.locale,
                                history        = history,
                                userMessage    = sanitized,
                                escalate       = true,
                                kbContext      = kbContext,
                                userContext    = userContext,
                                summaryContext = summaryContext,
                                isFirstTurn    = isFirstTurn,
                                toolRunner     = toolRunner,
                            ).collect { coachFrame ->
                                when (coachFrame) {
                                    is CoachFrame.LLMFrame -> when (val frame = coachFrame.frame) {
                                        is StreamFrame.TextDelta -> proBuilder.append(frame.text)
                                        is StreamFrame.End -> {
                                            proInput += frame.metaInfo.inputTokensCount ?: 0
                                            proOutput += frame.metaInfo.outputTokensCount ?: 0
                                            proFinish = frame.finishReason
                                        }
                                        else -> Unit
                                    }
                                    is CoachFrame.ToolStarted -> sendSseEventGuarded(
                                        "tool_start",
                                        sseJson.encodeToString(
                                            ToolStartPayload.serializer(),
                                            ToolStartPayload(name = publicToolStatusName(coachFrame.name)),
                                        )
                                    )
                                    is CoachFrame.ToolFinished -> sendSseEventGuarded(
                                        "tool_done",
                                        sseJson.encodeToString(
                                            ToolDonePayload.serializer(),
                                            ToolDonePayload(name = publicToolStatusName(coachFrame.name), ms = coachFrame.ms),
                                        )
                                    )
                                }
                            }
                        }
                    }.onFailure { e ->
                        // Loud — never silently keep Flash output while claiming escalated=true.
                        log.error("[COACH-ESCALATE] pro_call_failed userId={} chatId={}", user.userId, chatId, e)
                    }
                    val proContent = proBuilder.toString()
                        .replace(SystemPromptV1.NEEDS_ESCALATION_MARKER, "").trim()
                    // The Pro pass is paid for even when it comes back empty and we keep the
                    // Flash output — charge it on top of the Lite pass, never instead of it.
                    proInputTokens += proInput
                    proOutputTokens += proOutput
                    inputTokens += proInput
                    outputTokens += proOutput
                    if (proContent.isNotBlank()) {
                        responseBuilder.setLength(0)
                        responseBuilder.append(proContent)
                        finishReason = proFinish
                        escalated = true
                    } else {
                        log.warn(
                            "[COACH-ESCALATE] pro_retry_empty userId={} chatId={}, keeping Flash output",
                            user.userId, chatId
                        )
                    }
                }

                // Output sanitizer: check, retry once with strict mode, then fallback.
                // Strip any residual <<NEEDS_ESCALATION>> marker uniformly (e.g. if the Pro retry
                // failed and we fell back to the marked Flash output).
                var assistantContent = responseBuilder.toString()
                    .replace(SystemPromptV1.NEEDS_ESCALATION_MARKER, "").trim()
                var safetyAction: String? = null

                when (val checkResult = OutputSanitizer.check(assistantContent)) {
                    is OutputSanitizer.Result.Pass -> Unit
                    is OutputSanitizer.Result.Fail -> {
                        log.warn(
                            "[COACH-SAFETY] output_rejected userId={} reason={} chatId={}",
                            user.userId, checkResult.reason, chatId
                        )
                        val retryBuilder = StringBuilder()
                        var retryInputTokens = 0
                        var retryOutputTokens = 0
                        runCatching {
                            withTimeout(STREAM_TIMEOUT_MS) {
                                agentFactory.streamChat(
                                    chatId         = chatId,
                                    locale         = body.locale,
                                    history        = history,
                                    userMessage    = sanitized,
                                    strictMode     = true,
                                    kbContext      = kbContext,
                                    userContext    = userContext,
                                    summaryContext = summaryContext,
                                    isFirstTurn    = isFirstTurn,
                                ).collect { coachFrame ->
                                    when (coachFrame) {
                                        is CoachFrame.LLMFrame -> when (val frame = coachFrame.frame) {
                                            is StreamFrame.TextDelta -> retryBuilder.append(frame.text)
                                            is StreamFrame.End -> {
                                                retryInputTokens += frame.metaInfo.inputTokensCount ?: 0
                                                retryOutputTokens += frame.metaInfo.outputTokensCount ?: 0
                                            }
                                            else -> Unit
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        val retryContent = retryBuilder.toString()
                        // The strict retry runs on the Lite model and is paid for whether or
                        // not its output is used — accumulate, don't replace.
                        inputTokens += retryInputTokens
                        outputTokens += retryOutputTokens
                        if (retryContent.isNotBlank() && OutputSanitizer.check(retryContent) is OutputSanitizer.Result.Pass) {
                            assistantContent = retryContent
                        } else {
                            log.error(
                                "[COACH-SAFETY] output_rejected_after_retry userId={} chatId={}, using fallback",
                                user.userId, chatId
                            )
                            assistantContent = GENERIC_FALLBACK
                            safetyAction = "output_filtered"
                        }
                    }
                }

                // Strip internal (KB: doc-id) grounding markers before display + persistence.
                // Structured `citation` events (below) carry source attribution for the client.
                assistantContent = stripKbCitations(assistantContent)

                // Shrink oversized Markdown headers so they render calmly on mobile.
                assistantContent = demoteMarkdownHeaders(assistantContent)

                // Guard: Gemini thinking models can emit 0 TextDelta frames on empty tool results.
                if (assistantContent.isBlank()) {
                    log.warn("[COACH] blank_response userId={} chatId={}", user.userId, chatId)
                    assistantContent = "I checked that for you but couldn't form a response. Please try again."
                    safetyAction = "blank_response"
                }

                // Emit buffered tokens to client
                sendSseEventGuarded(
                    "token",
                    sseJson.encodeToString(TokenPayload.serializer(), TokenPayload(delta = assistantContent))
                )

                // Emit citation events before done.
                safeChunks.forEach { chunk ->
                    sendSseEventGuarded(
                        "citation",
                        sseJson.encodeToString(
                            CitationPayload.serializer(),
                            CitationPayload(chunkId = chunk.chunkId, source = chunk.docId, score = chunk.score)
                        )
                    )
                }

                // On the first turn, generate a title via Flash Lite and emit before done.
                if (isFirstTurn) {
                    runCatching {
                        withTimeout(3_000L) { agentFactory.generateTitle(content, body.locale) }
                    }.getOrNull()?.let { generatedTitle ->
                        runCatching { chatGateway.updateChatTitle(chatId, user.userId, generatedTitle) }
                            .onFailure { e -> log.warn("[COACH] updateChatTitle failed chatId={}", chatId, e) }
                        sendSseEventGuarded(
                            "title",
                            sseJson.encodeToString(
                                TitlePayload.serializer(),
                                TitlePayload(chatId = chatId, title = generatedTitle)
                            )
                        )
                    }
                }

                // Persist assistant message — non-fatal so `done` always reaches the client.
                val msgId = runCatching {
                    chatGateway.insertMessage(
                        chatId = chatId,
                        userId = user.userId,
                        role = "assistant",
                        content = assistantContent,
                        requestId = requestId,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cachedTokens = cachedTokens,
                        proInputTokens = proInputTokens.takeIf { it > 0 },
                        proOutputTokens = proOutputTokens.takeIf { it > 0 },
                        modelUsed = if (escalated) CoachModels.ESCALATION else CoachModels.PRIMARY,
                        finishReason = finishReason,
                        safetyAction = safetyAction,
                        citations = citationsJson,
                        escalated = escalated,
                    ).id
                }.onSuccess { assistantPersisted = true }
                    .onFailure { e ->
                        log.error("[COACH] assistant_persist_failed chatId={}", chatId, e)
                    }.getOrElse { java.util.UUID.randomUUID().toString() }

                sendSseEventGuarded(
                    "done",
                    sseJson.encodeToString(
                        DonePayload.serializer(),
                        DonePayload(
                            chatId = chatId,
                            messageId = msgId,
                            tokens = TokenUsage(input = inputTokens, output = outputTokens, cached = cachedTokens),
                            model = if (escalated) CoachModels.ESCALATION else CoachModels.PRIMARY,
                            escalated = escalated,
                            mode = mode.wire,
                        )
                    )
                )

                // Release lock immediately after `done` so the next user message isn't blocked
                // by the slow insertTrace call below (30–60s on dev cold starts).
                // insertMessage runs before this point so history ordering is preserved.
                inFlight.remove(flightKey)
                lockReleased = true

                // Reconcile the reservation with actual per-segment token usage (non-fatal).
                // inputTokens/outputTokens are totals across all passes (Lite + Pro + retry);
                // the Pro share carries a 6× credit weight, so it's split out for the RPC.
                reservationId?.let { rid ->
                    runCatching {
                        budgetGateway.reconcile(
                            reservationId = rid,
                            liteInput     = (inputTokens - proInputTokens).coerceAtLeast(0),
                            liteOutput    = (outputTokens - proOutputTokens).coerceAtLeast(0),
                            proInput      = proInputTokens,
                            proOutput     = proOutputTokens,
                            // koog 1.0.0 ResponseMetaInfo has no cached-token field; the SQL
                            // quarter-weight is ready whenever the stream exposes one.
                            cachedInput   = 0,
                        )
                    }
                        .onSuccess { budgetSettled = true }
                        .onFailure { e -> log.warn("[COACH-BUDGET] reconcile_failed reservationId={}", rid, e) }
                }

                // Persist RAG trace (non-fatal)
                runCatching {
                    chatGateway.insertTrace(
                        messageId = msgId,
                        userId = user.userId,
                        ragQuery = sanitized,
                        retrieved = citationsJson,
                        promptVersion = CoachPromptVersion.CURRENT_INT,
                    )
                }.onFailure { e ->
                    log.warn("[COACH] insertTrace failed messageId={}", msgId, e)
                }
            } catch (e: Exception) {
                if (!lockReleased) {
                    inFlight.remove(flightKey)
                    lockReleased = true
                }
                // Every throw point above the `done` event is wrapped in its own runCatching, so
                // reaching here always means the turn failed before an assistant reply completed —
                // safe to roll back the user row unconditionally (including client disconnects).
                rollbackUserMessage()
                if (e is ClosedWriteChannelException) {
                    // Client closed the SSE connection — expected, not an error.
                    log.debug("[COACH] SSE client disconnected userId={}", user.userId)
                } else {
                    log.error("[COACH] SSE stream failed userId={}", user.userId, e)
                    runCatching {
                        sendSseEventGuarded(
                            "error",
                            sseJson.encodeToString(
                                SseErrorPayload.serializer(),
                                SseErrorPayload(code = "INTERNAL_ERROR", message = "Processing failed")
                            )
                        )
                    }
                }
            } finally {
                pingJob.cancel()
                // Release the reservation if the turn never reconciled (stream cancel,
                // error, or a reconcile failure). Idempotent: budget_release no-ops unless the
                // reservation is still 'reserved', so this is safe after a successful reconcile.
                val rid = reservationId
                if (rid != null && !budgetSettled) {
                    runCatching { budgetGateway.release(rid) }
                        .onFailure { e -> log.warn("[COACH-BUDGET] release_failed reservationId={}", rid, e) }
                }
            }
            }
        }
    } finally {
        // Safety net: release lock on any exception path that bypassed the in-block release.
        if (!lockReleased) inFlight.remove(flightKey)
    }
}
