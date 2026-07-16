package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.SessionStatus

/**
 * Serialized executor boundary for Editor reconciliation plans.
 *
 * Owned by the app integration layer; `sleepwalker-core` never owns
 * BLE transport. The executor processes one plan end-to-end, returning
 * per-operation status correlation.
 *
 * @see Editor for serialization enforcement (single ordered command lane).
 */
interface EditorExecutor {
    /**
     * Execute a complete reconciliation plan.
     *
     * @param plan the ordered list of low-level keyboard operations to
     *   send over the transport.
     * @return [ExecutionOutcome.Delivered] when every batch receives a
     *   terminal `SENT_TO_USB` status, or [ExecutionOutcome.Partial]
     *   when any batch fails (DISARMED, QUEUE_FULL, USB_NOT_MOUNTED,
     *   KILLED, or transport timeout/missing status).
     */
    fun execute(plan: List<LowLevelOp>): ExecutionOutcome
}

/**
 * Outcome of an [EditorExecutor.execute] call.
 */
sealed class ExecutionOutcome {
    /**
     * Every batch was delivered with a terminal positive status.
     *
     * @property statuses per-batch terminal status notifications in
     *   execution order.
     */
    data class Delivered(val statuses: List<SessionStatus>) : ExecutionOutcome()

    /**
     * Execution did not complete.
     *
     * @property delivered statuses for batches that were acknowledged
     *   before the fault.
     * @property reason the infrastructure failure that aborted execution.
     */
    data class Partial(
        val delivered: List<SessionStatus>,
        val reason: FailureClassification,
    ) : ExecutionOutcome()
}
