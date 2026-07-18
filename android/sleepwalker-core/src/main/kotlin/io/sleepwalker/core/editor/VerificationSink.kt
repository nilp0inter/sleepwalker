package io.sleepwalker.core.editor

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

