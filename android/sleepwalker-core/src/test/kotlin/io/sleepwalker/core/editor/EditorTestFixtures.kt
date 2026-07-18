package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Opcodes

internal object EditorTestFixtures {
    fun target(
        mainLua: String = packageProgram(
            initializer = "{ revision = 0 }",
            planner = "return { actions = {}, next_state = state }",
        ),
        hostAbi: Int = TargetPackage.HOST_ABI_VERSION,
        modules: Map<String, String> = emptyMap(),
    ): TargetPackage = TargetPackage(
        id = "test-target",
        version = "test",
        hostAbi = hostAbi,
        target = "test-target",
        targetPin = "test",
        mode = "test",
        charset = "test",
        lineModel = "test",
        mainLua = mainLua,
        modules = modules,
        sourceHash = "test-source",
    )

    fun packageProgram(initializer: String, planner: String): String = """
        return {
            abi_version = 1,
            initialize = function(current)
                return $initializer
            end,
            plan = function(current, desired, state)
                $planner
            end,
        }
    """.trimIndent()
}

internal class RecordingHid : LowLevelHid {
    private var nextSequenceId = 1
    val calls = mutableListOf<LowLevelOp>()
    val issuedSequenceIds = mutableListOf<Int>()

    override fun nextSeqId(): Int = nextSequenceId.also { id ->
        issuedSequenceIds += id
        nextSequenceId += 1
    }

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
        LowLevelOp(opcode, payload, seqId).also(calls::add)
}

internal class RecordingEditorExecutor(
    private val outcomes: ArrayDeque<ExecutionOutcome>,
) : EditorExecutor {
    val submittedPlans = mutableListOf<List<LowLevelOp>>()

    override fun execute(plan: List<LowLevelOp>): ExecutionOutcome {
        submittedPlans += plan
        return outcomes.removeFirst()
    }
}
