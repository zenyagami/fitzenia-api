package com.zenthek.coach.agent.safety

object InputSanitizer {
    fun sanitize(raw: String): String =
        raw.replace(Regex("[\\p{Cntrl}&&[^\\n\\r\\t]]"), "")
           .replace(Regex("(?i)\\b(system|assistant|tool):\\s*"), "[$1]: ")
           .replace("\"\"\"", "\"\"")
           .take(2000)
}
