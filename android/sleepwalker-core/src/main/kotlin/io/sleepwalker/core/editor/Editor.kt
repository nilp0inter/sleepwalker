package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TextRenderingFailure
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Stateful Editor for reconciling a target text field via complete
 * snapshot writes.
 *
 * The only public text-mutating operation is [setText], which accepts
 * a complete desired document snapshot. Internal state (assumed target
 * document, caret, program state, revision, synchronization lifecycle)
 * is hidden from callers.
 *
 * ## State machine
 *
 * ```
 * Uninitialized ──setText──▶ Planning ──plan ok──▶ Executing ──all acked──▶ Synced
 *                                │                      │
 *                                │ plan/abi fail        │ partial ack
 *                                ▼                      ▼
 *                              Failed                Unknown (terminal)
 * ```
 *
 * - **Failed**: pre-execution planning/validation failure. The target
 *   is untouched; the Editor is recoverable (next [setText] may succeed).
 * - **Unknown**: terminal. A plan began executing but did not complete.
 *   The Editor rejects all further [setText] calls until [reset].
 *
 * @property target   the loaded target behavior package.
 * @property executor the serialized executor that owns transport.
 * @property hid      the low-level HID primitive factory.
 */
class Editor internal constructor(
    private val target: TargetPackage,
    val executor: EditorExecutor,
    val hid: LowLevelHid,
) {
    val targetId: String get() = target.id
    val targetVersion: String get() = target.version
    private val textPlanner: TextPlanner = TextPlanner(hid = hid)
    private val luaHost: LuaHostAdapter = LuaHostAdapter(hid, textPlanner)

    // ── Internal state (hidden from public surface) ──

    private var currentState: EditorState = EditorState.Uninitialized
    private var assumedDocument: String = ""
    private var programState: ReadlineProgramState = ReadlineProgramState()
    private var lastRetainedPlan: RetainedPlan? = null
    private var initializationFailure: FailureClassification? = null
    private val commandLane = ReentrantLock(true)

    init {
        if (target.hostAbi != TargetPackage.HOST_ABI_VERSION) {
            initializationFailure = FailureClassification.AbiMismatch(
                expected = TargetPackage.HOST_ABI_VERSION,
                actual = target.hostAbi,
            )
        } else {
            try {
                luaHost.initialize(target)
            } catch (failure: LuaLoadException) {
                initializationFailure = FailureClassification.PlanningError(
                    failure.message ?: "Target package initialization failed",
                )
            }
        }
    }

    // ── Public API ──

    /**
     * Reconcile the target field to [text].
     *
     * Accepts a complete desired snapshot — the Editor computes the
     * transition from its assumed current document to [text] via
     * LCP/LCS diff and the target package's planning function.
     *
     * Concurrent calls serialize in arrival order; there is no
     * coalescing and plan computation never interleaves.
     *
     * @param text the complete desired document content. Must be
     *   printable ASCII (0x20–0x7E) and single-line.
     * @return [EditorResult.Synced] on success or
     *   [EditorResult.EditorFailure] on failure.
     */
    fun setText(text: String): EditorResult = commandLane.withLock {
        setTextLocked(text)
    }

    private fun setTextLocked(text: String): EditorResult {
        initializationFailure?.let { failure ->
            return retainFailure(text, failure)
        }

        // ── Guard: Unknown is terminal ──
        if (currentState == EditorState.Unknown) {
            return retainFailure(
                text,
                FailureClassification.EnvironmentFailure(
                    "Editor is in Unknown state; requires explicit reset()",
                ),
                resultingState = EditorState.Unknown,
            )
        }

        currentState = EditorState.Planning

        // ── Step 1: Validate text constraints ──
        val validationFailure = validateText(text)
        if (validationFailure != null) {
            return retainFailure(text, validationFailure)
        }

        // ── Step 2: Compute LCP/LCS diff ──
        val diff = computeDiff(assumedDocument, text)
        // Identical complete snapshots are true no-ops: no Lua invocation,
        // execution, revision change, or target-state mutation.
        if (assumedDocument == text) {
            val retained = RetainedPlan(
                abiVersion = TargetPackage.HOST_ABI_VERSION,
                targetId = target.id,
                targetVersion = target.version,
                assumedState = programState,
                desiredText = text,
                predictedState = programState,
                predictedPoint = programState.point,
                ops = emptyList(),
            )
            lastRetainedPlan = retained
            currentState = EditorState.Synced
            EditorTrace.sink.record(
                VerificationEntry(
                    abiVersion = TargetPackage.HOST_ABI_VERSION,
                    targetId = target.id,
                    targetVersion = target.version,
                    assumedState = programState,
                    desiredText = text,
                    lcp = diff.lcp,
                    oldMid = diff.oldMid,
                    newMid = diff.newMid,
                    predictedState = programState,
                    ops = emptyList(),
                    classification = null,
                )
            )
            return EditorResult.Synced(text, emptyList())
        }

        // ── Step 3: Validate newMid representability ──
        if (diff.newMid.isNotEmpty()) {
            val probe = textPlanner.plan(diff.newMid, HostProfile.LINUX_US)
            if (!probe.ok) {
                val glyph = when (val f = probe.failure!!) {
                    is TextRenderingFailure.UnrepresentableGlyph -> f.ch
                    is TextRenderingFailure.MissingLayout -> '?'
                }
                return retainFailure(
                    text,
                    FailureClassification.UnrepresentableContent(glyph),
                    diff,
                )
            }
        }

        // ── Step 4: Plan transition via Lua target ──
        val planResult = luaHost.planTransition(
            current = assumedDocument,
            desired = text,
            lcp = diff.lcp,
            oldMid = diff.oldMid,
            newMid = diff.newMid,
            state = programState,
        )
        if (!planResult.ok) {
            return retainFailure(text, planResult.failure!!, diff)
        }

        // ── Step 5: Validate prediction ──
        val nextState = planResult.nextState!!
        if (nextState.buffer != text || nextState.point != diff.lcp + diff.newMid.length) {
            return retainFailure(
                text,
                FailureClassification.InconsistentPrediction(
                    expected = text,
                    predicted = nextState.buffer,
                ),
                diff,
                nextState,
                planResult.ops,
            )
        }

        val ops = planResult.ops

        // A non-identical transition cannot be delivered by an empty plan.
        if (ops.isEmpty()) {
            return retainFailure(
                text,
                FailureClassification.PlanningError(
                    "Target produced an empty plan for a mutating transition",
                ),
                diff,
                nextState,
            )
        }

        // ── Step 7: Execute ──
        currentState = EditorState.Executing
        val outcome = executor.execute(ops)

        return when (outcome) {
            is ExecutionOutcome.Delivered -> {
                // Capture pre-transition state for retention and trace
                val priorState = programState

                // Commit predicted state
                programState = nextState
                assumedDocument = text
                lastRetainedPlan = RetainedPlan(
                    abiVersion = TargetPackage.HOST_ABI_VERSION,
                    targetId = target.id,
                    targetVersion = target.version,
                    assumedState = priorState,
                    desiredText = text,
                    predictedState = nextState,
                    predictedPoint = diff.lcp + diff.newMid.length,
                    ops = ops,
                )
                currentState = EditorState.Synced

                // Verification trace (package-level sink, no public exposure)
                EditorTrace.sink.record(
                    VerificationEntry(
                        abiVersion = TargetPackage.HOST_ABI_VERSION,
                        targetId = target.id,
                        targetVersion = target.version,
                        assumedState = priorState,
                        desiredText = text,
                        lcp = diff.lcp,
                        oldMid = diff.oldMid,
                        newMid = diff.newMid,
                        predictedState = nextState,
                        ops = ops,
                        classification = null,
                    )
                )

                EditorResult.Synced(text, ops)
            }

            is ExecutionOutcome.Partial -> retainFailure(
                text = text,
                classification = outcome.reason,
                diff = diff,
                predictedState = nextState,
                ops = ops,
                resultingState = EditorState.Unknown,
            )
        }
    }

    private fun retainFailure(
        text: String,
        classification: FailureClassification,
        diff: DiffResult = computeDiff(assumedDocument, text),
        predictedState: ReadlineProgramState = programState,
        ops: List<LowLevelOp> = emptyList(),
        resultingState: EditorState = EditorState.Failed,
    ): EditorResult.EditorFailure {
        lastRetainedPlan = RetainedPlan(
            abiVersion = TargetPackage.HOST_ABI_VERSION,
            targetId = target.id,
            targetVersion = target.version,
            assumedState = programState,
            desiredText = text,
            predictedState = predictedState,
            predictedPoint = predictedState.point,
            ops = ops,
        )
        currentState = resultingState
        EditorTrace.sink.record(
            VerificationEntry(
                abiVersion = TargetPackage.HOST_ABI_VERSION,
                targetId = target.id,
                targetVersion = target.version,
                assumedState = programState,
                desiredText = text,
                lcp = diff.lcp,
                oldMid = diff.oldMid,
                newMid = diff.newMid,
                predictedState = predictedState,
                ops = ops,
                classification = classification,
            )
        )
        return EditorResult.EditorFailure(
            requestedDocument = text,
            classification = classification,
            plan = ops.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Read-only public state name.
     *
     * Returns one of [EditorState.Uninitialized], [EditorState.Synced],
     * [EditorState.Failed], or [EditorState.Unknown]. The transient
     * states [EditorState.Planning] and [EditorState.Executing] are
     * never returned because [setText] is synchronous.
     */
    fun state(): EditorState = commandLane.withLock { currentState }

    /**
     * Explicit reset to empty known state.
     *
     * Resets the assumed document, program state, and revision to
     * empty/zero, and transitions to [EditorState.Uninitialized].
     *
     * This is the only recovery path from [EditorState.Unknown].
     * The Lua host adapter is NOT re-initialised; the target package
     * remains loaded.
     */
    fun reset() = commandLane.withLock {
        currentState = EditorState.Uninitialized
        assumedDocument = ""
        programState = ReadlineProgramState()
        lastRetainedPlan = null
    }

    // ── Internal / verification access ──

    /**
     * Internal verification state snapshot.
     *
     * Package-level visibility for HIL and diagnostics. Exposes the
     * assumed document, program state, and retained plan that are
     * deliberately absent from the public [EditorResult] type.
     */
    internal val verificationState: VerificationState
        get() = VerificationState(
            state = currentState,
            assumedDocument = assumedDocument,
            programState = programState,
            lastPlan = lastRetainedPlan,
            luaPlanCount = lastRetainedPlan?.ops?.size ?: 0,
        )

    /**
     * Snapshot of the Editor's internal state for verification purposes.
     *
     * @property state           current Editor state.
     * @property assumedDocument the Editor's assumed target document text.
     * @property programState    the committed target program state.
     * @property lastPlan        the last retained plan, or null.
     * @property luaPlanCount    number of ops in the last Lua-produced plan.
     */
    data class VerificationState(
        val state: EditorState,
        val assumedDocument: String,
        val programState: ReadlineProgramState,
        val lastPlan: RetainedPlan?,
        val luaPlanCount: Int,
    )

    // ── Text validation ──

    /**
     * Validate [text] against the target package's declared constraints.
     *
     * Checks:
     * - Single-line (no CR, LF)
     * - ASCII printable (0x20–0x7E)
     */
    private fun validateText(text: String): FailureClassification? {
        if (text.contains('\n') || text.contains('\r')) {
            return FailureClassification.UnsupportedBehavior(
                "multi-line content not supported by ${target.id}"
            )
        }
        for (ch in text) {
            val code = ch.code
            if (code < 0x20 || code > 0x7E) {
                return FailureClassification.UnrepresentableContent(ch)
            }
        }
        return null
    }
}

/**
 * Retained plan record for verification and diagnostics.
 *
 * @property abiVersion       host ABI version used to generate the plan.
 * @property targetId         target package identity.
 * @property assumedState     the Editor's committed program state before
 *                            the transition.
 * @property desiredText      the complete snapshot the caller requested.
 * @property predictedState   the target package's predicted next program
 *                            state.
 * @property predictedPoint   predicted caret position after execution
 *                            (lcp + newMid.length).
 * @property ops              the ordered low-level keyboard operations.
 */
data class RetainedPlan(
    val abiVersion: Int,
    val targetId: String,
    val targetVersion: String,
    val assumedState: ReadlineProgramState,
    val desiredText: String,
    val predictedState: ReadlineProgramState,
    val predictedPoint: Int,
    val ops: List<LowLevelOp>,
)
