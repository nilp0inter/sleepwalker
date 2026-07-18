package io.sleepwalker.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.sleepwalker.app.diagnostics.SwLog
import androidx.appcompat.app.AppCompatActivity
import io.sleepwalker.app.ble.SleepwalkerBleService
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.view.View
import android.widget.AdapterView
import io.sleepwalker.core.keymap.JsonKeymapDatabase
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.text.TextRenderingFailure
import android.content.Intent
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.CheckBox
import io.sleepwalker.app.ble.UiEditorRequest
import io.sleepwalker.app.ble.UiEditorResult
import io.sleepwalker.app.ble.UiEditorListener
import io.sleepwalker.core.editor.EditorState
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.FailureClassification

/**
 * Programmatic UI for the sleepwalker reference app text rendering demo.
 * Exposes connection/safety controls and streams inserted valid characters
 * through the sleepwalker-core high-level text planner.
 */
enum class DemoTextMode { APPEND_ONLY, READLINE_EDITOR }

class MainActivity : AppCompatActivity() {

    companion object {
        private val generationCounter = java.util.concurrent.atomic.AtomicLong(0)
        const val EXTRA_READLINE = "io.sleepwalker.app.EXTRA_READLINE"
        const val EXTRA_MODE = "io.sleepwalker.app.EXTRA_MODE"
    }

    internal lateinit var statusText: TextView
    internal lateinit var feedbackText: TextView
    internal lateinit var textInput: EditText
    private val streamLock = Any()
    private val keymapDb by lazy { JsonKeymapDatabase(resources) }
    private val planner by lazy { TextPlanner(database = keymapDb, hid = SleepwalkerBleService.hid) }
    private var selectedProfile: HostProfile = HostProfile.LINUX_US

    internal var currentMode = DemoTextMode.APPEND_ONLY
    internal var modeLocked = false
    internal var isResetPending = false
    internal var editorState: EditorState = EditorState.Uninitialized
    private var isProgrammaticClear = false
    internal var lifecycleGeneration: Long = 0

    internal lateinit var appendOnlyRadio: RadioButton
    internal lateinit var readlineRadio: RadioButton
    internal lateinit var modeRadioGroup: RadioGroup
    internal lateinit var readlineInfoContainer: LinearLayout
    internal lateinit var ackCheckBox: CheckBox
    internal lateinit var resetBtn: Button

    // ── Test seams ──
    internal var submitRequestFn: (UiEditorRequest) -> Unit = { request ->
        SleepwalkerBleService.submitUiEditorRequest(request)
    }
    internal var nextChangeIdFn: () -> Long = { SleepwalkerBleService.nextUiChangeId() }

    internal fun injectUiEditorResult(request: UiEditorRequest, result: UiEditorResult) {
        uiEditorListener.onUiEditorResult(request, result)
    }


    private val bleListener = object : SleepwalkerBleService.Companion.StatusListener {
        override fun onStatusReceived(seqId: Int, status: Int, statusName: String) {
            runOnUiThread {
                if (currentMode == DemoTextMode.APPEND_ONLY) {
                    feedbackText.text = "Ack: $statusName (seq $seqId)"
                }
                updateInputState()
            }
        }

        override fun onConnectionChanged(connected: Boolean) {
            runOnUiThread {
                statusText.text = "Status: ${if (connected) "Connected" else "Disconnected"}"
                updateInputState()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SwLog.event("ui", "on_create")
        lifecycleGeneration = generationCounter.incrementAndGet()

        // Programmatic vertical LinearLayout container
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }

        // Layout Profile Selection Label
        val profileLabel = TextView(this).apply {
            text = "Target Layout Profile:"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(profileLabel)

        // Profile Spinner Dropdown
        val profileList = keymapDb.profiles.toList()
        val profileKeys = profileList.map { it.key }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileKeys).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val profileSpinner = Spinner(this).apply {
            adapter = spinnerAdapter
            val defaultIdx = profileList.indexOfFirst { it == HostProfile.LINUX_US }.coerceAtLeast(0)
            setSelection(defaultIdx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedProfile = profileList[position]
                    SwLog.event("ui", "profile_selected", fields = mapOf("profile" to selectedProfile.key))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 32)
            }
        }
        rootLayout.addView(profileSpinner)

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                SleepwalkerBleService.startScan(this@MainActivity)
            }
        }
        buttonRow.addView(connectBtn)

        val armBtn = Button(this).apply {
            text = "Arm"
            setOnClickListener {
                SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.arm(), 0)
            }
        }
        buttonRow.addView(armBtn)

        val killBtn = Button(this).apply {
            text = "Kill"
            setOnClickListener {
                SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.kill(), 0)
            }
        }
        buttonRow.addView(killBtn)

        rootLayout.addView(buttonRow)

        // Status field
        statusText = TextView(this).apply {
            text = "Status: Disconnected"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(statusText)

        // Mode selection label
        val modeLabel = TextView(this).apply {
            text = "Text Semantics Mode:"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        rootLayout.addView(modeLabel)

        // Mode RadioGroup
        modeRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        appendOnlyRadio = RadioButton(this).apply {
            text = "Append Only"
            id = View.generateViewId()
        }
        modeRadioGroup.addView(appendOnlyRadio)

        readlineRadio = RadioButton(this).apply {
            text = "Readline Editor"
            id = View.generateViewId()
        }
        modeRadioGroup.addView(readlineRadio)

        // By default check append only
        appendOnlyRadio.isChecked = true

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == appendOnlyRadio.id) {
                currentMode = DemoTextMode.APPEND_ONLY
                updateUiForMode(DemoTextMode.APPEND_ONLY)
            } else if (checkedId == readlineRadio.id) {
                currentMode = DemoTextMode.READLINE_EDITOR
                updateUiForMode(DemoTextMode.READLINE_EDITOR)
            }
        }
        rootLayout.addView(modeRadioGroup)

        // Readline Container
        readlineInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 16)
            visibility = View.GONE
        }

        val targetLabel = TextView(this).apply {
            text = "Target: readline-emacs-ascii (GNU Readline 8.2 Emacs-mode)"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        readlineInfoContainer.addView(targetLabel)

        val constraintsLabel = TextView(this).apply {
            text = "Constraints: Printable ASCII, single-line."
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        readlineInfoContainer.addView(constraintsLabel)

        val resetGuidanceLabel = TextView(this).apply {
            text = "Guidance: The physical Readline target must already be empty. This reset clears local state and does not clear or observe the physical target buffer."
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        readlineInfoContainer.addView(resetGuidanceLabel)

        ackCheckBox = CheckBox(this).apply {
            text = "Acknowledge physical target is empty"
            setOnCheckedChangeListener { _, isChecked ->
                resetBtn.isEnabled = isChecked && !isResetPending
            }
        }
        readlineInfoContainer.addView(ackCheckBox)

        resetBtn = Button(this).apply {
            text = "Reset Session"
            isEnabled = false
            setOnClickListener {
                submitReset()
            }
        }
        readlineInfoContainer.addView(resetBtn)

        rootLayout.addView(readlineInfoContainer)

        // Text input
        textInput = EditText(this).apply {
            hint = "Type here to stream characters"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 32)
            }
        }
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, after: Int) {
                val current = s?.toString() ?: ""
                if (currentMode == DemoTextMode.APPEND_ONLY) {
                    if (after > before) {
                        val inserted = current.substring(start, start + after)
                        SwLog.event("ui", "text_changed", fields = mapOf(
                            "text" to current, "inserted" to inserted
                        ))
                        streamText(inserted)
                    }
                } else {
                    if (isProgrammaticClear) return
                    submitSnapshot(current)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        rootLayout.addView(textInput)
        textInput.requestFocus()

        // Feedback field
        feedbackText = TextView(this).apply {
            text = "Feedback: none"
            textSize = 14f
        }
        rootLayout.addView(feedbackText)

        setContentView(rootLayout)
        handleIntentExtras(intent)
    }

    private fun streamText(inserted: String) {
        onTargetMutationAdmitted()
        if (SleepwalkerBleService.gatt == null) {
            feedbackText.text = "Error: not connected"
            return
        }
        Thread {
            synchronized(streamLock) {
                val result = planner.plan(inserted, selectedProfile)
                runOnUiThread {
                    if (result.ok) {
                        feedbackText.text = "Sent: $inserted"
                    } else {
                        val err = result.failure
                        val msg = when (err) {
                            is TextRenderingFailure.MissingLayout -> "Missing layout: ${err.profile.key}"
                            is TextRenderingFailure.UnrepresentableGlyph -> "Unrepresentable glyph: '${err.ch}'"
                            else -> "Planning failed"
                        }
                        feedbackText.text = "Error: $msg"
                    }
                }
                if (result.ok) {
                    val ops = result.plan!!
                    val compiled = TapScriptCompiler.compile(ops, SleepwalkerBleService.hid)
                    compiled.forEach { op ->
                        SleepwalkerBleService.sendOp(op, op.seqId)
                        Thread.sleep(390)
                    }
                }
            }
        }.start()
    }

    private fun onTargetMutationAdmitted() {
        if (!modeLocked) {
            runOnUiThread {
                lockModeSelection()
            }
        }
    }

    private fun lockModeSelection() {
        modeLocked = true
        appendOnlyRadio.isEnabled = false
        readlineRadio.isEnabled = false
    }

    private fun unlockModeSelection() {
        modeLocked = false
        appendOnlyRadio.isEnabled = true
        readlineRadio.isEnabled = true
    }

    private fun updateUiForMode(mode: DemoTextMode) {
        if (mode == DemoTextMode.READLINE_EDITOR) {
            readlineInfoContainer.visibility = View.VISIBLE
            textInput.hint = "Type here (reconciled snapshot)"
        } else {
            readlineInfoContainer.visibility = View.GONE
            textInput.hint = "Type here to stream characters"
        }
        updateInputState()
    }

    private fun updateInputState() {
        runOnUiThread {
            if (currentMode == DemoTextMode.READLINE_EDITOR) {
                val connected = SleepwalkerBleService.gatt != null && SleepwalkerBleService.rxChar != null
                val armed = SleepwalkerBleService.safetyState == SleepwalkerBleService.Companion.SafetyState.ARMED
                val isUnknown = editorState == EditorState.Unknown
                textInput.isEnabled = connected && armed && !isUnknown && !isResetPending
            } else {
                textInput.isEnabled = true
            }
        }
    }

    private fun submitSnapshot(text: String) {
        onTargetMutationAdmitted()
        val changeId = nextChangeIdFn()
        val req = UiEditorRequest.Snapshot(
            id = changeId,
            generation = lifecycleGeneration,
            text = text
        )
        submitRequestFn(req)
    }

    private fun submitReset() {
        isResetPending = true
        resetBtn.isEnabled = false
        updateInputState()
        val changeId = nextChangeIdFn()
        val req = UiEditorRequest.Reset(
            id = changeId,
            generation = lifecycleGeneration,
            acknowledgedEmpty = true
        )
        submitRequestFn(req)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent == null) return
        val readlineExtra = intent.getBooleanExtra(EXTRA_READLINE, false) ||
                intent.getStringExtra(EXTRA_READLINE) == "true" ||
                intent.getStringExtra(EXTRA_MODE) == "READLINE_EDITOR"
        if (readlineExtra) {
            currentMode = DemoTextMode.READLINE_EDITOR
            readlineRadio.isChecked = true
            updateUiForMode(DemoTextMode.READLINE_EDITOR)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private val uiEditorListener = object : UiEditorListener {
        override fun onUiEditorResult(request: UiEditorRequest, result: UiEditorResult) {
            runOnUiThread {
                if (result.generation != lifecycleGeneration) {
                    return@runOnUiThread
                }
                when (result) {
                    is UiEditorResult.Snapshot -> {
                        val requestedText = result.text
                        val editorResult = result.result
                        val snapshot = result.snapshot
                        editorState = snapshot?.state ?: editorState
                        
                        when (editorResult) {
                            is EditorResult.Synced -> {
                                feedbackText.text = "Synced change (id ${result.id}): '$requestedText'"
                            }
                            is EditorResult.EditorFailure -> {
                                val classificationName = editorResult.classification.javaClass.simpleName
                                val description = when (val c = editorResult.classification) {
                                    is FailureClassification.AbiMismatch -> "ABI mismatch: expected ${c.expected}, actual ${c.actual}"
                                    is FailureClassification.UnrepresentableContent -> "Unrepresentable character: '${c.glyph}'"
                                    is FailureClassification.InconsistentPrediction -> "Inconsistent prediction: expected '${c.expected}', predicted '${c.predicted}'"
                                    is FailureClassification.UnsupportedBehavior -> "Unsupported: ${c.reason}"
                                    is FailureClassification.PlanningError -> "Planning error: ${c.reason}"
                                    is FailureClassification.TransportFailure -> "Transport failure: ${c.reason}"
                                    is FailureClassification.EnvironmentFailure -> "Environment failure: ${c.reason}"
                                    else -> "Failure: $classificationName"
                                }
                                if (editorState == EditorState.Unknown) {
                                    feedbackText.text = "Terminal error: $description. Target state Unknown; reset required."
                                } else {
                                    feedbackText.text = "Planning failure: $description"
                                }
                            }
                        }
                        updateInputState()
                    }
                    is UiEditorResult.Reset -> {
                        isResetPending = false
                        editorState = EditorState.Uninitialized
                        isProgrammaticClear = true
                        textInput.setText("")
                        isProgrammaticClear = false
                        
                        ackCheckBox.isChecked = false
                        feedbackText.text = "Editor session reset. Assumed empty target."
                        
                        unlockModeSelection()
                        updateInputState()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SleepwalkerBleService.statusListener = bleListener
        SleepwalkerBleService.uiEditorListener = uiEditorListener
        val connected = SleepwalkerBleService.gatt != null
        statusText.text = "Status: ${if (connected) "Connected" else "Disconnected"}"
        updateInputState()
    }

    override fun onStop() {
        super.onStop()
        SleepwalkerBleService.statusListener = null
        SleepwalkerBleService.uiEditorListener = null
    }
}
