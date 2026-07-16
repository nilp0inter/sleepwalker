package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelOp

/**
 * Structured Editor result for [Editor.setText].
 *
 * Carries the requested complete snapshot and, when computed, the
 * inspectable plan. Never exposes caret position, selection anchor, or
 * target-program internal state.
 */
sealed class EditorResult {
    /** The complete desired document snapshot the caller requested. */
    abstract val requestedDocument: String

    /**
     * Successful reconciliation.
     *
     * @property plan the inspectable ordered plan of low-level keyboard
     *   operations that was executed (empty for a no-op transition).
     */
    data class Synced(
        override val requestedDocument: String,
        val plan: List<LowLevelOp>,
    ) : EditorResult()

    /**
     * Failed reconciliation.
     *
     * @property classification structured failure class distinguishing
     *   semantic failures from infrastructure failures.
     * @property plan null when planning never produced a plan (pre-
     *   execution), non-null when execution began but did not complete
     *   (partial execution → Unknown state).
     */
    data class EditorFailure(
        override val requestedDocument: String,
        val classification: FailureClassification,
        val plan: List<LowLevelOp>?,
    ) : EditorResult()
}

/**
 * Structured failure classification.
 *
 * Mutually exclusive classes separating semantic reconciliation
 * failures from infrastructure failures, matching the HIL failure
 * taxonomy (Decision 14).
 */
sealed class FailureClassification {
    // ── Pre-execution semantic failures ──

    /** Target package ABI version does not match the host ABI. */
    data class AbiMismatch(val expected: Int, val actual: Int) : FailureClassification()

    /** A character in the requested text cannot be represented. */
    data class UnrepresentableContent(val glyph: Char) : FailureClassification()

    /** The target package prediction does not match the desired text. */
    data class InconsistentPrediction(
        val expected: String,
        val predicted: String,
    ) : FailureClassification()

    /** The requested behavior is out of scope for the target package. */
    data class UnsupportedBehavior(val reason: String) : FailureClassification()

    /** Generic planning failure (Lua error, invalid plan, etc.). */
    data class PlanningError(val reason: String) : FailureClassification()

    // ── Infrastructure / post-execution failures ──

    /** BLE/firmware transport fault (DISARMED, QUEUE_FULL, timeout, etc.). */
    data class TransportFailure(val reason: String) : FailureClassification()

    /** Fixture misbehaviour (health fail, snapshot fail, identity mismatch). */
    data class FixtureFailure(val reason: String) : FailureClassification()

    /** Synchronization barrier not consumed within the bounded window. */
    data class SyncFailure(val reason: String) : FailureClassification()

    /** Environment issue (console keymap, device enumeration, SSH). */
    data class EnvironmentFailure(val reason: String) : FailureClassification()

    /** Step failed once but same-input replay succeeded (flaky hardware). */
    data class NonReproducible(val reason: String) : FailureClassification()
}
