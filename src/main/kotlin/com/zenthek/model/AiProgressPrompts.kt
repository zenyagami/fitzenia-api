package com.zenthek.model

object AiProgressPrompts {

    private val versions: Map<Int, (PromptInputs) -> String> = mapOf(
        1 to ::buildV1,
        2 to ::buildV2,
    )

    fun render(version: Int, inputs: PromptInputs): String {
        val builder = versions[version]
            ?: error("No prompt template for version=$version (have: ${versions.keys.sorted()})")
        return builder(inputs)
    }

    /**
     * [stepIndex] / [numRungs] / [finalBodyFatPercent] / [finalWeightKg] are only consumed by v2+.
     * They carry defaults so v1 renders unchanged and stays byte-identical for rollback.
     */
    data class PromptInputs(
        val currentBodyFatPercent: Double,
        val currentWeightKg: Double,
        val targetBodyFatPercent: Double,
        val targetWeightKg: Double,
        val stepIndex: Int = 1,
        val numRungs: Int = 1,
        val finalBodyFatPercent: Double = targetBodyFatPercent,
        val finalWeightKg: Double = targetWeightKg,
    )

    /* --------------------------------------------------------------------------------- v1 */

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

    /* --------------------------------------------------------------------------------- v2 */

    /**
     * v2 fixes three failure modes observed in production v1 ladders:
     *
     *  1. **Numeric targets don't steer the image model.** A rung nominally 2.3 pp leaner than
     *     the source rendered a full 4-pack — every rung collapsed onto the same "lean fit
     *     person" attractor, so the ordering between rungs was generation noise rather than
     *     signal. v2 states the stage fraction explicitly, names the attractor and forbids it,
     *     and gives each stage a hard ceiling on how much change may be visible.
     *  2. **The prose contradicted the numbers.** Target weights are solved lean-mass-neutral
     *     (pure fat loss), while v1 asked for added "muscle fullness" and an "optimistic"
     *     result. The model added muscle on top. v2 states lean mass as a fixed quantity and
     *     prohibits adding size outright.
     *  3. **The muscle instruction was not scaled by step.** Rung 1 received the identical
     *     full-strength boost as the final rung, which both over-inflated the first rung and
     *     flattened the gradient. v2 scales every magnitude cue by [PromptInputs.stepIndex].
     *
     * v2 also hardens scene preservation: production outputs re-generated the background
     * (furniture, plants, wall objects all drifted between rungs) and re-framed the shot, which
     * changes apparent body size and is an independent confound on any rung-to-rung comparison.
     *
     * Note the scene-preservation clauses are prompt-side only. The Gemini path additionally
     * sends no `imageConfig`, so aspect ratio and resolution are still model-chosen.
     */
    private fun buildV2(i: PromptInputs): String {
        val steps = i.numRungs.coerceAtLeast(1)
        val step = i.stepIndex.coerceIn(1, steps)
        val isFinal = step >= steps
        val progressPercent = (100.0 * step / steps).toInt()

        // Stated per-frame rather than once for the sequence: the linear BF/weight
        // interpolation does not hold lean mass exactly constant between the endpoints, and
        // quoting a single figure would contradict the weight/BF pair given just above it.
        val sourceLeanMassKg = i.currentWeightKg * (1.0 - i.currentBodyFatPercent / 100.0)
        val targetLeanMassKg = i.targetWeightKg * (1.0 - i.targetBodyFatPercent / 100.0)
        val fatNowKg = i.currentWeightKg * (i.currentBodyFatPercent / 100.0)
        val fatThenKg = i.targetWeightKg * (i.targetBodyFatPercent / 100.0)
        val fatLostKg = (fatNowKg - fatThenKg).coerceAtLeast(0.0)
        val bfDelta = i.currentBodyFatPercent - i.targetBodyFatPercent

        // Magnitude ceiling, scaled by stage. The early stages carry an explicit prohibition
        // because that is where v1 overshot hardest.
        val restraint = when {
            steps == 1 -> """
Show the full change. It must still read as the same person after a realistic period of
dieting — not as a different, fitter person.
""".trimIndent()

            step == 1 -> """
This is the FIRST and most subtle frame. Someone comparing this image side by side with the
input must see a MODEST difference: slightly less softness at the waist and lower abdomen,
a marginally narrower waistline. Nothing more.
Do NOT reveal defined abdominal muscles at this stage. If individual abdominal segments,
oblique separation, or a visible "six-pack"/"four-pack" are not already present in the input
photo, they must NOT appear here. At most, the flattest part of the abdomen may look very
slightly firmer.
If you are unsure how much to change, change LESS.
""".trimIndent()

            !isFinal -> """
This is an INTERMEDIATE frame. The change from the input photo should be clearly noticeable
but still moderate, and it must sit visibly BETWEEN the previous stage and the final stage.
Muscle definition may begin to show through the thinning fat layer, but must remain well short
of the sharp, fully-defined look of the final stage.
If you are unsure how much to change, change LESS.
""".trimIndent()

            else -> """
This is the FINAL frame — the full change is shown. Even here the result must remain an
ordinary, healthy, believable physique for this specific person, not a stage-lean or
competition-conditioned one.
""".trimIndent()
        }

        return """
You are RETOUCHING one photograph of one specific adult person.

This is frame $step of $steps in a progress sequence. Every frame in the sequence is retouched
from this SAME input photograph, and all of them must look like the same photo of the same
person, taken on the same day, in the same room, with the same camera. The ONLY thing that
differs between frames is the person's body composition.

════════ ABSOLUTE CONSTRAINTS — a violation of any of these fails the task ════════
1. Return the SAME photograph, retouched. Do not re-imagine, re-shoot, re-stage or
   re-generate the scene.
2. Keep the input's exact camera framing, crop, subject distance, and aspect ratio. The person
   must stay in the same position and occupy the same proportion of the frame.
3. Reproduce the background exactly wherever the body does not overlap it — every object in
   its original position, shape and colour: furniture, plants, wall decorations, doors,
   flooring, and anything resting on a surface. Do not add, remove, move or redraw scene
   objects.
4. Preserve the face, facial structure, expression, gaze, hairline, hair, skin tone, body-hair
   pattern, tattoos, scars and birthmarks.
5. Preserve the pose, limb and hand positions, clothing, clothing fit, and any worn items
   (watch, jewellery).
6. Preserve lighting direction, colour temperature, shadow placement and overall exposure.
7. Photographic realism only. No stylisation, no text, no watermarks, no logos. Do not change
   the person's apparent age.

════════ THE ONLY PERMITTED CHANGE ════════
In the input photo the person is at approximately ${"%.1f".format(i.currentBodyFatPercent)}% body fat at ${"%.1f".format(i.currentWeightKg)} kg.
Render them at approximately ${"%.1f".format(i.targetBodyFatPercent)}% body fat at ${"%.1f".format(i.targetWeightKg)} kg.

That is a reduction of ${"%.1f".format(bfDelta)} percentage points of body fat — about ${"%.1f".format(fatLostKg)} kg of fat
lost, and nothing else. Represent it as a THINNER LAYER OF SUBCUTANEOUS FAT over the abdomen,
flanks, lower back, chest and face. The waistline narrows accordingly, and the shape of the
muscle that is already underneath shows through a little more clearly than before.

════════ STAGE BUDGET — this frame is $progressPercent% of the way to the end state ════════
The complete sequence ends at ${"%.1f".format(i.finalBodyFatPercent)}% body fat at ${"%.1f".format(i.finalWeightKg)} kg.
Show only the portion of that change which belongs to this frame.

$restraint

════════ MUSCLE — DO NOT ADD ANY ════════
Lean body mass does NOT increase anywhere in this sequence: approximately ${"%.1f".format(targetLeanMassKg)} kg in
this frame, against ${"%.1f".format(sourceLeanMassKg)} kg in the input photo. This is fat loss, not muscle gain.
- Do NOT increase muscle size, width, thickness or mass anywhere.
- Shoulder width, arm circumference, chest size and leg size stay exactly as they are in the
  input photo.
- Do NOT broaden the frame or exaggerate the V-taper.
- Any increase in visible definition must come ONLY from the thinner fat layer revealing muscle
  that is already present in the input photo.

════════ CALIBRATION ════════
Do NOT render a fitness model, an influencer physique, or a bodybuilder. Do not drift toward a
generic "fit person" — the result must remain recognisably THIS person's body, with this
person's proportions, bone structure and muscle shape, simply carrying less fat.
The output should be believable as an ordinary photograph this person could take of themselves
after losing ${"%.1f".format(fatLostKg)} kg of fat.
""".trimIndent()
    }
}
