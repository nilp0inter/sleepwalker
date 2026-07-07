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

/**
 * Programmatic UI for the sleepwalker reference app text rendering demo.
 * Exposes connection/safety controls and streams inserted valid characters
 * through the sleepwalker-core high-level text planner.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var textInput: EditText
    private val streamLock = Any()
    private val keymapDb by lazy { JsonKeymapDatabase(resources) }
    private val planner by lazy { TextPlanner(database = keymapDb, hid = SleepwalkerBleService.hid) }
    private var selectedProfile: HostProfile = HostProfile.LINUX_US

    private val bleListener = object : SleepwalkerBleService.Companion.StatusListener {
        override fun onStatusReceived(seqId: Int, status: Int, statusName: String) {
            runOnUiThread {
                feedbackText.text = "Ack: $statusName (seq $seqId)"
            }
        }

        override fun onConnectionChanged(connected: Boolean) {
            runOnUiThread {
                statusText.text = "Status: ${if (connected) "Connected" else "Disconnected"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SwLog.event("ui", "on_create")

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

        // Text input
        textInput = EditText(this).apply {
            hint = "Type here to stream characters"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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
                if (after > before) {
                    val inserted = current.substring(start, start + after)
                    SwLog.event("ui", "text_changed", fields = mapOf(
                        "text" to current, "inserted" to inserted
                    ))
                    streamText(inserted)
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
    }

    private fun streamText(inserted: String) {
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

    override fun onStart() {
        super.onStart()
        SleepwalkerBleService.statusListener = bleListener
        val connected = SleepwalkerBleService.gatt != null
        statusText.text = "Status: ${if (connected) "Connected" else "Disconnected"}"
    }

    override fun onStop() {
        super.onStop()
        SleepwalkerBleService.statusListener = null
    }
}
