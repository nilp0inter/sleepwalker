package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Opcodes

internal object EditorTestFixtures {
    fun target(
        mainLua: String = passThroughProgram(),
        hostAbi: Int = TargetPackage.HOST_ABI_VERSION,
        modules: Map<String, String> = emptyMap(),
    ): TargetPackage = TargetPackage(
        id = "test-readline",
        version = "test",
        hostAbi = hostAbi,
        target = "gnu-readline",
        targetPin = "test",
        mode = "emacs",
        charset = "ascii-printable",
        lineModel = "single-line",
        description = mapOf(
            "name" to "Test Readline",
            "charset" to "ascii-printable",
            "line_model" to "single-line",
            "mode" to "emacs",
            "target" to "gnu-readline",
            "target_pin" to "test",
        ),
        mainLua = mainLua,
        modules = modules,
    )

    fun passThroughProgram(): String = """
        function plan(current, desired, lcp, oldMid, newMid, state)
            if #newMid > 0 then
                host.text_plan(newMid)
            end
            return {
                buffer = desired,
                point = lcp + #newMid,
                revision = state.revision + 1,
            }
        end
    """.trimIndent()
}

internal class RecordingHid : LowLevelHid {
    private var nextSequenceId = 1
    val calls = mutableListOf<LowLevelOp>()
    var onOperation: ((LowLevelOp) -> Unit)? = null

    override fun nextSeqId(): Int = nextSequenceId++

    override fun arm(seqId: Int): LowLevelOp = operation(Opcodes.ARM, byteArrayOf(), seqId)

    override fun disarm(seqId: Int): LowLevelOp = operation(Opcodes.DISARM, byteArrayOf(), seqId)

    override fun kill(seqId: Int): LowLevelOp = operation(Opcodes.KILL, byteArrayOf(), seqId)

    override fun releaseAll(seqId: Int): LowLevelOp = operation(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)

    override fun keyTap(usage: HidUsage, seqId: Int): LowLevelOp =
        operation(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)

    override fun keyDown(usage: HidUsage, seqId: Int): LowLevelOp =
        operation(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)

    override fun keyUp(usage: HidUsage, seqId: Int): LowLevelOp =
        operation(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)

    override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp =
        operation(Opcodes.KEYBOARD_TAP_SCRIPT, byteArrayOf(), seqId)

    override fun mouseRelReport(
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int,
        pan: Int,
        seqId: Int,
    ): LowLevelOp = operation(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)

    private fun operation(opcode: Int, payload: ByteArray, seqId: Int): LowLevelOp =
        LowLevelOp(opcode, payload, seqId).also { op ->
            calls += op
            onOperation?.invoke(op)
        }
}

internal class RecordingEditorExecutor(
    private val outcomes: ArrayDeque<ExecutionOutcome>,
) : EditorExecutor {
    val submittedPlans = mutableListOf<List<LowLevelOp>>()
    var onExecute: ((List<LowLevelOp>) -> Unit)? = null

    override fun execute(plan: List<LowLevelOp>): ExecutionOutcome {
        submittedPlans += plan
        onExecute?.invoke(plan)
        return outcomes.removeFirst()
    }
}
