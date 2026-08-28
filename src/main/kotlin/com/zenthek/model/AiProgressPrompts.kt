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
     * v2 replaces v1. It took two passes, and the discarded one is recorded here because
     * together they bracket the useful calibration from both sides:
     *
     *  - **v1 (shipped)** applied one full-strength "add muscle / be optimistic" instruction to
     *    every rung regardless of position. The first rung therefore rendered nearly the end
     *    state (a visible four-pack from a soft midsection on a nominal 2.3 pp change), all
     *    rungs landed on the same "lean fit person" attractor, and the ordering between them
     *    was generation noise — rung 1 could read leaner than rung 2.
     *  - **A first attempt at v2 (discarded before release)** fixed the ordering by capping how
     *    much change each rung could show, but capped the *whole ladder*: it damped the final
     *    rung too ("must remain an ordinary, healthy, believable physique", "if you are unsure,
     *    change LESS"). Correctly ordered and unmotivating — the goal frame stopped looking
     *    like a goal.
     *
     * CACHE WARNING: that discarded attempt also carried `promptVersion = 2` and was run
     * against prod once during calibration. Any `ai_progress_ladder` row with
     * `prompt_version = 2` created before this version shipped will cache-hit on the same
     * photo + targets and serve the old, damped images. Delete such rows via
     * `DELETE /api/progress/ladders/{id}` so the storage blobs go with them.
     *
     * This version keeps that gradient mechanism and drops the global damping:
     *
     *  1. Every frame is given the **same description of the ladder's end state**, then told
     *     where it sits between the input photo and that end state. Early frames are bounded
     *     by an explicit reference rather than by generic restraint, which is what makes the
     *     ordering hold without flattening the sequence.
     *  2. The **final frame is explicitly the payoff** and is told not to hedge.
     *  3. Muscle is allowed back, but as a **gradient** rather than v1's constant: negligible
     *     at the first rung, modest and real at the last. This is the "slightly too positive
     *     muscle" complaint addressed by scaling rather than by prohibition.
     *
     * Scene-preservation constraints are new in v2. They are prompt-side only — the Gemini
     * path still sends no `imageConfig`, so aspect ratio and framing stay model-chosen and
     * background drift is not fixed here.
     *
     * **Tuning:** [stageGoal] and [muscleAllowance] below are the two calibration dials. Make
     * the ladder more aspirational by strengthening the final-frame wording in both; make it
     * more conservative by strengthening the "short of the goal state" wording in the
     * non-final branches. Any edit needs a new version entry + a `promptVersion` bump in
     * `loadAiProgressProjectionConfig()`, since `promptVersion` is part of the cache key.
     */
    private fun buildV2(i: PromptInputs): String {
        val steps = i.numRungs.coerceAtLeast(1)
        val step = i.stepIndex.coerceIn(1, steps)
        val isFinal = step >= steps
        val progressPercent = (100.0 * step / steps).toInt()

        val sourceLeanMassKg = i.currentWeightKg * (1.0 - i.currentBodyFatPercent / 100.0)
        val targetLeanMassKg = i.targetWeightKg * (1.0 - i.targetBodyFatPercent / 100.0)
        val fatNowKg = i.currentWeightKg * (i.currentBodyFatPercent / 100.0)
        val fatThenKg = i.targetWeightKg * (i.targetBodyFatPercent / 100.0)
        val fatLostKg = (fatNowKg - fatThenKg).coerceAtLeast(0.0)
        val bfDelta = i.currentBodyFatPercent - i.targetBodyFatPercent

        // Calibration dial 1 — how much of the transformation this frame may show.
        val stageGoal = when {
            isFinal -> """
This is the FINAL frame: the goal state itself, and the image the person is working toward.
Show the FULL transformation and make it genuinely impressive — the lean, athletic,
well-conditioned physique described above, rendered convincingly.
Do not hedge, soften or hold this frame back. The only limit is that it must unmistakably
remain the same person: same face, same bone structure, same height, same natural proportions.
""".trimIndent()

            step == 1 -> """
This is the FIRST frame, $progressPercent% of the way toward that goal state.
The change must be clearly visible and encouraging — a noticeably slimmer waist, a flatter
abdomen, a slightly more sculpted face and jawline. It should read as real, motivating
progress, not as an unchanged photo.
But it is the first step, not the destination: keep it clearly SHORT of the goal state. The
shape of the underlying muscle may begin to show through, but abdominal definition must not
yet be sharp or fully separated.
If you are unsure, make this frame clearly distinct from BOTH the input photo and the goal
state — it must sit visibly between them.
""".trimIndent()

            else -> """
This is an INTERMEDIATE frame, $progressPercent% of the way toward that goal state.
Show clear, substantial, obviously visible progress — meaningfully leaner and more defined
than the input photo — while staying visibly SHORT of the goal state. Abdominal definition
should be emerging and partly visible here, not yet complete.
If you are unsure, make this frame clearly distinct from BOTH the input photo and the goal
state — it must sit visibly between them.
""".trimIndent()
        }

        // Calibration dial 2 — how much muscle may be added at this stage. v1 applied the
        // final-stage allowance to every rung; that was the "too positive muscle" complaint.
        val muscleAllowance = when {
            isFinal -> """
At this final stage a modest but genuine increase in muscle fullness and hardness is
appropriate — the look of someone who kept training hard three to four times a week
throughout the fat loss.
""".trimIndent()

            step == 1 -> """
At this early stage muscle size is essentially unchanged. The improvement comes almost
entirely from carrying less fat.
""".trimIndent()

            else -> """
At this stage a slight increase in muscle fullness is appropriate — clearly less than at the
final frame.
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

════════ THE GOAL STATE — where this sequence ends ════════
The sequence ends at approximately ${"%.1f".format(i.finalBodyFatPercent)}% body fat at ${"%.1f".format(i.finalWeightKg)} kg: a lean, athletic,
well-conditioned version of this same person — visible abdominal definition, a distinctly
narrower waist, a defined V-taper, and sculpted shoulders and arms.
Every frame in the sequence is measured against that end point.

════════ THIS FRAME ════════
In the input photo the person is at approximately ${"%.1f".format(i.currentBodyFatPercent)}% body fat at ${"%.1f".format(i.currentWeightKg)} kg.
Render them at approximately ${"%.1f".format(i.targetBodyFatPercent)}% body fat at ${"%.1f".format(i.targetWeightKg)} kg — a reduction of
${"%.1f".format(bfDelta)} percentage points, about ${"%.1f".format(fatLostKg)} kg of fat lost.

Represent that primarily as a THINNER LAYER OF SUBCUTANEOUS FAT over the abdomen, flanks,
lower back, chest and face. The waistline narrows accordingly and the shape of the muscle
underneath shows through more clearly than before.

$stageGoal

════════ MUSCLE ════════
The weight targets are close to lean-mass-neutral — about ${"%.1f".format(targetLeanMassKg)} kg of lean mass in this
frame against ${"%.1f".format(sourceLeanMassKg)} kg in the input photo — so most of the visible change must come from
revealing muscle that is already there, not from adding new mass.

$muscleAllowance

Regardless of stage:
- Do NOT broaden the skeletal frame or widen the shoulders beyond a natural amount.
- Do NOT render bodybuilder or competition-stage mass.
- Keep this person's own limb proportions and natural muscle shape.

════════ CALIBRATION ════════
The result must remain unmistakably THIS person — same face, bone structure, height, limb
proportions and natural muscle shape. Do not substitute a generic fitness-model body.
Within that constraint the projection is meant to be motivating: render the best realistic
version of this person at ${"%.1f".format(i.targetBodyFatPercent)}% body fat, not a cautious or understated one.
""".trimIndent()
    }
}
