package io.sleepwalker.app

import android.content.Intent
import android.os.Looper
import io.mockk.mockk
import io.sleepwalker.app.ble.EditorSnapshot
import io.sleepwalker.app.ble.SleepwalkerBleService
import io.sleepwalker.app.ble.UiEditorRequest
import io.sleepwalker.app.ble.UiEditorResult
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.EditorState
import io.sleepwalker.core.editor.FailureClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityEditorTest {

    private lateinit var activity: MainActivity
    private val capturedRequests = mutableListOf<UiEditorRequest>()
    private var nextId = 0L

    @Before
    fun setUp() {
        capturedRequests.clear()
        nextId = 0L
        SleepwalkerBleService.gatt = null
        SleepwalkerBleService.rxChar = null
        setSafetyState(SleepwalkerBleService.Companion.SafetyState.UNKNOWN)
    }

    // ── Helpers ──

    private fun setSafetyState(state: SleepwalkerBleService.Companion.SafetyState) {
        var field: java.lang.reflect.Field? = null
        try {
            field = SleepwalkerBleService.Companion::class.java.getDeclaredField("safetyState")
        } catch (_: NoSuchFieldException) {
            try {
                field = SleepwalkerBleService::class.java.getDeclaredField("safetyState")
            } catch (_: NoSuchFieldException) {
                for (f in SleepwalkerBleService::class.java.declaredFields) {
                    if (f.name.contains("safetyState")) { field = f; break }
                }
                if (field == null) {
                    for (f in SleepwalkerBleService.Companion::class.java.declaredFields) {
                        if (f.name.contains("safetyState")) { field = f; break }
                    }
                }
            }
        }
        requireNotNull(field) { "Could not find safetyState field for reflection" }
        field.isAccessible = true
        val target = if ((field.modifiers and java.lang.reflect.Modifier.STATIC) != 0) null else SleepwalkerBleService.Companion
        field.set(target, state)
    }

    private fun enableReadlineInput() {
        SleepwalkerBleService.gatt = mockk(relaxed = true)
        SleepwalkerBleService.rxChar = mockk(relaxed = true)
        setSafetyState(SleepwalkerBleService.Companion.SafetyState.ARMED)
    }

    private fun installCaptures() {
        activity.submitRequestFn = { capturedRequests.add(it) }
        activity.nextChangeIdFn = { ++nextId }
    }

    private fun drainMain() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    private fun createActivity(intentExtras: Intent.() -> Unit = {}): MainActivity {
        val intent = Intent().apply(intentExtras)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        activity = controller.create().start().resume().get()
        drainMain()
        return activity
    }

    private fun switchToReadlineMode() {
        activity.modeRadioGroup.check(activity.readlineRadio.id)
        drainMain()
    }

    // ── Mode routing ──


    @Test
    fun `readline extra intent selects readline editor mode`() {
        createActivity { putExtra(MainActivity.EXTRA_READLINE, true) }
        assertEquals(DemoTextMode.READLINE_EDITOR, activity.currentMode)
        assertTrue(activity.readlineRadio.isChecked)
    }

    @Test
    fun `readline mode via extra mode string`() {
        createActivity { putExtra(MainActivity.EXTRA_MODE, "READLINE_EDITOR") }
        assertEquals(DemoTextMode.READLINE_EDITOR, activity.currentMode)
    }

    @Test
    fun `readline extra via string true`() {
        createActivity { putExtra(MainActivity.EXTRA_READLINE, "true") }
        assertEquals(DemoTextMode.READLINE_EDITOR, activity.currentMode)
    }

    @Test
    fun `switching radio to readline updates mode`() {
        createActivity()
        switchToReadlineMode()
        assertEquals(DemoTextMode.READLINE_EDITOR, activity.currentMode)
    }

    @Test
    fun `switching radio back to append only works before lock`() {
        createActivity()
        switchToReadlineMode()
        activity.modeRadioGroup.check(activity.appendOnlyRadio.id)
        drainMain()
        assertEquals(DemoTextMode.APPEND_ONLY, activity.currentMode)
    }

    // ── Append-only isolation ──

    @Test
    fun `append only mode does not submit snapshot requests`() {
        createActivity()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        assertTrue(capturedRequests.isEmpty())
    }

    // ── Readline snapshot submission ──

    @Test
    fun `readline editor submits complete snapshot on text insertion`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        assertEquals(1, capturedRequests.size)
        val req = capturedRequests[0] as UiEditorRequest.Snapshot
        assertEquals("hello", req.text)
        assertEquals(activity.lifecycleGeneration, req.generation)
    }

    @Test
    fun `readline submits complete snapshot after multiple insertions`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("a")
        activity.textInput.append("b")
        drainMain()
        assertEquals(2, capturedRequests.size)
        assertEquals("a", (capturedRequests[0] as UiEditorRequest.Snapshot).text)
        assertEquals("ab", (capturedRequests[1] as UiEditorRequest.Snapshot).text)
    }

    @Test
    fun `readline snapshot carries correct lifecycle generation`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        val gen = activity.lifecycleGeneration
        assertTrue(gen > 0)
        activity.textInput.setText("test")
        drainMain()
        assertEquals(gen, (capturedRequests[0] as UiEditorRequest.Snapshot).generation)
    }

    @Test
    fun `readline submits complete snapshot on deletion`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        capturedRequests.clear()
        activity.textInput.getText().delete(activity.textInput.length() - 1, activity.textInput.length())
        drainMain()
        assertEquals(1, capturedRequests.size)
        assertEquals("hell", (capturedRequests[0] as UiEditorRequest.Snapshot).text)
    }

    @Test
    fun `readline submits complete snapshot on replacement`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        capturedRequests.clear()
        activity.textInput.getText().replace(1, 4, "XX")
        drainMain()
        assertEquals(1, capturedRequests.size)
        assertEquals("hXXo", (capturedRequests[0] as UiEditorRequest.Snapshot).text)
    }

    @Test
    fun `readline submits complete snapshot on paste`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("be")
        drainMain()
        capturedRequests.clear()
        activity.textInput.getText().insert(2, "st")
        drainMain()
        assertEquals(1, capturedRequests.size)
        assertEquals("best", (capturedRequests[0] as UiEditorRequest.Snapshot).text)
    }

    @Test
    fun `readline submits empty snapshot on clear`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        capturedRequests.clear()
        activity.textInput.setText("")
        drainMain()
        assertEquals(1, capturedRequests.size)
        assertEquals("", (capturedRequests[0] as UiEditorRequest.Snapshot).text)
    }

    @Test
    fun `readline snapshot always contains complete current text`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("The quick brown fox jumps over the lazy dog")
        drainMain()
        assertEquals(1, capturedRequests.size)
        assertEquals("The quick brown fox jumps over the lazy dog",
            (capturedRequests[0] as UiEditorRequest.Snapshot).text)
    }

    // ── Mode locking ──

    @Test
    fun `mode is locked after first snapshot submission`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        assertFalse(activity.modeLocked)
        activity.textInput.setText("x")
        drainMain()
        assertTrue(activity.modeLocked)
    }

    @Test
    fun `radio buttons are disabled after mode is locked`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("x")
        drainMain()
        assertFalse(activity.appendOnlyRadio.isEnabled)
        assertFalse(activity.readlineRadio.isEnabled)
    }

    @Test
    fun `append-only first mutation also locks mode`() {
        createActivity()
        installCaptures()
        activity.textInput.setText("x")
        drainMain()
        assertTrue(activity.modeLocked)
    }

    // ── Connection/safety gating ──

    @Test
    fun `readline input disabled when gatt is null`() {
        createActivity()
        setSafetyState(SleepwalkerBleService.Companion.SafetyState.ARMED)
        SleepwalkerBleService.gatt = null
        SleepwalkerBleService.rxChar = null
        switchToReadlineMode()
        drainMain()
        assertFalse(activity.textInput.isEnabled)
    }

    @Test
    fun `readline input disabled when safety is not ARMED`() {
        createActivity()
        SleepwalkerBleService.gatt = mockk(relaxed = true)
        SleepwalkerBleService.rxChar = mockk(relaxed = true)
        setSafetyState(SleepwalkerBleService.Companion.SafetyState.DISARMED)
        switchToReadlineMode()
        drainMain()
        assertFalse(activity.textInput.isEnabled)
    }

    @Test
    fun `readline input enabled when connected and ARMED`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        drainMain()
        assertTrue(activity.textInput.isEnabled)
    }

    // ── Unknown/reset-pending gating ──

    @Test
    fun `readline input disabled in Unknown editor state`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        drainMain()
        activity.editorState = EditorState.Unknown
        SleepwalkerBleService.statusListener?.onConnectionChanged(true)
        drainMain()
        assertFalse(activity.textInput.isEnabled)
    }

    @Test
    fun `readline input disabled when reset is pending`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        drainMain()
        activity.isResetPending = true
        SleepwalkerBleService.statusListener?.onConnectionChanged(true)
        drainMain()
        assertFalse(activity.textInput.isEnabled)
    }

    // ── Result feedback: Synced ──

    @Test
    fun `Synced result updates feedback with change id and text`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        val syncResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "hello",
            result = EditorResult.Synced("hello", emptyList()),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "hello"),
            syncResult,
        )
        drainMain()
        assertEquals("Synced change (id $nextId): 'hello'",
            activity.feedbackText.text.toString())
    }


    // ── Result feedback: recoverable failures ──

    @Test
    fun `planning failure shows recoverable error and leaves input enabled`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("bad")
        drainMain()
        val failResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "bad",
            result = EditorResult.EditorFailure(
                requestedDocument = "bad",
                classification = FailureClassification.UnsupportedBehavior("multiline content"),
                plan = null,
            ),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "bad"),
            failResult,
        )
        drainMain()
        assertEquals("Planning failure: Unsupported: multiline content",
            activity.feedbackText.text.toString())
        assertTrue(activity.textInput.isEnabled)
    }

    @Test
    fun `planning error shows error reason`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("x")
        drainMain()
        val failResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "x",
            result = EditorResult.EditorFailure(
                requestedDocument = "x",
                classification = FailureClassification.PlanningError("Lua runtime error"),
                plan = null,
            ),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "x"),
            failResult,
        )
        drainMain()
        assertEquals("Planning failure: Planning error: Lua runtime error",
            activity.feedbackText.text.toString())
    }

    @Test
    fun `abi mismatch failure shows structured error`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("x")
        drainMain()
        val failResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "x",
            result = EditorResult.EditorFailure(
                requestedDocument = "x",
                classification = FailureClassification.AbiMismatch(expected = 2, actual = 1),
                plan = null,
            ),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "x"),
            failResult,
        )
        drainMain()
        assertEquals("Planning failure: ABI mismatch: expected 2, actual 1",
            activity.feedbackText.text.toString())
    }

    // ── Result feedback: terminal Unknown ──

    @Test
    fun `transport failure in Unknown state disables input`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("partial")
        drainMain()
        val terminalResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "partial",
            result = EditorResult.EditorFailure(
                requestedDocument = "partial",
                classification = FailureClassification.TransportFailure("BLE link lost"),
                plan = emptyList(),
            ),
            snapshot = terminalSnapshot("partial", "TransportFailure"),
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "partial"),
            terminalResult,
        )
        drainMain()
        assertTrue(activity.feedbackText.text.toString().contains("Transport failure: BLE link lost"))
        assertTrue(activity.feedbackText.text.toString().contains("Unknown; reset required"))
        assertFalse(activity.textInput.isEnabled)
    }

    @Test
    fun `environment failure shows terminal error when Unknown`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("x")
        drainMain()
        val terminalResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = activity.lifecycleGeneration,
            text = "x",
            result = EditorResult.EditorFailure(
                requestedDocument = "x",
                classification = FailureClassification.EnvironmentFailure("USB device disconnected"),
                plan = emptyList(),
            ),
            snapshot = terminalSnapshot("x", "EnvironmentFailure"),
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = activity.lifecycleGeneration, text = "x"),
            terminalResult,
        )
        drainMain()
        assertTrue(activity.feedbackText.text.toString().contains("Environment failure: USB device disconnected"))
        assertTrue(activity.feedbackText.text.toString().contains("reset required"))
    }

    // ── Reset behavior ──

    @Test
    fun `reset submits request through lane and updates state on result`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("hello")
        drainMain()
        assertTrue(activity.modeLocked)
        activity.ackCheckBox.isChecked = true
        drainMain()
        assertTrue(activity.resetBtn.isEnabled)
        activity.resetBtn.performClick()
        drainMain()
        assertEquals(2, capturedRequests.size)
        val resetReq = capturedRequests[1] as UiEditorRequest.Reset
        assertTrue(resetReq.acknowledgedEmpty)
        assertTrue(activity.isResetPending)
        activity.injectUiEditorResult(
            resetReq,
            UiEditorResult.Reset(id = resetReq.id, generation = resetReq.generation),
        )
        drainMain()
        assertFalse(activity.isResetPending)
        assertEquals(EditorState.Uninitialized, activity.editorState)
        assertEquals("Editor session reset. Assumed empty target.",
            activity.feedbackText.text.toString())
        assertFalse(activity.modeLocked)
    }

    @Test
    fun `reset button is disabled without ack`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        assertFalse(activity.resetBtn.isEnabled)
    }

    @Test
    fun `reset button becomes enabled after ack checkbox`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.ackCheckBox.isChecked = true
        drainMain()
        assertTrue(activity.resetBtn.isEnabled)
    }

    // ── Stale lifecycle generation ──

    @Test
    fun `stale lifecycle generation suppresses callback`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.textInput.setText("stale")
        drainMain()
        val oldGen = activity.lifecycleGeneration
        activity.lifecycleGeneration = oldGen + 1
        val staleResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = oldGen,
            text = "stale",
            result = EditorResult.Synced("stale", emptyList()),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = oldGen, text = "stale"),
            staleResult,
        )
        drainMain()
        assertEquals("Feedback: none", activity.feedbackText.text.toString())
        val freshResult = UiEditorResult.Snapshot(
            id = nextId,
            generation = oldGen + 1,
            text = "stale",
            result = EditorResult.Synced("stale", emptyList()),
            snapshot = null,
        )
        activity.injectUiEditorResult(
            UiEditorRequest.Snapshot(id = nextId, generation = oldGen + 1, text = "stale"),
            freshResult,
        )
        drainMain()
        assertEquals("Synced change (id $nextId): 'stale'",
            activity.feedbackText.text.toString())
    }

    @Test
    fun `stale reset result is ignored`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.ackCheckBox.isChecked = true
        activity.resetBtn.performClick()
        drainMain()
        assertEquals(1, capturedRequests.size)
        val resetReq = capturedRequests[0] as UiEditorRequest.Reset
        val oldGen = resetReq.generation
        activity.lifecycleGeneration = oldGen + 1
        activity.injectUiEditorResult(
            resetReq,
            UiEditorResult.Reset(id = resetReq.id, generation = oldGen),
        )
        drainMain()
        assertTrue(activity.isResetPending)
    }

    @Test
    fun `reset recovers from Unknown state`() {
        createActivity()
        enableReadlineInput()
        switchToReadlineMode()
        installCaptures()
        activity.editorState = EditorState.Unknown
        activity.isResetPending = true
        drainMain()
        activity.injectUiEditorResult(
            UiEditorRequest.Reset(id = 1, generation = activity.lifecycleGeneration, acknowledgedEmpty = true),
            UiEditorResult.Reset(id = 1, generation = activity.lifecycleGeneration),
        )
        drainMain()
        assertEquals(EditorState.Uninitialized, activity.editorState)
        assertFalse(activity.isResetPending)
        assertTrue(activity.appendOnlyRadio.isEnabled)
    }
    private fun terminalSnapshot(desiredText: String, classification: String) = EditorSnapshot(
        state = EditorState.Unknown,
        targetId = null,
        targetVersion = null,
        targetSourceHash = null,
        hostAbi = null,
        currentText = null,
        desiredText = desiredText,
        opaqueInputState = null,
        opaqueOutputState = null,
        symbolicActions = null,
        ops = null,
        layoutId = null,
        costMetricId = null,
        policyId = null,
        outcome = "UNKNOWN",
        lastPlanOps = 0,
        lastClassification = classification,
    )

}
