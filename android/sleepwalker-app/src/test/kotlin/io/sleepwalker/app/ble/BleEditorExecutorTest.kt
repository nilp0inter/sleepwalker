package io.sleepwalker.app.ble

import io.sleepwalker.core.editor.ExecutionOutcome
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.protocol.Opcodes
import io.sleepwalker.core.protocol.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BleEditorExecutorTest {

    @Test
    fun delivered_plan_sends_every_operation_through_shared_path_in_order_and_correlates_sent_acknowledgements() {
        val plan = listOf(operation(41), operation(42), operation(43))
        val sent = mutableListOf<LowLevelOp>()
        lateinit var executor: BleEditorExecutor
        executor = BleEditorExecutor(
            sendBlock = { op ->
                sent += op
                executor.injectStatus(op.seqId, Status.SENT_TO_USB, "sent_to_usb")
            },
            isConnected = { true },
            isSafe = { true },
            timeoutMs = 0,
        )

        val outcome = executor.execute(plan)

        assertEquals(plan, sent)
        assertTrue(outcome is ExecutionOutcome.Delivered)
        outcome as ExecutionOutcome.Delivered
        assertEquals(plan.map { it.seqId }, outcome.statuses.map { it.seqId })
        assertTrue(outcome.statuses.all { it.status == Status.SENT_TO_USB })
    }

    @Test
    fun unsafe_or_disconnected_preflight_returns_partial_without_emitting_hid() {
        val plan = listOf(operation(51))
        val sent = mutableListOf<LowLevelOp>()
        val unsafe = BleEditorExecutor(
            sendBlock = sent::add,
            isConnected = { true },
            isSafe = { false },
            timeoutMs = 0,
        )
        val disconnected = BleEditorExecutor(
            sendBlock = sent::add,
            isConnected = { false },
            isSafe = { true },
            timeoutMs = 0,
        )

        val unsafeOutcome = unsafe.execute(plan)
        val disconnectedOutcome = disconnected.execute(plan)

        assertPartial(unsafeOutcome, emptyList(), "Safety state not ARMED")
        assertPartial(disconnectedOutcome, emptyList(), "Not connected")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun terminal_safety_acknowledgement_returns_partial_and_prevents_following_operation() {
        for ((status, name) in listOf(Status.DISARMED to "disarmed", Status.KILLED to "killed")) {
            val sent = mutableListOf<LowLevelOp>()
            lateinit var executor: BleEditorExecutor
            executor = BleEditorExecutor(
                sendBlock = { op ->
                    sent += op
                    executor.injectStatus(op.seqId, status, name)
                },
                isConnected = { true },
                isSafe = { true },
                timeoutMs = 0,
            )

            val outcome = executor.execute(listOf(operation(61), operation(62)))

            assertPartial(outcome, emptyList(), "Terminal fault: $name (seq 61)")
            assertEquals(listOf(operation(61)), sent)
            assertEquals(listOf(61), executor.lastStatuses.map { it.seqId })
            assertEquals(listOf(status), executor.lastStatuses.map { it.status })
            assertEquals(listOf(name), executor.lastStatuses.map { it.statusName })
        }
    }

    @Test
    fun timeout_or_mismatched_acknowledgement_returns_partial_instead_of_delivered() {
        val cases = listOf(
            "missing acknowledgement" to { _: BleEditorExecutor, _: LowLevelOp -> Unit },
            "different sequence acknowledgement" to { executor: BleEditorExecutor, op: LowLevelOp ->
                executor.injectStatus(op.seqId + 1, Status.SENT_TO_USB, "sent_to_usb")
            },
            "intermediate acknowledgement only" to { executor: BleEditorExecutor, op: LowLevelOp ->
                executor.injectStatus(op.seqId, Status.QUEUED, "queued")
            },
        )

        for ((_, acknowledge) in cases) {
            val sent = mutableListOf<LowLevelOp>()
            lateinit var executor: BleEditorExecutor
            executor = BleEditorExecutor(
                sendBlock = { op ->
                    sent += op
                    acknowledge(executor, op)
                },
                isConnected = { true },
                isSafe = { true },
                timeoutMs = 0,
            )

            val outcome = executor.execute(listOf(operation(71)))

            assertPartial(outcome, emptyList(), "Timeout waiting for ack (seq 71)")
            assertEquals(listOf(operation(71)), sent)
        }
    }

    @Test
    fun partial_acknowledgement_retains_only_confirmed_operations() {
        val sent = mutableListOf<LowLevelOp>()
        lateinit var executor: BleEditorExecutor
        executor = BleEditorExecutor(
            sendBlock = { op ->
                sent += op
                if (op.seqId == 81) {
                    executor.injectStatus(op.seqId, Status.SENT_TO_USB, "sent_to_usb")
                }
            },
            isConnected = { true },
            isSafe = { true },
            timeoutMs = 0,
        )

        val outcome = executor.execute(listOf(operation(81), operation(82)))

        assertPartial(outcome, listOf(81), "Timeout waiting for ack (seq 82)")
        assertEquals(listOf(operation(81), operation(82)), sent)
    }

    private fun operation(seqId: Int) = LowLevelOp(
        opcode = Opcodes.KEY_TAP,
        payload = byteArrayOf(seqId.toByte()),
        seqId = seqId,
    )

    private fun assertPartial(outcome: ExecutionOutcome, deliveredSeqIds: List<Int>, reason: String) {
        assertTrue(outcome is ExecutionOutcome.Partial)
        outcome as ExecutionOutcome.Partial
        assertEquals(deliveredSeqIds, outcome.delivered.map { it.seqId })
        assertTrue(outcome.reason is io.sleepwalker.core.editor.FailureClassification.TransportFailure)
        assertEquals(
            reason,
            (outcome.reason as io.sleepwalker.core.editor.FailureClassification.TransportFailure).reason,
        )
    }
}
