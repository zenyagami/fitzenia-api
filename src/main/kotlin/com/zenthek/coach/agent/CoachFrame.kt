package com.zenthek.coach.agent

import ai.koog.prompt.streaming.StreamFrame

sealed class CoachFrame {
    data class LLMFrame(val frame: StreamFrame) : CoachFrame()
    data class ToolStarted(val name: String) : CoachFrame()
    data class ToolFinished(val name: String, val ms: Long) : CoachFrame()
}
