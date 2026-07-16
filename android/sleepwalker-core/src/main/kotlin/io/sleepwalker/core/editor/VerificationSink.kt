package io.sleepwalker.core.editor

import io.sleepwalker.core.hid.LowLevelOp

/**
 * Package-level verification trace sink.
 *
 * The HIL and app integration set [sink] before creating an Editor to
 * receive machine-readable verification records. Each [Editor.setText]
 * call emits one [VerificationEntry] through the active sink.
 *
 * Defaults to [NOOP]; no allocation or I/O overhead when verification
 * logging is not required.
 */
object EditorTrace {
    @Volatile
    var sink: VerificationSink = VerificationSink.NOOP
}

/**
 * Sink interface for verification-only trace records.
 *
 * Implementations emit machine-readable logs (JSONL, diagnostics,
 * in-memory buffers) without modifying Editor public API behaviour.
 *
 * @see VerificationEntry for the record schema.
 */
interface VerificationSink {
    fun record(entry: VerificationEntry)

    companion object {
        /** No-op sink — discards all records. */
        val NOOP: VerificationSink = object : VerificationSink {
            override fun record(entry: VerificationEntry) {}
        }
    }
}

/**
 * A single verification record emitted after an Editor [Editor.setText]
 * call completes (success or failure).
 *
 * Contains the full internal prediction state — assumed program state,
 * LCP/LCS metrics, predicted next state, and planned ops — which is
 * deliberately excluded from the public [EditorResult] type but
 * required by the HIL for snapshot-sequence comparison and failure
 * classification.
 *
 * @property abiVersion    host ABI version used to generate the plan.
 * @property targetId      target package identity.
 * @property targetVersion target package semantic version.
 * @property assumedState  the Editor's committed program state before
 *                         this transition.
 * @property desiredText   the complete desired snapshot requested.
 * @property lcp           longest common prefix length.
 * @property oldMid        characters removed (may be empty).
 * @property newMid        characters inserted (may be empty).
 * @property predictedState the target package's predicted next program
 *                          state (only valid when [classification] is null).
 * @property ops           the ordered low-level keyboard operations in
 *                         the reconciliation plan.
 * @property classification null when the transition succeeded;
 *                          non-null when it failed, with the structured cause.
 */
data class VerificationEntry(
    val abiVersion: Int,
    val targetId: String,
    val targetVersion: String,
    val assumedState: ReadlineProgramState,
    val desiredText: String,
    val lcp: Int,
    val oldMid: String,
    val newMid: String,
    val predictedState: ReadlineProgramState,
    val ops: List<LowLevelOp>,
    val classification: FailureClassification?,
)
