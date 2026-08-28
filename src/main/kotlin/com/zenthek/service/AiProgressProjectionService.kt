package com.zenthek.service

import com.zenthek.ai.GatekeeperVerdict
import com.zenthek.ai.ProgressGatekeeperClient
import com.zenthek.config.AiProgressProjectionConfig
import com.zenthek.config.ImageGenerationProvider
import com.zenthek.model.AiProgressLadderInsertPayload
import com.zenthek.model.AiProgressLadderRow
import com.zenthek.model.AiProgressLadderRungInsertPayload
import com.zenthek.model.AiProgressPrompts
import com.zenthek.model.BodyFatSource
import com.zenthek.model.LadderDoneEventDto
import com.zenthek.model.LadderErrorEventDto
import com.zenthek.model.LadderResolvedDto
import com.zenthek.model.LadderResolvedFieldDto
import com.zenthek.model.LadderStatus
import com.zenthek.model.LadderStatusEventDto
import com.zenthek.model.ProjectionRungDto
import com.zenthek.model.ResolvedFieldSource
import com.zenthek.model.ResolvedLadderInputs
import com.zenthek.model.RungKind
import com.zenthek.model.RungStatus
import com.zenthek.model.toDto
import com.zenthek.upstream.imageedit.ImageEditModerationException
import com.zenthek.upstream.imageedit.ProgressImageEditClient
import com.zenthek.upstream.supabase.AiProgressLadderGateway
import com.zenthek.upstream.supabase.SupabaseAdminGateway
import io.ktor.http.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant

/**
 * Result of an attempted DELETE against /api/progress/ladders/{id}.
 */
sealed class DeleteLadderResult {
    object Deleted : DeleteLadderResult()
    object NotFoundOrNotOwned : DeleteLadderResult()
}

/**
 * Callback the route handler implements to push SSE events down the wire. The orchestrator
 * never touches the response channel directly — separation lets us unit-test the flow with
 * a list-collecting emitter.
 */
fun interface LadderEventEmitter {
    suspend fun emit(eventName: String, payload: String)
}

/**
 * Orchestrates the bytes-in → ladder-out flow described in the plan:
 *
 *   1. hash bytes
 *   2. resolve weight / target weight / target body-fat from request fallbacks
 *   3. gatekeeper (single Gemini call, returns verdict + body-fat estimate)
 *   4. resolve current body-fat from request → gatekeeper estimate (no formula fallback)
 *   5. compute request_key from RESOLVED values, INSERT ON CONFLICT DO NOTHING
 *   6. upload source bytes as rung #0 (kind=SOURCE)
 *   7. fan out N parallel gpt-image-2 edit calls bounded by Semaphore
 *   8. as each rung lands: upload to storage, insert rung row, emit SSE
 *
 * Cache + dedup model: cross-instance dedup is the unique index `(user_id, request_key)`.
 * Same-instance concurrent calls with the same key serialize naturally because the second
 * insert sees the row and falls into the cache-HIT path.
 */
class AiProgressProjectionService(
    private val ladderGateway: AiProgressLadderGateway,
    private val storageGateway: SupabaseAdminGateway,
    private val gatekeeper: ProgressGatekeeperClient,
    private val imageEdit: ProgressImageEditClient,
    private val config: AiProgressProjectionConfig,
) {
    private val log = LoggerFactory.getLogger(AiProgressProjectionService::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Streaming generate. The route handler:
     *   1. opens an SSE stream
     *   2. calls this method with a ready-to-use [emitter]
     *   3. the method emits status / rung / done / error events, then returns
     *
     * Throws nothing user-facing — failures are emitted as `error` events. The route
     * handler should still wrap in try/catch to convert any unexpected exception into a
     * generic SSE error event before closing.
     */
    suspend fun generate(
        userId: String,
        sourceBytes: ByteArray,
        mimeType: String,
        requestedCurrentWeightKg: Double?,
        requestedCurrentBodyFatPercent: Double?,
        requestedTargetWeightKg: Double?,
        requestedTargetBodyFatPercent: Double?,
        emitter: LadderEventEmitter,
        // Returns false once the SSE client has disconnected. We probe this before the
        // expensive OpenAI fan-out so a flaky network drop doesn't burn N image-edit calls
        // for a request the user can no longer see results for.
        isClientConnected: () -> Boolean = { true },
        // Internal — set when we re-enter generate() after cleaning up a stale orphan.
        // Prevents an unbounded recursion if cleanup leaves the slot stuck somehow.
        afterOrphanCleanup: Boolean = false,
    ) {
        // 1. Validate basic inputs.
        if (sourceBytes.size > config.maxUploadBytes) {
            emitError(emitter, "Image exceeds maximum size of ${config.maxUploadBytes} bytes", "UPLOAD_TOO_LARGE")
            return
        }
        if (mimeType.lowercase() !in config.allowedMimeTypes) {
            emitError(emitter, "Unsupported image mime type: $mimeType", "UNSUPPORTED_MIME_TYPE")
            return
        }

        // 2. Pre-resolve weight + targets (no AI needed).
        val baseWeightKg: Double
        val baseWeightSource: ResolvedFieldSource
        val targetWeightKg: Double
        val targetWeightSource: ResolvedFieldSource
        val targetBodyFatPercent: Double
        val targetBodyFatSource: ResolvedFieldSource
        try {
            val latestWeight = if (requestedCurrentWeightKg == null) ladderGateway.findLatestWeightEntry(userId) else null
            baseWeightKg = requestedCurrentWeightKg
                ?: latestWeight?.weightKg
                ?: return emitError(emitter, "No currentWeightKg provided and no weight_entry found for user", "MISSING_CURRENT_WEIGHT")
            baseWeightSource = if (requestedCurrentWeightKg != null) ResolvedFieldSource.REQUEST else ResolvedFieldSource.WEIGHT_ENTRY

            val userGoal = if (requestedTargetWeightKg == null || requestedTargetBodyFatPercent == null) {
                ladderGateway.findUserGoal(userId)
            } else null
            targetWeightKg = requestedTargetWeightKg
                ?: userGoal?.goalWeightKg
                ?: return emitError(emitter, "No targetWeightKg and no user_goal.goal_weight_kg", "MISSING_TARGET_WEIGHT")
            targetWeightSource = if (requestedTargetWeightKg != null) ResolvedFieldSource.REQUEST else ResolvedFieldSource.USER_GOAL

            targetBodyFatPercent = requestedTargetBodyFatPercent
                ?: userGoal?.bodyFatPercent
                ?: return emitError(emitter, "No targetBodyFatPercent and no user_goal.body_fat_percent", "MISSING_TARGET_BODY_FAT")
            targetBodyFatSource = if (requestedTargetBodyFatPercent != null) ResolvedFieldSource.REQUEST else ResolvedFieldSource.USER_GOAL
        } catch (e: Exception) {
            log.error("[PROJECTION] failed to resolve inputs", e)
            emitError(emitter, "Failed to resolve user data", "INPUT_RESOLUTION_FAILED")
            return
        }

        // 3. Gatekeeper (single Gemini call: gate verdict + body-fat estimate).
        val verdict: GatekeeperVerdict = try {
            gatekeeper.verify(sourceBytes, mimeType)
        } catch (e: Exception) {
            log.error("[PROJECTION] gatekeeper failure", e)
            emitError(emitter, "Photo validation failed", "GATEKEEPER_UNAVAILABLE")
            return
        }
        if (!verdict.isAcceptable) {
            log.info("[PROJECTION] gatekeeper rejected reasons={}", verdict.rejectionReasons)
            val msg = "Photo rejected: ${verdict.rejectionReasons.joinToString(", ")}".take(200)
            emitError(emitter, msg, "GATEKEEPER_REJECTED")
            return
        }

        // 4. Resolve current body-fat: request → gatekeeper estimate. No other fallback.
        val baseBodyFatPercent = requestedCurrentBodyFatPercent ?: verdict.estimatedBodyFatPercent
        if (baseBodyFatPercent == null) {
            emitError(
                emitter,
                "No currentBodyFatPercent provided and gatekeeper could not estimate one from the photo",
                "MISSING_CURRENT_BODY_FAT",
            )
            return
        }
        val baseBodyFatSource = if (requestedCurrentBodyFatPercent != null) BodyFatSource.REQUEST else BodyFatSource.GATEKEEPER_ESTIMATE
        val baseBodyFatConfidence = if (requestedCurrentBodyFatPercent != null) null else verdict.estimatedBodyFatConfidence

        val resolved = ResolvedLadderInputs(
            currentWeightKg = baseWeightKg,
            currentWeightSource = baseWeightSource,
            currentBodyFatPercent = baseBodyFatPercent,
            currentBodyFatSource = baseBodyFatSource,
            currentBodyFatConfidence = baseBodyFatConfidence,
            targetWeightKg = targetWeightKg,
            targetWeightSource = targetWeightSource,
            targetBodyFatPercent = targetBodyFatPercent,
            targetBodyFatSource = targetBodyFatSource,
        )

        // 5. Compute steps + request_key.
        val sourceContentHash = sha256Hex(sourceBytes)
        val numRungs = config.numRungs.coerceAtLeast(1)
        val stepBfDelta = (resolved.targetBodyFatPercent - resolved.currentBodyFatPercent) / numRungs
        val requestKey = computeRequestKey(
            userId = userId,
            sourceContentHash = sourceContentHash,
            resolved = resolved,
        )

        // 6. INSERT ON CONFLICT DO NOTHING. Falls back to cache HIT path.
        val insertPayload = AiProgressLadderInsertPayload(
            userId = userId,
            sourceContentHash = sourceContentHash,
            sourceWidth = null,
            sourceHeight = null,
            baseWeightKg = baseWeightKg,
            baseBodyFatPercent = baseBodyFatPercent,
            targetWeightKg = resolved.targetWeightKg,
            targetBodyFatPercent = resolved.targetBodyFatPercent,
            bodyFatSource = baseBodyFatSource.wireValue,
            stepBodyFatPercent = kotlin.math.abs(stepBfDelta),
            numSteps = numRungs,
            model = config.activeImageModel,
            quality = config.quality,
            size = config.size,
            promptVersion = config.promptVersion,
            requestKey = requestKey,
            status = "PENDING",
        )
        val freshLadder: AiProgressLadderRow? = ladderGateway.insertLadderIfAbsent(insertPayload)
        if (freshLadder == null) {
            // Cache HIT (or in-flight on another instance) — replay rungs and return.
            // If the existing row is a stale orphan from a prior crashed run (e.g. client
            // disconnect aborted the orchestration before any rung landed), self-heal:
            // delete it and re-enter generate(). One retry only; afterOrphanCleanup pins
            // recursion at depth 1 so a stuck slot can't loop forever.
            if (!afterOrphanCleanup) {
                val existing = ladderGateway.findByCacheKey(userId, requestKey)
                if (existing != null && isStaleOrphan(existing)) {
                    log.warn(
                        "[PROJECTION] stale orphan detected ladderId={} status={} ageMs={}; cleaning and retrying",
                        existing.id, existing.status, ageOfMs(existing.createdAt),
                    )
                    cleanupStaleOrphan(userId, existing)
                    return generate(
                        userId = userId,
                        sourceBytes = sourceBytes,
                        mimeType = mimeType,
                        requestedCurrentWeightKg = requestedCurrentWeightKg,
                        requestedCurrentBodyFatPercent = requestedCurrentBodyFatPercent,
                        requestedTargetWeightKg = requestedTargetWeightKg,
                        requestedTargetBodyFatPercent = requestedTargetBodyFatPercent,
                        emitter = emitter,
                        isClientConnected = isClientConnected,
                        afterOrphanCleanup = true,
                    )
                }
            }
            log.info("[PROJECTION] cache hit, replaying rungs userId={} requestKey={}", userId, requestKey.take(12))
            handleCacheHit(userId, requestKey, emitter, resolved)
            return
        }
        val ladder = freshLadder
        log.info("[PROJECTION] cache miss, generating ladderId={} userId={} requestKey={}", ladder.id, userId, requestKey.take(12))

        // 6.5. Emit `validating` then `generating` status events.
        emitStatus(emitter, ladder.id, "generating", numRungs + 1, 0, resolved)

        // 7. Update ladder to RUNNING + persist gatekeeper verdict.
        val verdictJson = json.encodeToJsonElement(GatekeeperVerdict.serializer(), verdict)
        runCatching {
            ladderGateway.updateLadder(
                ladderId = ladder.id,
                status = LadderStatus.RUNNING.name,
                gatekeeperVerdict = verdictJson,
            )
        }.onFailure { log.warn("[PROJECTION] could not patch ladder to RUNNING ladderId={}", ladder.id, it) }

        // 8. Upload source as rung #0.
        val sourcePath = rungStoragePath(userId, ladder.id, stepIndex = 0)
        try {
            storageGateway.uploadObject(
                bucket = AI_PROGRESS_LADDERS_BUCKET,
                path = sourcePath,
                bytes = sourceBytes,
                contentType = ContentType.parse(mimeType),
            )
        } catch (e: Exception) {
            log.error("[PROJECTION] source upload failed ladderId={}", ladder.id, e)
            ladderGateway.updateLadder(
                ladderId = ladder.id,
                status = LadderStatus.FAILED.name,
                failureCode = "source_upload",
            )
            emitError(emitter, "Could not store source photo", "SOURCE_UPLOAD_FAILED")
            return
        }
        val sourceRung = ladderGateway.insertRung(
            AiProgressLadderRungInsertPayload(
                ladderId = ladder.id,
                userId = userId,
                stepIndex = 0,
                kind = RungKind.SOURCE.name,
                projectedBodyFatPercent = baseBodyFatPercent,
                projectedWeightKg = baseWeightKg,
                storagePath = sourcePath,
                openAiModel = null,
                usageInputTokens = null,
                usageOutputTokens = null,
                usageCachedInputTokens = null,
                costMicros = null,
                status = RungStatus.SUCCEEDED.name,
                failureCode = null,
            )
        )
        emitRung(emitter, sourceRung.toDto())

        // 8.5. Final disconnect probe before the expensive fan-out. If the client has
        // walked away, hard-delete the ladder + source blob so the next attempt with the
        // same photo+targets isn't blocked by the (user_id, request_key) unique index
        // and `handleCacheHit` doesn't have an orphan PENDING row to choke on. We accept
        // the small cost of the gatekeeper call + source upload that already happened —
        // the OpenAI image edits are the spendy part and we skip them entirely.
        if (!isClientConnected()) {
            log.info("[PROJECTION] client disconnected before fan-out; tearing down ladderId={}", ladder.id)
            runCatching {
                storageGateway.removeStorageObjects(AI_PROGRESS_LADDERS_BUCKET, listOf(sourcePath))
            }.onFailure { log.warn("[PROJECTION] cleanup: source blob delete failed ladderId={}", ladder.id, it) }
            runCatching {
                ladderGateway.deleteOwnedLadder(userId, ladder.id)
            }.onFailure { log.warn("[PROJECTION] cleanup: ladder row delete failed ladderId={}", ladder.id, it) }
            return
        }

        // 9. Fan out projection rungs in parallel, bounded by Semaphore.
        val semaphore = Semaphore(config.maxParallelRungs)
        val projectionResults: List<RungAttemptOutcome> = coroutineScope {
            (1..numRungs).map { stepIndex ->
                async {
                    semaphore.withPermit {
                        runProjectionRung(
                            userId = userId,
                            ladderId = ladder.id,
                            stepIndex = stepIndex,
                            numRungs = numRungs,
                            sourceBytes = sourceBytes,
                            mimeType = mimeType,
                            resolved = resolved,
                            stepBfDelta = stepBfDelta,
                            emitter = emitter,
                        )
                    }
                }
            }.awaitAll()
        }

        // 10. Finalize ladder status.
        val successCount = projectionResults.count { it == RungAttemptOutcome.SUCCEEDED }
        val moderationCount = projectionResults.count { it == RungAttemptOutcome.FAILED_MODERATION }
        val ladderSucceeded = successCount >= ((numRungs + 1) / 2)  // ceil(numRungs/2)
        // If the ladder didn't succeed AND any rung was blocked by OpenAI's safety
        // system, surface MODERATION_BLOCKED so the client can show a tailored message.
        // In practice, moderation either blocks all rungs (same image + same prompt
        // structure) or none, so this branches cleanly.
        val moderationBlocked = !ladderSucceeded && moderationCount > 0
        val finalStatus = if (ladderSucceeded) LadderStatus.SUCCEEDED else LadderStatus.FAILED
        val finalFailureCode = when {
            ladderSucceeded -> null
            moderationBlocked -> "moderation_block"
            else -> "majority_rungs_failed"
        }
        runCatching {
            ladderGateway.updateLadder(
                ladderId = ladder.id,
                status = finalStatus.name,
                failureCode = finalFailureCode,
            )
        }.onFailure { log.warn("[PROJECTION] could not finalize ladder status ladderId={}", ladder.id, it) }

        when {
            ladderSucceeded -> emitter.emit("done", json.encodeToString(LadderDoneEventDto(ladderId = ladder.id, rungsCount = numRungs + 1)))
            moderationBlocked -> emitError(
                emitter,
                "Photo was rejected by OpenAI's safety system. Please try one wearing athletic clothing (shorts + t-shirt, sports bra + shorts) instead of underwear.",
                "MODERATION_BLOCKED",
            )
            else -> emitError(emitter, "Most projection rungs failed to generate", "GENERATION_FAILED")
        }
    }

    private suspend fun handleCacheHit(
        userId: String,
        requestKey: String,
        emitter: LadderEventEmitter,
        resolved: ResolvedLadderInputs,
    ) {
        val ladder = ladderGateway.findByCacheKey(userId, requestKey)
        if (ladder == null) {
            // Race lost the insert AND the row vanished — extreme edge case, treat as fresh failure.
            emitError(emitter, "Could not locate cached ladder", "CACHE_RACE_LOST")
            return
        }
        val rungs = ladderGateway.findRungs(ladder.id)
        emitStatus(emitter, ladder.id, "generating", rungs.size + (if (ladder.status == "RUNNING") 1 else 0), rungs.size, resolved)
        rungs.forEach { emitRung(emitter, it.toDto()) }
        when (ladder.status) {
            LadderStatus.SUCCEEDED.name -> emitter.emit("done", json.encodeToString(LadderDoneEventDto(ladderId = ladder.id, rungsCount = rungs.size)))
            LadderStatus.RUNNING.name, LadderStatus.PENDING.name -> {
                // The first inserter is still working. The client should subscribe to realtime
                // on ai_progress_ladder_rung filter ladder_id=eq.<id> to receive the rest.
                emitter.emit("done", json.encodeToString(LadderDoneEventDto(ladderId = ladder.id, rungsCount = rungs.size)))
            }
            else -> {
                // Map the persisted failure_code back to the SSE error code so a retry of
                // a moderation-blocked photo gets the same tailored message as the first
                // attempt (instead of a generic GENERATION_FAILED).
                val (sseCode, sseMessage) = when (ladder.failureCode) {
                    "moderation_block" -> "MODERATION_BLOCKED" to
                        "Photo was rejected by OpenAI's safety system. Please try one wearing athletic clothing (shorts + t-shirt, sports bra + shorts) instead of underwear."
                    else -> "GENERATION_FAILED" to (ladder.failureCode ?: "Generation previously failed")
                }
                emitError(emitter, sseMessage, sseCode)
            }
        }
    }

    /** Single projection rung: OpenAI edit → storage upload → DB insert → SSE rung event. */
    private suspend fun runProjectionRung(
        userId: String,
        ladderId: String,
        stepIndex: Int,
        numRungs: Int,
        sourceBytes: ByteArray,
        mimeType: String,
        resolved: ResolvedLadderInputs,
        stepBfDelta: Double,
        emitter: LadderEventEmitter,
    ): RungAttemptOutcome {
        val projectedBf = resolved.currentBodyFatPercent + stepBfDelta * stepIndex
        // Linearly interpolate weight too — same number of steps.
        val weightDelta = (resolved.targetWeightKg - resolved.currentWeightKg) / numRungs
        val projectedWeight = resolved.currentWeightKg + weightDelta * stepIndex

        val prompt = AiProgressPrompts.render(
            version = config.promptVersion,
            inputs = AiProgressPrompts.PromptInputs(
                currentBodyFatPercent = resolved.currentBodyFatPercent,
                currentWeightKg = resolved.currentWeightKg,
                targetBodyFatPercent = projectedBf,
                targetWeightKg = projectedWeight,
                // v2+ uses the stage position and the ladder end state to bound how much
                // change may be visible in this frame. v1 ignores these.
                stepIndex = stepIndex,
                numRungs = numRungs,
                finalBodyFatPercent = resolved.targetBodyFatPercent,
                finalWeightKg = resolved.targetWeightKg,
            ),
        )
        val sourceFilename = if (mimeType.contains("png", ignoreCase = true)) "source.png" else "source.jpg"

        val editResult: ProgressImageEditClient.Result = try {
            imageEdit.edit(
                sourceBytes = sourceBytes,
                sourceMimeType = mimeType,
                sourceFilename = sourceFilename,
                prompt = prompt,
                userId = userId,
            )
        } catch (e: ImageEditModerationException) {
            log.warn("[PROJECTION] rung blocked by upstream safety system ladderId={} step={} msg={}", ladderId, stepIndex, e.message)
            persistFailedRung(userId, ladderId, stepIndex, projectedBf, projectedWeight, "moderation_block", emitter)
            return RungAttemptOutcome.FAILED_MODERATION
        } catch (e: Exception) {
            log.warn("[PROJECTION] rung edit failed ladderId={} step={}", ladderId, stepIndex, e)
            persistFailedRung(userId, ladderId, stepIndex, projectedBf, projectedWeight, "image_edit_failed", emitter)
            return RungAttemptOutcome.FAILED
        }

        val storagePath = rungStoragePath(userId, ladderId, stepIndex)
        try {
            storageGateway.uploadObject(
                bucket = AI_PROGRESS_LADDERS_BUCKET,
                path = storagePath,
                bytes = editResult.bytes,
                contentType = outputContentType(),
            )
        } catch (e: Exception) {
            log.warn("[PROJECTION] rung upload failed ladderId={} step={}", ladderId, stepIndex, e)
            persistFailedRung(userId, ladderId, stepIndex, projectedBf, projectedWeight, "storage_upload", emitter)
            return RungAttemptOutcome.FAILED
        }

        val costMicros = computeCostMicros(editResult)
        val rung = ladderGateway.insertRung(
            AiProgressLadderRungInsertPayload(
                ladderId = ladderId,
                userId = userId,
                stepIndex = stepIndex,
                kind = RungKind.PROJECTION.name,
                projectedBodyFatPercent = projectedBf,
                projectedWeightKg = projectedWeight,
                storagePath = storagePath,
                openAiModel = config.activeImageModel,
                usageInputTokens = editResult.usageInputTokens,
                usageOutputTokens = editResult.usageOutputTokens,
                usageCachedInputTokens = editResult.usageCachedInputTokens,
                costMicros = costMicros,
                status = RungStatus.SUCCEEDED.name,
                failureCode = null,
            )
        )
        emitRung(emitter, rung.toDto())
        return RungAttemptOutcome.SUCCEEDED
    }

    private suspend fun persistFailedRung(
        userId: String,
        ladderId: String,
        stepIndex: Int,
        projectedBf: Double,
        projectedWeight: Double,
        failureCode: String,
        emitter: LadderEventEmitter,
    ) {
        val rung = runCatching {
            ladderGateway.insertRung(
                AiProgressLadderRungInsertPayload(
                    ladderId = ladderId,
                    userId = userId,
                    stepIndex = stepIndex,
                    kind = RungKind.PROJECTION.name,
                    projectedBodyFatPercent = projectedBf,
                    projectedWeightKg = projectedWeight,
                    storagePath = null,
                    openAiModel = config.activeImageModel,
                    usageInputTokens = null,
                    usageOutputTokens = null,
                    usageCachedInputTokens = null,
                    costMicros = null,
                    status = RungStatus.FAILED.name,
                    failureCode = failureCode,
                )
            )
        }.getOrNull()
        if (rung != null) emitRung(emitter, rung.toDto())
    }

    /**
     * Synchronous DELETE: read rung paths → wipe blobs → delete row (cascade wipes rungs).
     * 404 from storage is treated as success (existing pattern in [SupabaseAdminGateway]).
     */
    suspend fun delete(userId: String, ladderId: String): DeleteLadderResult {
        // First: prove ownership AND collect storage paths in a single query.
        val storagePaths = ladderGateway.findRungStoragePathsForOwnedLadder(userId, ladderId)
        if (storagePaths.isEmpty()) {
            // Could mean: not owned, doesn't exist, or owned-but-no-rungs (e.g. PENDING ladder
            // pre-source-upload). The "not owned / doesn't exist" case is the common one,
            // and there's no harm in a no-op delete attempt for the rare "no rungs" case.
            // Try the row delete; if it affects 0 rows we still return NotFoundOrNotOwned.
            return runCatching {
                ladderGateway.deleteOwnedLadder(userId, ladderId)
                DeleteLadderResult.NotFoundOrNotOwned
            }.getOrElse { DeleteLadderResult.NotFoundOrNotOwned }
        }
        // Storage first, DB second — leaving DB rows pointing at deleted blobs is
        // recoverable (404-tolerant retry); leaving blobs without DB rows is a leak.
        storageGateway.removeStorageObjects(bucket = AI_PROGRESS_LADDERS_BUCKET, fullPaths = storagePaths)
        ladderGateway.deleteOwnedLadder(userId, ladderId)
        return DeleteLadderResult.Deleted
    }

    /* ------------------------------------------------------------------------- helpers */

    private suspend fun emitStatus(
        emitter: LadderEventEmitter,
        ladderId: String,
        phase: String,
        rungsTotal: Int,
        rungsReady: Int,
        resolved: ResolvedLadderInputs,
    ) {
        val event = LadderStatusEventDto(
            phase = phase,
            rungsTotal = rungsTotal,
            rungsReady = rungsReady,
            ladderId = ladderId,
            resolved = LadderResolvedDto(
                currentWeightKg = LadderResolvedFieldDto(
                    value = resolved.currentWeightKg,
                    source = resolved.currentWeightSource.wireValue,
                ),
                currentBodyFatPercent = LadderResolvedFieldDto(
                    value = resolved.currentBodyFatPercent,
                    source = resolved.currentBodyFatSource.wireValue,
                    confidence = resolved.currentBodyFatConfidence,
                ),
                targetWeightKg = LadderResolvedFieldDto(
                    value = resolved.targetWeightKg,
                    source = resolved.targetWeightSource.wireValue,
                ),
                targetBodyFatPercent = LadderResolvedFieldDto(
                    value = resolved.targetBodyFatPercent,
                    source = resolved.targetBodyFatSource.wireValue,
                ),
            ),
        )
        emitter.emit("status", json.encodeToString(event))
    }

    private suspend fun emitRung(emitter: LadderEventEmitter, dto: ProjectionRungDto) {
        emitter.emit("rung", json.encodeToString(dto))
    }

    private suspend fun emitError(emitter: LadderEventEmitter, message: String, code: String) {
        emitter.emit("error", json.encodeToString(LadderErrorEventDto(message = message, code = code)))
    }

    private fun rungStoragePath(userId: String, ladderId: String, stepIndex: Int): String {
        val ext = if (config.outputFormat.equals("png", ignoreCase = true)) "png" else "jpg"
        return "$userId/ladders/$ladderId/$stepIndex.$ext"
    }

    private fun outputContentType(): ContentType = when (config.outputFormat.lowercase()) {
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType.parse("image/webp")
        else -> ContentType.Image.JPEG
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Cache key. Uses RESOLVED values so that two `null`-BF requests on the same photo
     * collapse to the same key once the gatekeeper estimate fills both in.
     * The body-fat values are rounded to 1 decimal so tiny estimator drift doesn't miss the cache.
     */
    private fun computeRequestKey(
        userId: String,
        sourceContentHash: String,
        resolved: ResolvedLadderInputs,
    ): String {
        val parts = listOf(
            userId,
            sourceContentHash,
            config.activeImageModel,
            config.quality,
            config.size,
            config.promptVersion.toString(),
            "%.1f".format(resolved.currentWeightKg),
            "%.1f".format(resolved.currentBodyFatPercent),
            "%.1f".format(resolved.targetWeightKg),
            "%.1f".format(resolved.targetBodyFatPercent),
            config.numRungs.toString(),
        )
        return sha256Hex(parts.joinToString("|").toByteArray())
    }

    /**
     * Estimate cost in micro-dollars from token counts. Provider-specific because pricing
     * differs significantly. Returns null when usage isn't reported, OR for providers we
     * haven't priced yet (so we don't write a bogus 0 to the cost column).
     */
    private fun computeCostMicros(result: ProgressImageEditClient.Result): Long? = when (config.provider) {
        ImageGenerationProvider.OPENAI -> computeOpenAiCostMicros(result)
        // Gemini pricing TBD — leave null until we decide whether we're shipping it.
        ImageGenerationProvider.GEMINI -> null
    }

    /**
     * gpt-image-2 standard pricing:
     *   image input  $8/M     ⇒ 8 micros per token
     *   cached input $2/M     ⇒ 2 micros per token
     *   image output $30/M    ⇒ 30 micros per token
     */
    private fun computeOpenAiCostMicros(result: ProgressImageEditClient.Result): Long? {
        val input = result.usageInputTokens ?: return null
        val output = result.usageOutputTokens ?: return null
        val cached = result.usageCachedInputTokens ?: 0
        val uncachedInput = (input - cached).coerceAtLeast(0)
        return uncachedInput * 8L + cached * 2L + output * 30L
    }

    /**
     * A ladder is a "stale orphan" when it's been sitting in PENDING/RUNNING for longer
     * than [ORPHAN_GRACE_MS] without producing any projection rungs. The most common cause
     * is a client disconnect (or any other unhandled exception) that aborted the
     * orchestration before the OpenAI fan-out wrote anything useful. The grace period is
     * generous enough that a legitimate concurrent in-flight generation on another
     * instance won't be misidentified — gatekeeper + source upload + source-rung insert
     * all complete well within 30 s on a healthy path.
     */
    private fun isStaleOrphan(row: AiProgressLadderRow): Boolean {
        val terminal = row.status == LadderStatus.SUCCEEDED.name || row.status == LadderStatus.FAILED.name
        if (terminal) return false
        return ageOfMs(row.createdAt) > ORPHAN_GRACE_MS
    }

    private fun ageOfMs(createdAt: String?): Long {
        if (createdAt.isNullOrBlank()) return Long.MAX_VALUE
        return runCatching { System.currentTimeMillis() - Instant.parse(createdAt).toEpochMilli() }
            .getOrDefault(Long.MAX_VALUE)
    }

    private suspend fun cleanupStaleOrphan(userId: String, row: AiProgressLadderRow) {
        val rungs = runCatching { ladderGateway.findRungs(row.id) }.getOrDefault(emptyList())
        val blobs = rungs.mapNotNull { it.storagePath }
        if (blobs.isNotEmpty()) {
            runCatching {
                storageGateway.removeStorageObjects(AI_PROGRESS_LADDERS_BUCKET, blobs)
            }.onFailure { log.warn("[PROJECTION] orphan cleanup: blob delete failed ladderId={}", row.id, it) }
        }
        runCatching { ladderGateway.deleteOwnedLadder(userId, row.id) }
            .onFailure { log.warn("[PROJECTION] orphan cleanup: row delete failed ladderId={}", row.id, it) }
    }

    companion object {
        const val AI_PROGRESS_LADDERS_BUCKET = "ai-progress-ladders"

        // Grace window for an in-flight generation to make progress before its slot is
        // considered abandoned. Healthy path produces a source rung in well under 15 s.
        private const val ORPHAN_GRACE_MS = 30_000L
    }

    private enum class RungAttemptOutcome { SUCCEEDED, FAILED, FAILED_MODERATION }
}
