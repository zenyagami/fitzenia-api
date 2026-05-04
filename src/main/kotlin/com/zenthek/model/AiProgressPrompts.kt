package com.zenthek.model

/**
 * Versioned prompt templates for the AI Progress Projections feature. The currently-served
 * prompt version lives in `AiProgressProjectionConfig.promptVersion` — bumping that value
 * invalidates the cache (because it's part of `request_key`) so the next generate call
 * re-runs against the new template. Old ladders in the DB stay viewable.
 *
 * Why "preserve body shape" is intentionally absent: OpenAI's canonical identity-preservation
 * example pins body shape, but our use case requires changing it. We pin face / skin / pose /
 * lighting / clothing fit *at the shoulders* and let the abdomen + waist + arms move with
 * the projected body composition. Expect occasional drift; iterate on this template after
 * watching real outputs.
 */
object AiProgressPrompts {

    /** All versions, indexed by version number. */
    private val versions: Map<Int, (PromptInputs) -> String> = mapOf(
        1 to ::buildV1,
    )

    fun render(version: Int, inputs: PromptInputs): String {
        val builder = versions[version]
            ?: error("No prompt template for version=$version (have: ${versions.keys.sorted()})")
        return builder(inputs)
    }

    data class PromptInputs(
        val currentBodyFatPercent: Double,
        val currentWeightKg: Double,
        val targetBodyFatPercent: Double,
        val targetWeightKg: Double,
    )

    private fun buildV1(i: PromptInputs): String = """
You are editing a single fitness progress photo of one adult person.

PRESERVE EXACTLY (do not change):
- The person's face, facial features, expression, skin tone
- Hair style and color
- Tattoos, scars, birthmarks
- Pose and camera angle
- Lighting direction, color temperature, and intensity
- Background, clothing, and clothing fit at the shoulders/neckline

CHANGE ONLY:
- Body composition. The person currently appears at approximately ${"%.1f".format(i.currentBodyFatPercent)}% body fat
  at ${"%.1f".format(i.currentWeightKg)} kg. Render them at approximately ${"%.1f".format(i.targetBodyFatPercent)}% body fat at
  ${"%.1f".format(i.targetWeightKg)} kg. Adjust visible muscle definition, waist, abdomen, arms, and overall
  body shape proportionally.
- Add a slight and realistic increase in muscle fullness and definition, consistent with a natural body recomposition during a calorie deficit.

CONSTRAINTS:
- Do not add or remove text, watermarks, or logos.
- Do not stylize. Photographic realism only.
- No exaggeration. The result must remain plausible for a real human but also optimistic for a person that lift weights at least 3 times per week
- Do not alter the person's apparent age.
- Keep any added muscle subtle, natural, and proportional to the person's frame and target body fat percentage.
""".trimIndent()
}
