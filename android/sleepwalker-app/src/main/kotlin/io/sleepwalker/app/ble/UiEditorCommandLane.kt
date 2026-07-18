package io.sleepwalker.app.ble

import android.os.Handler
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.app.adb.AdbCommandReceiver
import io.sleepwalker.core.editor.Editor
import io.sleepwalker.core.editor.EditorResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-thread FIFO lane for UI Editor commands.
 *
 * Owns the monotonic change-id generator, the single-thread executor, and the
 * [UiEditorListener] delivery. Each admitted request is processed in FIFO order
 * on a dedicated background thread; results are delivered on the main thread
 * via [mainHandler].
 *
 * Test seams: [snapshotState] and [changeId] allow unit tests to observe internal
 * state without Android/BLE hardware. The [executor] parameter is injectable so
 * tests can control FIFO ordering (e.g. block the first request to force a queue).
 *
 * Production wiring happens in [SleepwalkerBleService] which provides:
 * - a single-thread executor with a named thread
 * - the main-thread [Handler]
 * - the [editorLock] for synchronized Editor access
 * - the [editorProvider] lambda (lazy Editor singleton)
 * - the [snapshotProvider] lambda (caller-owned lastVerification state)
 * - the [onReset] lambda for service-side reset side effects
 *
 * @param executor       single-thread executor that processes requests serially.
 * @param mainHandler    handler bound to the main looper for listener delivery.
 * @param editorLock     lock that serializes [Editor] mutations.
 * @param editorProvider returns the shared [Editor] instance.
 * @param snapshotProvider returns the current [EditorSnapshot] (may be null).
 * @param onReset        invoked inside the synchronized block after [Editor.reset];
 *                       the service uses it to clear [SleepwalkerBleService.lastVerification]
 *                       and emit the "reset_acknowledged_empty" diagnostic.
 */
class UiEditorCommandLane(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SleepwalkerUiEditorFIFO")
    },
    private val mainHandler: Handler,
    private val editorLock: Any,
    private val editorProvider: () -> Editor,
    private val snapshotProvider: () -> EditorSnapshot?,
    private val onReset: () -> Unit = {},
) {
    private val changeIdGenerator = AtomicLong(0)

    /** Listener notified on the main thread after each request completes. */
    @Volatile
    var listener: UiEditorListener? = null

    /** Allocate the next monotonic change identifier. */
    fun nextUiChangeId(): Long = changeIdGenerator.incrementAndGet()

    /**
     * Enqueue a [UiEditorRequest] for serial processing.
     *
     * 1. Executes the operation on the FIFO thread (holding [editorLock] for
     *    [Editor] mutations).
     * 2. Emits the structured completion event ([SwLog]).
     * 3. Delivers the result on the main thread via [listener].
     */
    fun submit(request: UiEditorRequest) {
        executor.submit { processRequest(request) }
    }

    // ── Test seams ──

    /** Returns the current [EditorSnapshot] (test seam). */
    fun snapshotState(): EditorSnapshot? = snapshotProvider()

    /** Returns the current change-id generator value (test seam). */
    fun changeId(): Long = changeIdGenerator.get()

    // ── Internal ──

    private fun processRequest(request: UiEditorRequest) {
        try {
            when (request) {
                is UiEditorRequest.Snapshot -> processSnapshot(request)
                is UiEditorRequest.Reset -> processReset(request)
            }
        } catch (e: Exception) {
            SwLog.failure("ui_editor_fifo_error", fields = mapOf("error" to (e.message ?: "unknown")))
        }
    }

    private fun processSnapshot(request: UiEditorRequest.Snapshot) {
        val editor = editorProvider()
        val result: EditorResult
        val snapshot: EditorSnapshot?
        synchronized(editorLock) {
            result = editor.setText(request.text)
            snapshot = snapshotProvider()
        }
        // Completion diagnostic emitted only after Editor.setText returns
        // (contract: never log completion before Editor/set/reset returns).
        SwLog.event("editor", "ui_change_result", fields = mapOf(
            "change_id" to request.id,
            "generation" to request.generation,
            "desired_text" to request.text,
            "editor_state" to snapshot?.let { s ->
                mapOf(
                    "state" to s.state.name,
                    "target_id" to s.targetId,
                    "target_version" to s.targetVersion,
                    "target_source_hash" to s.targetSourceHash,
                    "host_abi" to s.hostAbi,
                    "current_text" to s.currentText,
                    "desired_text" to s.desiredText,
                    "opaque_input_state" to s.opaqueInputState?.toString(),
                    "opaque_output_state" to s.opaqueOutputState?.toString(),
                    "symbolic_actions" to s.symbolicActions?.toString(),
                    "compiled_operations" to s.ops?.map { AdbCommandReceiver.formatLowLevelOp(it) },
                    "layout_id" to s.layoutId,
                    "cost_metric_id" to s.costMetricId,
                    "policy_id" to s.policyId,
                    "transaction_outcome" to s.outcome,
                    "classification" to s.lastClassification,
                )
            },
            "classification" to when (result) {
                is EditorResult.Synced -> null
                is EditorResult.EditorFailure -> result.classification.javaClass.simpleName
                else -> result.javaClass.simpleName
            },
        ))
        mainHandler.post {
            listener?.onUiEditorResult(request, UiEditorResult.Snapshot(
                id = request.id,
                generation = request.generation,
                text = request.text,
                result = result,
                snapshot = snapshot,
            ))
        }
    }

    private fun processReset(request: UiEditorRequest.Reset) {
        val editor = editorProvider()
        synchronized(editorLock) {
            editor.reset()
            onReset()
        }
        // Completion diagnostic emitted only after Editor.reset returns
        // (contract: never log completion before Editor/set/reset returns).
        SwLog.event("editor", "ui_reset_result", fields = mapOf(
            "change_id" to request.id,
            "generation" to request.generation,
        ))
        mainHandler.post {
            listener?.onUiEditorResult(request, UiEditorResult.Reset(
                id = request.id,
                generation = request.generation,
            ))
        }
    }
}
