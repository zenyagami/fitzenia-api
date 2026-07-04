package com.zenthek.coach.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.zenthek.coach.agent.tools.CoachToolDescriptors
import com.zenthek.coach.agent.tools.CoachToolRunner
import com.zenthek.coach.config.CoachModels
import com.zenthek.coach.persistence.HistoryMessageRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val MAX_TOOL_ITERATIONS = 5

// Gemini "thinking" models spend part of maxOutputTokens on invisible reasoning before writing
// the visible answer. Left unset (plain LLMParams -> GoogleParams(thinkingConfig = null)),
// thinking defaults to dynamic/unbounded and can consume nearly the whole cap, truncating the
// visible answer at finish_reason=MAX_TOKENS (confirmed against the live API: with no
// thinkingConfig, gemini-3.5-flash spent 1793 of a 2048-token budget on thoughts, leaving only
// 251 visible tokens). Bounding thinkingBudget reserves guaranteed headroom for the answer.
private const val LITE_MAX_OUTPUT_TOKENS = 1024
private const val LITE_THINKING_BUDGET = 384
// 4096 (not 2048): confirmed via live probe that 2048 still truncates a genuinely demanding
// escalated ask (full multi-section plan) even with thinkingBudget bounded; 4096 completed the
// same prompt with ~34% headroom to spare. Raises the documented worst-case reserve/reconcile
// overshoot in docs/AI_COACH.md from ~194k to ~267k credits (~$0.018 more, negligible vs the
// $0.51/user/mo COGS budget) — update that figure if this constant changes again.
private const val PRO_MAX_OUTPUT_TOKENS = 4096
private const val PRO_THINKING_BUDGET = 768

class CoachAgentFactory(apiKey: String) {

    private val executor: MultiLLMPromptExecutor

    private val primaryModel = LLModel(
        provider = LLMProvider.Google,
        id = CoachModels.PRIMARY,
        // LLMCapability.Thinking is required so GoogleLLMClient uses its fallback thought_signature
        // ("context_engineering_is_the_way_to_go") when no real signature is in lastSignature.
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Thinking),
    )

    private val liteModel = LLModel(
        provider = LLMProvider.Google,
        id = CoachModels.PRIMARY,
        capabilities = listOf(LLMCapability.Completion),
    )

    // Escalation target. Same capability set as the primary so the tool-call/thought-signature
    // bridge in the streaming loop behaves identically when tools are enabled.
    private val escalationModel = LLModel(
        provider = LLMProvider.Google,
        id = CoachModels.ESCALATION,
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Thinking),
    )

    init {
        val client = GoogleLLMClient(apiKey)
        executor = MultiLLMPromptExecutor(client)
    }

    suspend fun generateTitle(userMessage: String, locale: String): String? {
        val sb = StringBuilder()
        executor.executeStreaming(
            prompt = Prompt(
                messages = listOf(
                    Message.System(
                        content = "You generate short conversation titles. Output ONLY the title text — no quotes, no trailing punctuation, no explanation.",
                        metaInfo = RequestMetaInfo.Empty,
                    ),
                    Message.User(
                        content = "Write a title in $locale (at most 6 words) for a coaching conversation that starts with:\n\"${userMessage.take(300)}\"",
                        metaInfo = RequestMetaInfo.Empty,
                    ),
                ),
                id = "title",
                params = LLMParams(maxTokens = 20),
            ),
            model = liteModel,
            tools = emptyList(),
        ).collect { frame ->
            if (frame is StreamFrame.TextDelta) sb.append(frame.text)
        }
        val title = sb.toString().trim().take(80)
        return if (title.isNotBlank()) title else null
    }

    suspend fun generateSummary(messages: List<HistoryMessageRow>, priorSummary: String?): Pair<String, Int> {
        val conversationText = messages.joinToString("\n") { "[${it.role}] ${it.content.take(500)}" }
        val userContent = buildString {
            if (priorSummary != null) {
                appendLine("Prior summary (earlier context):")
                appendLine(priorSummary)
                appendLine()
                appendLine("Additional messages to incorporate:")
            }
            append(conversationText)
        }
        val sb = StringBuilder()
        var outputTokens = 0
        executor.executeStreaming(
            prompt = Prompt(
                messages = listOf(
                    Message.System(
                        content = "You are a fitness/nutrition coaching conversation summarizer. When given conversation messages, output a concise factual summary (at most 200 words). Include key facts, goals, foods logged, and advice given. Output the summary text ONLY — no preamble, no meta-commentary.",
                        metaInfo = RequestMetaInfo.Empty,
                    ),
                    Message.User(content = userContent, metaInfo = RequestMetaInfo.Empty),
                ),
                id = "summary",
                params = LLMParams(maxTokens = 200),
            ),
            model = liteModel,
            tools = emptyList(),
        ).collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> sb.append(frame.text)
                is StreamFrame.End -> outputTokens = frame.metaInfo.outputTokensCount ?: 0
                else -> Unit
            }
        }
        return Pair(sb.toString().trim(), outputTokens)
    }

    fun streamChat(
        chatId: String,
        locale: String,
        history: List<HistoryMessageRow>,
        userMessage: String,
        strictMode: Boolean = false,
        escalate: Boolean = false,
        kbContext: String? = null,
        userContext: String? = null,
        summaryContext: String? = null,
        toolRunner: CoachToolRunner? = null,
        allowEscalationMarker: Boolean = true,
    ): Flow<CoachFrame> = flow {
        // The Pro model is the escalation target, so it never carries the self-signal instruction.
        // Callers that will never run the Pro retry (mode=fast) also disable it — the prompt tells
        // the model to reply with ONLY the marker, which would leave those users with no answer.
        val systemPrompt = SystemPromptV1.build(
            locale, strictMode, userContext, summaryContext,
            allowEscalationMarker = allowEscalationMarker && !escalate,
        )
        val activeModel = if (escalate) escalationModel else primaryModel
        val maxOutputTokens = if (escalate) PRO_MAX_OUTPUT_TOKENS else LITE_MAX_OUTPUT_TOKENS
        val thinkingBudget = if (escalate) PRO_THINKING_BUDGET else LITE_THINKING_BUDGET
        val userContent = if (kbContext != null) "$kbContext\n\n$userMessage" else userMessage

        val messages = mutableListOf<Message>()
        messages.add(Message.System(content = systemPrompt, metaInfo = RequestMetaInfo.Empty))
        for (row in history) {
            when (row.role) {
                "user" -> messages.add(Message.User(content = row.content, metaInfo = RequestMetaInfo.Empty))
                "assistant" -> messages.add(
                    Message.Assistant(
                        content = row.content,
                        metaInfo = ResponseMetaInfo(timestamp = kotlin.time.Clock.System.now()),
                    )
                )
            }
        }
        messages.add(Message.User(content = userContent, metaInfo = RequestMetaInfo.Empty))

        // Tools disabled for strict-mode retries. The escalated Pro pass gets the read tools only,
        // so it can ground complex data questions but can never double-write a coach note.
        val tools: List<ToolDescriptor> = when {
            toolRunner == null || strictMode -> emptyList()
            escalate -> CoachToolDescriptors.READ_ONLY
            else -> CoachToolDescriptors.ALL
        }
        var iterationsLeft = MAX_TOOL_ITERATIONS

        while (true) {
            val toolCalls = mutableListOf<StreamFrame.ToolCallComplete>()
            val reasoningFrames = mutableListOf<StreamFrame.ReasoningComplete>()
            var endFrame: StreamFrame.End? = null

            executor.executeStreaming(
                prompt = Prompt(
                    messages = messages,
                    id = chatId,
                    params = GoogleParams(
                        maxTokens = maxOutputTokens,
                        thinkingConfig = GoogleThinkingConfig(thinkingBudget = thinkingBudget),
                    ),
                ),
                model = activeModel,
                tools = tools,
            ).collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> emit(CoachFrame.LLMFrame(frame))
                    is StreamFrame.ToolCallComplete -> toolCalls.add(frame)
                    // Preserve reasoning/thought-signature so Gemini 2.5+ accepts the next turn
                    is StreamFrame.ReasoningComplete -> reasoningFrames.add(frame)
                    is StreamFrame.End -> endFrame = frame
                    else -> Unit
                }
            }

            endFrame?.let { emit(CoachFrame.LLMFrame(it)) }

            if (toolCalls.isEmpty() || toolRunner == null || iterationsLeft <= 0) break

            // Append assistant message: thought content → empty-sig bridge → tool calls.
            //
            // In streaming mode, GoogleLLMClient puts the thought_signature in
            // StreamFrame.ReasoningComplete.id (not .encrypted). toGoogleContent() only propagates
            // a signature to the FunctionCall via lastSignature, which is set ONLY from a
            // MessagePart.Reasoning with EMPTY content. So we must add one empty Reasoning part
            // (encrypted = r.id) after the thought text and before the tool calls.
            // LLMCapability.Thinking on the model enables the fallback signature as a safety net
            // for cases where no ReasoningComplete arrived.
            val thoughtSig = reasoningFrames.firstOrNull()?.id
            messages.add(
                Message.Assistant(
                    parts = buildList {
                        // Replay the thought text (non-empty content).
                        reasoningFrames.forEach { r ->
                            if (r.content.isNotEmpty()) {
                                add(MessagePart.Reasoning(
                                    content = r.content,
                                    summary = r.summary,
                                    encrypted = r.id,  // streaming: sig is in .id, not .encrypted
                                    id = r.id,
                                ))
                            }
                        }
                        // Empty-content bridge so toGoogleContent sets lastSignature before Tool.Call.
                        // Mirrors what non-streaming processGoogleCandidate emits for each FunctionCall.
                        if (thoughtSig != null) {
                            add(MessagePart.Reasoning(content = emptyList(), encrypted = thoughtSig))
                        }
                        addAll(toolCalls.map {
                            MessagePart.Tool.Call(id = it.id, tool = it.name, args = it.content)
                        })
                    },
                    metaInfo = ResponseMetaInfo(timestamp = kotlin.time.Clock.System.now()),
                    finishReason = "tool_calls",
                )
            )

            // Execute each tool, emit start/done frames, collect results.
            val resultParts = mutableListOf<MessagePart.Tool.Result>()
            for (tc in toolCalls) {
                emit(CoachFrame.ToolStarted(tc.name))
                val t0 = System.currentTimeMillis()
                val raw = runCatching { toolRunner.run(tc.name, tc.content) }
                    .getOrElse { """{"error":"tool_failed"}""" }
                emit(CoachFrame.ToolFinished(tc.name, System.currentTimeMillis() - t0))
                // Belt-and-braces: reject output that could escape the tagged envelope.
                val safe = if (raw.contains("</tool_output>") || raw.contains("</kb_context>")) {
                    """{"error":"tool_output_sanitized"}"""
                } else raw
                resultParts.add(MessagePart.Tool.Result(id = tc.id, tool = tc.name, output = safe))
            }

            // Append tool results as a user message.
            messages.add(Message.User(parts = resultParts, metaInfo = RequestMetaInfo.Empty))
            iterationsLeft--
        }
    }
}
