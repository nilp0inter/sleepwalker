package io.sleepwalker.app.ble

import io.sleepwalker.core.editor.EditorExecutor
import io.sleepwalker.core.editor.ExecutionOutcome
import io.sleepwalker.core.editor.FailureClassification
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.SessionStatus
import io.sleepwalker.core.protocol.Status
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.app.diagnostics.SwLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android BLE-backed [EditorExecutor] that sends operations through the
 * shared [SleepwalkerBleService] session and correlates per-op status
 * notifications.
 *
 * **Production use:** default constructor wiring to
 * [SleepwalkerBleService.sendOp] and [SleepwalkerBleService.executorStatusListener].
 *
 * **Test use:** secondary internal constructor accepting injection of
 * [sendBlock], [isConnected], [timeoutMs]; call [injectStatus] and
 * [injectDisconnect] to drive the pending-ack machinery deterministically.
 *
 * Safety preflight: [execute] checks [isConnected] before the first op
 * and returns [ExecutionOutcome.Partial] immediately if not connected.
 * Every fault (timeout, disconnected, DISARMED, KILLED, USB_NOT_MOUNTED,
 * QUEUE_FULL, or unexpected status) yields [Partial] with a structured
 * [FailureClassification.TransportFailure].
 */
class BleEditorExecutor internal constructor(
    private val sendBlock: (LowLevelOp) -> Unit,
    private val isConnected: () -> Boolean,
    /** Pre-emission safety check — return false if safety is DISARMED/KILLED. */
    private val isSafe: () -> Boolean,
    private val timeoutMs: Long,
    private val compileBlock: (List<LowLevelOp>) -> List<LowLevelOp> = { it },
    private val drainBlock: () -> Unit = {},
) : EditorExecutor {

    // ── Pending-ack machinery ──

    private val pending = ConcurrentHashMap<Int, CompletableFuture<SessionStatus>>()
    @Volatile
    internal var lastStatuses: List<SessionStatus> = emptyList()
        private set


    /**
     * Inject a simulated status notification (test seam).
     * Matches [seqId] against a pending future and completes or fails it.
     */
    internal fun injectStatus(seqId: Int, status: Int, statusName: String) {
        val future = pending[seqId] ?: return
        val sessionStatus = SessionStatus(seqId, status, statusName, byteArrayOf())
        when (status) {
            Status.SENT_TO_USB -> {
                SwLog.frame("executor_ack", seqId, mapOf("status" to status, "name" to statusName))
                future.complete(sessionStatus)
            }
            Status.DISARMED, Status.KILLED, Status.QUEUE_FULL, Status.USB_NOT_MOUNTED,
            Status.MALFORMED, Status.BAD_CRC, Status.UNSUPPORTED_OPCODE -> {
                SwLog.failure("executor_fault", seqId, mapOf("status" to status, "name" to statusName))
                future.complete(sessionStatus)
            }
            // Non-terminal statuses (RECEIVED, QUEUED, or unknown) are
            // intermediate — the future stays pending for the terminal one.
            else -> {
                SwLog.frame("executor_intermediate", seqId, mapOf("status" to status, "name" to statusName))
            }
        }
    }

    /**
     * Inject a simulated disconnection (test seam).
     * Fails all pending futures immediately.
     */
    internal fun injectDisconnect() {
        SwLog.frame("executor_disconnected")
        val ex = IllegalStateException("Disconnected while waiting for ack")
        val iter = pending.values.iterator()
        while (iter.hasNext()) {
            iter.next().completeExceptionally(ex)
        }
        pending.clear()
    }

    // ── EditorExecutor implementation ──

    override fun execute(plan: List<LowLevelOp>): ExecutionOutcome {
        lastStatuses = emptyList()
        // Pre-emission safety check.
        if (!isSafe()) {
            SwLog.failure("executor_unsafe")
            return ExecutionOutcome.Partial(
                delivered = emptyList(),
                reason = FailureClassification.TransportFailure("Safety state not ARMED"),
            )
        }

        // Connectivity check before attempting send.
        if (!isConnected()) {
            SwLog.failure("executor_not_connected")
            return ExecutionOutcome.Partial(
                delivered = emptyList(),
                reason = FailureClassification.TransportFailure("Not connected"),
            )
        }

        val batches = compileBlock(plan)
        if (plan.isNotEmpty() && batches.isEmpty()) {
            return ExecutionOutcome.Partial(
                delivered = emptyList(),
                reason = FailureClassification.TransportFailure(
                    "Plan compilation produced no transport batches",
                ),
            )
        }

        val delivered = mutableListOf<SessionStatus>()
        val observed = mutableListOf<SessionStatus>()

        for ((index, op) in batches.withIndex()) {
            val future = CompletableFuture<SessionStatus>()
            pending[op.seqId] = future

            try {
                sendBlock(op)
                val status = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                observed.add(status)
                lastStatuses = observed.toList()
                if (status.status != Status.SENT_TO_USB) {
                    pending.remove(op.seqId)
                    return ExecutionOutcome.Partial(
                        delivered = delivered.toList(),
                        reason = FailureClassification.TransportFailure(
                            "Terminal fault: ${status.statusName} (seq ${status.seqId})",
                        ),
                    )
                }
                delivered.add(status)
                if (index < batches.lastIndex) drainBlock()
                pending.remove(op.seqId)
            } catch (e: Exception) {
                pending.remove(op.seqId)
                val reason = when (e) {
                    is java.util.concurrent.TimeoutException ->
                        "Timeout waiting for ack (seq ${op.seqId})"
                    is java.util.concurrent.CancellationException ->
                        "Cancelled (seq ${op.seqId})"
                    is java.util.concurrent.ExecutionException ->
                        e.cause?.message ?: "Execution failed (seq ${op.seqId})"
                    else -> e.message ?: "Unknown error (seq ${op.seqId})"
                }
                lastStatuses = observed.toList()
                return ExecutionOutcome.Partial(
                    delivered = delivered.toList(),
                    reason = FailureClassification.TransportFailure(reason),
                )
            }
        }

        lastStatuses = observed.toList()
        return ExecutionOutcome.Delivered(delivered.toList())
    }

    companion object {
        /** Production per-op ack timeout. */
        private const val OP_TIMEOUT_MS = 10_000L

        /**
         * Production factory — wires to [SleepwalkerBleService] statics.
         */
        fun create(): BleEditorExecutor {
            val exec = BleEditorExecutor(
                sendBlock = { op -> SleepwalkerBleService.sendOp(op, op.seqId) },
                isConnected = { SleepwalkerBleService.gatt != null && SleepwalkerBleService.rxChar != null },
                isSafe = {
                    SleepwalkerBleService.safetyState ==
                        SleepwalkerBleService.Companion.SafetyState.ARMED
                },
                timeoutMs = OP_TIMEOUT_MS,
                compileBlock = { plan ->
                    TapScriptCompiler.compile(plan, SleepwalkerBleService.hid)
                },
                drainBlock = { Thread.sleep(390) },
            )

            // Register as the service's dedicated executor listener so
            // status notifications are routed through injectStatus.
            SleepwalkerBleService.executorStatusListener =
                object : SleepwalkerBleService.Companion.StatusListener {
                    override fun onStatusReceived(seqId: Int, status: Int, statusName: String) {
                        exec.injectStatus(seqId, status, statusName)
                    }

                    override fun onConnectionChanged(connected: Boolean) {
                        if (!connected) exec.injectDisconnect()
                    }
                }

            return exec
        }
    }
}
