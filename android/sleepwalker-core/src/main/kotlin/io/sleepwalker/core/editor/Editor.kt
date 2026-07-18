package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TextRenderingFailure
import io.sleepwalker.core.protocol.Opcodes
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.protocol.HidUsage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Stateful Editor for reconciling a target text field via complete
 * snapshot writes.
 *
 * The only public text-mutating operation is [setText], which accepts
 * a complete desired document snapshot. Internal state is hidden from callers.
 */
class Editor internal constructor(
    private val target: TargetPackage,
    val executor: EditorExecutor,
    val hid: LowLevelHid,
    private val sharedModules: Map<String, String>,
    val policy: ExecutionPolicy = ExecutionPolicy.PRODUCTION,
    val profile: HostProfile = HostProfile.LINUX_US,
) {
    val targetId: String get() = target.id
    val targetVersion: String get() = target.version
    private val textPlanner: TextPlanner = TextPlanner(hid = hid)
    private val luaHost: LuaHostAdapter = LuaHostAdapter(hid, textPlanner, sharedModules)

    // ── Internal state ──
    private var currentState: EditorState = EditorState.Uninitialized
    private var assumedDocument: String = ""
    private var opaqueState: AbiValue = AbiValue.Null
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
                opaqueState = luaHost.runInitializer(target, "")
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
     */
    fun setText(text: String): EditorResult = commandLane.withLock {
        setTextLocked(text)
    }

    private fun setTextLocked(text: String): EditorResult {
        initializationFailure?.let { failure ->
            return retainFailure(text, failure, null)
        }

        if (currentState == EditorState.Unknown) {
            return retainFailure(
                text,
                FailureClassification.EnvironmentFailure(
                    "Editor is in Unknown state; requires explicit reset()",
                ),
                null,
                resultingState = EditorState.Unknown,
            )
        }

        currentState = EditorState.Planning

        val validationFailure = validateText(text)
        if (validationFailure != null) {
            return retainFailure(text, validationFailure, null)
        }

        // Invoke Lua planning
        val planResult = luaHost.runPlan(
            target = target,
            currentText = assumedDocument,
            desiredText = text,
            opaqueState = opaqueState,
            profile = profile
        )

        if (planResult is LuaInvocationResult.Failure) {
            return retainFailure(text, planResult.classification, null)
        }

        val success = planResult as LuaInvocationResult.Success
        val actions = success.actions
        val nextState = success.nextState
        val compileCache = success.compileCache

        // Check no-action rejection
        if (actions.isEmpty()) {
            if (text != assumedDocument || nextState != opaqueState) {
                return retainFailure(
                    text,
                    FailureClassification.PlanningError(
                        "Target produced an empty plan for a mutating transition"
                    ),
                    nextState,
                    actions
                )
            }

            // Valid true no-op
            val ops = emptyList<LowLevelOp>()
            lastRetainedPlan = RetainedPlan(
                abiVersion = TargetPackage.HOST_ABI_VERSION,
                targetId = target.id,
                targetVersion = target.version,
                targetSourceHash = target.sourceHash,
                currentText = assumedDocument,
                desiredText = text,
                opaqueInputState = opaqueState,
                opaqueOutputState = nextState,
                symbolicActions = actions,
                ops = ops,
                layoutId = profile.key,
                costMetricId = "op_count:1",
                policyId = policy.name,
                outcome = "COMMITTED"
            )
            currentState = EditorState.Synced

            EditorTrace.sink.record(
                VerificationEntry(
                    abiVersion = TargetPackage.HOST_ABI_VERSION,
                    targetId = target.id,
                    targetVersion = target.version,
                    targetSourceHash = target.sourceHash,
                    currentText = assumedDocument,
                    desiredText = text,
                    opaqueInputState = opaqueState,
                    opaqueOutputState = nextState,
                    symbolicActions = actions,
                    ops = ops,
                    layoutId = profile.key,
                    costMetricId = "op_count:1",
                    policyId = policy.name,
                    outcome = "COMMITTED",
                    classification = null
                )
            )
            return EditorResult.Synced(text, ops)
        }

        // Compile symbolic actions
        val ops = try {
            compileActions(actions, hid, profile, policy, compileCache)
        } catch (e: Exception) {
            return retainFailure(
                text,
                FailureClassification.PlanningError(
                    e.message ?: "Failed to compile symbolic actions"
                ),
                nextState,
                actions
            )
        }

        // Execute
        currentState = EditorState.Executing
        val outcome = executor.execute(ops)

        return when (outcome) {
            is ExecutionOutcome.Delivered -> {
                val priorDocument = assumedDocument
                val priorState = opaqueState

                // Commit desired text and returned state
                opaqueState = nextState
                assumedDocument = text

                lastRetainedPlan = RetainedPlan(
                    abiVersion = TargetPackage.HOST_ABI_VERSION,
                    targetId = target.id,
                    targetVersion = target.version,
                    targetSourceHash = target.sourceHash,
                    currentText = priorDocument,
                    desiredText = text,
                    opaqueInputState = priorState,
                    opaqueOutputState = nextState,
                    symbolicActions = actions,
                    ops = ops,
                    layoutId = profile.key,
                    costMetricId = "op_count:1",
                    policyId = policy.name,
                    outcome = "COMMITTED"
                )
                currentState = EditorState.Synced

                EditorTrace.sink.record(
                    VerificationEntry(
                        abiVersion = TargetPackage.HOST_ABI_VERSION,
                        targetId = target.id,
                        targetVersion = target.version,
                        targetSourceHash = target.sourceHash,
                        currentText = priorDocument,
                        desiredText = text,
                        opaqueInputState = priorState,
                        opaqueOutputState = nextState,
                        symbolicActions = actions,
                        ops = ops,
                        layoutId = profile.key,
                        costMetricId = "op_count:1",
                        policyId = policy.name,
                        outcome = "COMMITTED",
                        classification = null
                    )
                )

                EditorResult.Synced(text, ops)
            }

            is ExecutionOutcome.Partial -> {
                retainFailure(
                    text = text,
                    classification = outcome.reason,
                    predictedState = nextState,
                    symbolicActions = actions,
                    ops = ops,
                    resultingState = EditorState.Unknown
                )
            }
        }
    }

    private fun retainFailure(
        text: String,
        classification: FailureClassification,
        predictedState: AbiValue?,
        symbolicActions: List<SymbolicAction>? = null,
        ops: List<LowLevelOp> = emptyList(),
        resultingState: EditorState = EditorState.Failed,
    ): EditorResult.EditorFailure {
        val outcome = if (resultingState == EditorState.Unknown) "UNKNOWN" else "FAILED"
        lastRetainedPlan = RetainedPlan(
            abiVersion = TargetPackage.HOST_ABI_VERSION,
            targetId = target.id,
            targetVersion = target.version,
            targetSourceHash = target.sourceHash,
            currentText = assumedDocument,
            desiredText = text,
            opaqueInputState = opaqueState,
            opaqueOutputState = predictedState,
            symbolicActions = symbolicActions,
            ops = ops,
            layoutId = profile.key,
            costMetricId = "op_count:1",
            policyId = policy.name,
            outcome = outcome
        )
        currentState = resultingState

        EditorTrace.sink.record(
            VerificationEntry(
                abiVersion = TargetPackage.HOST_ABI_VERSION,
                targetId = target.id,
                targetVersion = target.version,
                targetSourceHash = target.sourceHash,
                currentText = assumedDocument,
                desiredText = text,
                opaqueInputState = opaqueState,
                opaqueOutputState = predictedState,
                symbolicActions = symbolicActions,
                ops = ops,
                layoutId = profile.key,
                costMetricId = "op_count:1",
                policyId = policy.name,
                outcome = outcome,
                classification = classification
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
     */
    fun state(): EditorState = commandLane.withLock { currentState }

    /**
     * Explicit reset to empty known state.
     */
    fun reset() = commandLane.withLock {
        currentState = EditorState.Uninitialized
        assumedDocument = ""
        lastRetainedPlan = null
        try {
            opaqueState = luaHost.runInitializer(target, "")
            initializationFailure = null
        } catch (failure: LuaLoadException) {
            initializationFailure = FailureClassification.PlanningError(
                failure.message ?: "Target package initialization failed on reset",
            )
            opaqueState = AbiValue.Null
        }
    }

    /**
     * Restore current assumed document and opaque state for replay.
     */
    fun restore(document: String, state: AbiValue) = commandLane.withLock {
        assumedDocument = document
        opaqueState = state
        currentState = EditorState.Synced
        lastRetainedPlan = null
    }

    // ── Internal / verification access ──

    internal val verificationState: VerificationState
        get() = VerificationState(
            state = currentState,
            assumedDocument = assumedDocument,
            opaqueState = opaqueState,
            lastPlan = lastRetainedPlan,
            luaPlanCount = lastRetainedPlan?.ops?.size ?: 0,
        )

    data class VerificationState(
        val state: EditorState,
        val assumedDocument: String,
        val opaqueState: AbiValue,
        val lastPlan: RetainedPlan?,
        val luaPlanCount: Int,
    )

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

    private fun compileActions(
        actions: List<SymbolicAction>,
        hid: LowLevelHid,
        profile: HostProfile,
        policy: ExecutionPolicy,
        compileCache: Map<String, List<LowLevelOp>>
    ): List<LowLevelOp> {
        val dummyOps = mutableListOf<LowLevelOp>()
        val dummyTextPlanner = TextPlanner(hid = NonAllocatingHid)

        for (action in actions) {
            when (action) {
                is SymbolicAction.Tap -> {
                    rejectF24IfReserved(action.usage, policy)
                    val usage = try {
                        Usages.byName(action.usage)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Unknown HID usage: ${action.usage}")
                    }
                    dummyOps.add(NonAllocatingHid.keyTap(usage))
                }
                is SymbolicAction.Down -> {
                    rejectF24IfReserved(action.usage, policy)
                    val usage = try {
                        Usages.byName(action.usage)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Unknown HID usage: ${action.usage}")
                    }
                    dummyOps.add(NonAllocatingHid.keyDown(usage))
                }
                is SymbolicAction.Up -> {
                    rejectF24IfReserved(action.usage, policy)
                    val usage = try {
                        Usages.byName(action.usage)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Unknown HID usage: ${action.usage}")
                    }
                    dummyOps.add(NonAllocatingHid.keyUp(usage))
                }
                is SymbolicAction.Text -> {
                    val ops = compileCache[action.text] ?: run {
                        val plan = dummyTextPlanner.plan(action.text, profile)
                        if (!plan.ok) {
                            val failure = plan.failure!!
                            val msg = when (failure) {
                                is TextRenderingFailure.MissingLayout -> "missing layout: ${failure.profile}"
                                is TextRenderingFailure.UnrepresentableGlyph -> "unrepresentable glyph: '${failure.ch}'"
                            }
                            throw IllegalArgumentException("Text rendering failed: $msg")
                        }
                        plan.plan!!
                    }
                    rejectF24InOpsIfReserved(ops, policy)
                    dummyOps.addAll(ops)
                }
            }
        }

        if (dummyOps.size > 1000) {
            throw IllegalArgumentException("Plan size limit exceeded (${dummyOps.size} > 1000)")
        }

        return dummyOps.map { it.copy(seqId = hid.nextSeqId()) }
    }

    private fun rejectF24IfReserved(usage: String, policy: ExecutionPolicy) {
        if (policy == ExecutionPolicy.CONFORMANCE) {
            if (usage == "USB_KEY_F24" || usage == "F24") {
                throw IllegalArgumentException("F24 is reserved for synchronization and unavailable under the active policy")
            }
        }
    }

    private fun rejectF24InOpsIfReserved(ops: List<LowLevelOp>, policy: ExecutionPolicy) {
        if (policy == ExecutionPolicy.CONFORMANCE) {
            val f24Usage = Usages.USB_KEY_F24.usbUsage.toByte()
            for (op in ops) {
                if ((op.opcode == Opcodes.KEY_TAP || op.opcode == Opcodes.KEY_DOWN || op.opcode == Opcodes.KEY_UP) &&
                    op.payload.isNotEmpty() && op.payload[0] == f24Usage
                ) {
                    throw IllegalArgumentException("F24 is reserved for synchronization and unavailable under the active policy")
                }
            }
        }
    }

    private object NonAllocatingHid : LowLevelHid {
        override fun nextSeqId(): Int = 0
        override fun arm(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.ARM, byteArrayOf(), seqId)
        override fun disarm(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.DISARM, byteArrayOf(), seqId)
        override fun kill(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KILL, byteArrayOf(), seqId)
        override fun releaseAll(seqId: Int): LowLevelOp = LowLevelOp(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)
        override fun keyTap(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyDown(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyUp(usage: HidUsage, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)
        override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.KEYBOARD_TAP_SCRIPT, byteArrayOf(), seqId)
        override fun mouseRelReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int, seqId: Int): LowLevelOp = LowLevelOp(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)
    }
}
