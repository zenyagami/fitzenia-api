package com.zenthek.coach.agent.safety

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class BlockClass {
    PASS,
    ED_REDIRECT,
    MEDICAL_REDIRECT,
    DRUG_REDIRECT,
    SELF_HARM_REDIRECT,
    OFF_TOPIC,
    COMPLEX_REASONING,
}

sealed class ClassifyResult {
    data object Pass : ClassifyResult()
    data class HardBlock(val blockClass: BlockClass, val message: String) : ClassifyResult()

    /** Not blocked, but routed straight to the Pro model (e.g. COMPLEX_REASONING). */
    data class Escalate(val blockClass: BlockClass) : ClassifyResult()
}

class HardBlockClassifier {

    @Serializable
    private data class RedirectBundle(
        @SerialName("ED_REDIRECT") val edRedirect: String,
        @SerialName("SELF_HARM_REDIRECT") val selfHarmRedirect: String,
        @SerialName("MEDICAL_REDIRECT") val medicalRedirect: String,
        @SerialName("DRUG_REDIRECT") val drugRedirect: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val bundles = mutableMapOf<String, RedirectBundle>()

    private fun bundle(locale: String): RedirectBundle {
        // Strip region subtags so "es-ES" / "pt_BR" resolve to the base-language redirect file.
        val lang = locale.substringBefore('-').substringBefore('_').lowercase().ifBlank { "en" }
        return bundles.getOrPut(lang) {
            val stream = javaClass.getResourceAsStream("/redirects/$lang.json")
                ?: javaClass.getResourceAsStream("/redirects/en.json")!!
            json.decodeFromString(RedirectBundle.serializer(), stream.bufferedReader().readText())
        }
    }

    fun classify(sanitized: String, locale: String = "en"): ClassifyResult {
        val lower = sanitized.lowercase()
        return when {
            ED_PATTERNS.any { it.containsMatchIn(lower) } ->
                ClassifyResult.HardBlock(BlockClass.ED_REDIRECT, bundle(locale).edRedirect)
            SELF_HARM_PATTERNS.any { it.containsMatchIn(lower) } ->
                ClassifyResult.HardBlock(BlockClass.SELF_HARM_REDIRECT, bundle(locale).selfHarmRedirect)
            MEDICAL_PATTERNS.any { it.containsMatchIn(lower) } ->
                ClassifyResult.HardBlock(BlockClass.MEDICAL_REDIRECT, bundle(locale).medicalRedirect)
            DRUG_PATTERNS.any { it.containsMatchIn(lower) } ->
                ClassifyResult.HardBlock(BlockClass.DRUG_REDIRECT, bundle(locale).drugRedirect)
            // Escalation signal: evaluated after all safety blocks so a redirect always wins.
            COMPLEX_REASONING_PATTERNS.any { it.containsMatchIn(lower) } ->
                ClassifyResult.Escalate(BlockClass.COMPLEX_REASONING)
            else -> ClassifyResult.Pass
        }
    }

    companion object {
        private val ED_PATTERNS = listOf(
            Regex("""(anorexia|bulimia|binge.?eating|bingeing|purging)"""),
            Regex("""eating\s+disorder"""),
            Regex("""(starving myself|stop eating entirely|not eating at all)"""),
            Regex("""(laxative|diuretic).{0,20}(lose weight|after eating|to compensate)"""),
            Regex("""(orthorexia|body dysmorphia)"""),
            Regex("""(severely restrict|extreme(ly)? restrict).{0,20}(food|calori|intake|eating)"""),
            Regex("""restrict.{0,10}(food|calori|intake|eating).{0,20}(severely|extremely|drastically)"""),
            Regex("""(0|zero|no) calori.{0,15}(a day|per day|daily|every day)"""),
            Regex("""under \d{2,3} calori.{0,10}(a day|per day|daily)"""),
            // Any tense/phrasing of self-induced vomiting ("how can I make myself sick", "making myself throw up").
            Regex("""mak(e|ing).{0,10}myself.{0,6}(throw up|vomit|puke|sick|purge)"""),
            // Verb forms of "purge" bound to a food/weight context so "purge my chat history" stays unblocked.
            Regex("""purg(e|ing|ed).{0,30}(meal|eat|food|calori|weight|thin|skinny)"""),
            Regex("""(after|before).{0,12}(meal|eat|food)s?.{0,20}purg(e|ing|ed)"""),
            // Vomiting/throwing up as a weight-loss or compensation tactic.
            Regex("""(throw(ing)? up|vomit(ing)?|puk(e|ing)).{0,30}(lose weight|weight loss|burn.{0,8}calori|stay (thin|skinny|lean)|to compensate|after (meal|eating))"""),
            Regex("""(lose weight|weight loss|stay (thin|skinny|lean)).{0,30}(throw(ing)? up|vomit(ing)?|puk(e|ing)|purg(e|ing))"""),
        )

        private val SELF_HARM_PATTERNS = listOf(
            Regex("""self[.\- ]?harm"""),
            Regex("""(cut|hurt|injure|burn|hit).{0,10}(myself|my (arm|leg|body|skin|wrist))"""),
            Regex("""suicid(al|e|ing|ally)"""),
            Regex("""(kill|end|take).{0,10}(my life|myself)"""),
            Regex("""(don.?t|do not|no longer) want to (be alive|live|exist)"""),
            Regex("""want to die"""),
            Regex("""(overdose|od) on"""),
        )

        private val MEDICAL_PATTERNS = listOf(
            Regex("""do i have (cancer|diabetes|a disease|a condition|celiac|crohn|ibs|sibo|colitis|lupus|ms\b)"""),
            Regex("""(is it|could it be|might (it|this) be) (cancer|a tumor|diabetes|celiac|an autoimmune)"""),
            Regex("""diagnos(e|is|ed).{0,20}(me|myself|what|my (symptoms|condition))"""),
            Regex("""what.{0,10}(disease|disorder|condition|illness) do i have"""),
            Regex("""(interpret|explain|read).{0,15}(my|these).{0,10}(blood|lab|test) results?"""),
            Regex("""what.?s wrong with (me|my body|my health)"""),
        )

        private val DRUG_PATTERNS = listOf(
            Regex("""\b(steroids?|sarms?)\b"""),
            Regex("""\bpeds?\b"""),
            Regex("""performance.?enhancing (drug|substance|compound)"""),
            Regex("""testosterone (injection|enanthate|cypionate|propionate|undecanoate)"""),
            Regex("""\b(anabolic|androgenic) (steroid|compound|agent)"""),
            Regex("""(clenbuterol|trenbolone|nandrolone|stanozolol|oxandrolone|boldenone|winstrol|deca\b)"""),
            Regex("""\bhgh\b.{0,20}(inject|take|use|cycle|dose)"""),
            Regex("""human growth hormone.{0,20}(inject|take|use|cycle)"""),
            Regex("""\bepo\b.{0,20}(inject|take|use|dose|cycling)"""),
            Regex("""erythropoietin"""),
            Regex("""insulin (injection|shot|dose|protocol|cycle)"""),
        )

        // COMPLEX_REASONING: high-precision heuristic baseline (mirrors the regex approach of the
        // safety classifier). Kept deliberately narrow: it must catch genuine multi-step planning
        // requests while leaving ordinary questions ("good protein target on a cut?") on Flash Lite.
        // The <<NEEDS_ESCALATION>> self-signal is the general fallback for anything this misses.
        private val COMPLEX_REASONING_PATTERNS = listOf(
            Regex("""step[\s\-]?by[\s\-]?step"""),
            Regex("""(full|complete|detailed|comprehensive|whole|weekly|monthly|\d+[\s\-]?(day|week|month))[\s\S]{0,40}(meal[\s\-]?plan|workout[\s\-]?(plan|program|routine|split)|training[\s\-]?(plan|program|split)|diet[\s\-]?plan|nutrition[\s\-]?plan|periodization)"""),
            Regex("""(meal[\s\-]?plan|workout[\s\-]?(plan|program|routine)|training[\s\-]?(plan|program))[\s\S]{0,40}\b(and|plus|as well as|along with)\b[\s\S]{0,40}(workout|meal|grocery|shopping|macro|cardio|supplement|training)"""),
        )
    }
}
