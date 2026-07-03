package com.zenthek.coach.agent.safety

object OutputSanitizer {

    sealed class Result {
        data object Pass : Result()
        data class Fail(val reason: String) : Result()
    }

    private val DOSAGE_PATTERN = Regex("""\d+\s*(mg|mcg|iu)\b""", RegexOption.IGNORE_CASE)

    private val PRESCRIPTION_DRUGS = Regex(
        """\b(metformin|ozempic|semaglutide|orlistat|phentermine|topiramate|naltrexone|bupropion|liraglutide|tirzepatide|wegovy|saxenda|qsymia|contrave|victoza|mounjaro|zepbound)\b""",
        RegexOption.IGNORE_CASE
    )

    // Medical conditions the coach must never diagnose the user with.
    // Targeted allowlist is fragile — match the bad thing directly instead.
    private val MEDICAL_CONDITIONS = (
        """(?:type [12] )?diabetes|pre-?diabetes|insulin resistance|""" +
        """anemia|anaemia|iron deficiency|vitamin deficiency|nutrient deficiency|""" +
        """eating disorder|anorexia(?:\s+nervosa)?|bulimia(?:\s+nervosa)?|""" +
        """hypothyroid(?:ism)?|hyperthyroid(?:ism)?|""" +
        """celiac(?:\s+disease)?|coeliac(?:\s+disease)?|""" +
        """metabolic syndrome|polycystic(?:\s+ovary)?|pcos|""" +
        """morbid obesity|hypertension|high blood pressure|malnutrition"""
    )

    private val DIAGNOSTIC_PHRASES = listOf(
        Regex("""(you have|you might have|you could have|you('re| are) suffering from)\s+($MEDICAL_CONDITIONS)\b""", RegexOption.IGNORE_CASE),
        Regex("""(it sounds like|it seems like|this indicates|this suggests) you have\s+($MEDICAL_CONDITIONS)\b""", RegexOption.IGNORE_CASE),
        Regex("""you('ve| have) been diagnosed\b""", RegexOption.IGNORE_CASE),
        Regex("""you('re| are) (diabetic|pre-?diabetic|anorexic|bulimic|hypothyroid|celiac)\b""", RegexOption.IGNORE_CASE),
    )

    private val URL_PATTERN = Regex("""https?://\S+""")

    private val EMAIL_PII = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
    private val PHONE_PII = Regex("""\b(?:\+?[0-9]{1,3}[\s.\-]?)?(?:\([0-9]{2,4}\)[\s.\-]?)?[0-9]{3,5}[\s.\-][0-9]{3,5}(?:[\s.\-][0-9]{3,5})?\b""")
    private val ADDRESS_PII = Regex(
        """\b\d+\s+[A-Za-z\s]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Place|Pl|Way|Parkway|Pkwy)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SYSTEM_PROMPT_LEAK = listOf(
        Regex("""\[ROLE\]"""),
        Regex("""\[TRUST BOUNDARIES\]"""),
        Regex("""\[SCOPE\]"""),
        Regex("""\[SAFETY ACTIONS\]"""),
        Regex("""\[STYLE\]"""),
        Regex("""\[CURRENT STATS\]"""),
        Regex("""\[TOOLS\]"""),
    )

    fun stripPii(text: String): String {
        var result = EMAIL_PII.replace(text, "[email]")
        result = PHONE_PII.replace(result, "[phone]")
        result = ADDRESS_PII.replace(result, "[address]")
        return result
    }

    fun isMostlyPii(text: String): Boolean {
        if (text.isBlank()) return false
        val piiLength = EMAIL_PII.findAll(text).sumOf { it.value.length } +
            PHONE_PII.findAll(text).sumOf { it.value.length } +
            ADDRESS_PII.findAll(text).sumOf { it.value.length }
        return piiLength.toDouble() / text.length > 0.5
    }

    fun check(response: String): Result {
        if (DOSAGE_PATTERN.containsMatchIn(response)) return Result.Fail("dosage_pattern")
        if (PRESCRIPTION_DRUGS.containsMatchIn(response)) return Result.Fail("prescription_drug")
        if (URL_PATTERN.containsMatchIn(response)) return Result.Fail("url_detected")
        for (pattern in DIAGNOSTIC_PHRASES) {
            if (pattern.containsMatchIn(response)) return Result.Fail("diagnostic_phrase")
        }
        for (pattern in SYSTEM_PROMPT_LEAK) {
            if (pattern.containsMatchIn(response)) return Result.Fail("prompt_leak")
        }
        return Result.Pass
    }
}
