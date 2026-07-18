package io.sleepwalker.app.adb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.os.Handler
import android.os.Looper
import io.sleepwalker.app.ble.SleepwalkerBleService
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.JsonKeymapDatabase
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.text.TextPlanner
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.MouseOps
import io.sleepwalker.core.hid.SessionStatusParser
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.protocol.MouseRel
import io.sleepwalker.core.protocol.Opcodes
import io.sleepwalker.core.protocol.Usages
import java.util.UUID
import io.sleepwalker.app.ble.BleEditorExecutor
import io.sleepwalker.core.editor.Editor
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.FailureClassification
import io.sleepwalker.core.editor.TargetPackageLoader
import io.sleepwalker.core.editor.AbiValue
import io.sleepwalker.core.editor.SymbolicAction
import io.sleepwalker.core.editor.ExecutionPolicy
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.nio.ByteBuffer

/**
 * Structured result of an [executeSetText] call.
 *
 * Carries every field the HIL runner diagnostics require, derived from both
 * the public [EditorResult] and the internal [VerificationEntry] trace.
 *
 * @property seq                ADB command sequence id.
 * @property textLength         length of the decoded requested text.
 * @property editorResult       "Synced" or "EditorFailure".
 * @property planOps            number of low-level keyboard operations in
 *                              the executed plan (0 for no-op / pre-exec
 *                              failure).
 * @property failureClass       simple name of [FailureClassification], or
 *                              null on success.
 * @property transportStatus    "ok" for [ExecutionOutcome.Delivered],
 *                              otherwise the transport failure reason.
 * @property abiVersion         host ABI version used for the plan.
 * @property targetId           loaded target package identity.
 */
data class SetTextResult(
    val seq: Int,
    val textLength: Int,
    val editorResult: String,
    val planOps: Int,
    val operationSeqs: List<Int>,
    val failureClass: String?,
    val transportStatus: String?,
    val abiVersion: Int?,
    val targetId: String?,
    val targetVersion: String?,
    val targetSourceHash: String?,
    val currentText: String?,
    val desiredText: String?,
    val opaqueInputState: AbiValue?,
    val opaqueOutputState: AbiValue?,
    val symbolicActions: List<SymbolicAction>?,
    val compiledOperations: List<String>?,
    val layoutId: String?,
    val costMetricId: String?,
    val policyId: String?,
    val transactionOutcome: String?,
    val classification: String? = null,
    val planOpNames: List<String> = emptyList(),
    val operationStatuses: List<String> = emptyList(),
    val failure: String? = null,
)
/**
 * ADB command intake surface + single BLE session owner.
 *
 * Android 12+ blocks background apps from starting services (both
 * foreground and background). This receiver uses `goAsync()` to get up
 * to ~10 seconds of execution time, and performs BLE operations
 * directly on a background thread within that window.
 *
 * The BLE connection is maintained as a static reference so it survives
 * across broadcasts (the GATT connection persists even after the
 * receiver finishes, as it's owned by the BluetoothManager). This makes
 * the receiver the single BLE session owner — there is no duplicated
 * scan/connect/write logic elsewhere.
 *
 * Command construction is delegated to the `sleepwalker-core` library
 * ([LowLevelHid], [MouseOps]) rather than built app-locally. This is
 * the public library command path exercised by HIL.
 *
 * Agent sends explicit ADB broadcasts:
 *   adb shell am broadcast -a io.sleepwalker.app.COMMAND \
 *     -n io.sleepwalker.app/.adb.AdbCommandReceiver \
 *     --es cmd <command> [--es key <USB_KEY_SPACE>] [--ei seq <id>]
 *
 * Mouse commands:
 *   --es cmd mouse-click        left button click (down + up)
 *   --es cmd mouse-move         --ei dx <n> --ei dy <n>
 *   --es cmd mouse-scroll       --ei amount <n>
 *   --es cmd mouse-pan          --ei amount <n>
 *   --es cmd mouse-release      release all mouse buttons
 */
class AdbCommandReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "io.sleepwalker.app.COMMAND"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_KEY = "key"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_DX = "dx"
        const val EXTRA_DY = "dy"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TEXT_ENCODED = "text_encoded"
        const val EXTRA_OS = "os"
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_VARIANT = "variant"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        // All BLE session state is delegated to SleepwalkerBleService.
        private val commandExecutor = Executors.newSingleThreadExecutor()
        private val base64UrlSyntax = Regex("[A-Za-z0-9_-]*={0,2}")

        /**
         * Decode a base64url-encoded UTF-8 string with strict validation.
         *
         * Package-visible for testability. Returns null if the input is
         * not valid base64url or the decoded bytes are not well-formed
         * UTF-8 (rejects malformed byte sequences and unmappable
         * characters).
         */
        internal fun decodeBase64UrlStrict(encoded: String?): String? {
            if (encoded == null || !base64UrlSyntax.matches(encoded)) return null
            val padding = encoded.indexOf('=')
            if (encoded.length % 4 == 1 || (padding >= 0 && encoded.length % 4 != 0)) return null
            return try {
                val bytes = Base64.decode(encoded, Base64.URL_SAFE)
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Decode a base64url-encoded UTF-8 string (lenient — replaces
         * malformed sequences).
         * @return Decoded string or null if decoding fails.
         */
        internal fun decodeBase64Url(encoded: String?): String? {
            if (encoded == null) return null
            return try {
                val bytes = Base64.decode(encoded, Base64.URL_SAFE)
                String(bytes, Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        internal fun formatLowLevelOp(op: LowLevelOp): String {
            return when (op.opcode) {
                Opcodes.KEY_TAP -> {
                    val usbUsage = op.payload.getOrNull(0)?.toInt()?.let { it and 0xFF } ?: 0
                    val usageName = Usages.byUsb(usbUsage)?.name ?: "USB_KEY_UNKNOWN"
                    "tap $usageName"
                }
                Opcodes.KEY_DOWN -> {
                    val usbUsage = op.payload.getOrNull(0)?.toInt()?.let { it and 0xFF } ?: 0
                    val usageName = Usages.byUsb(usbUsage)?.name ?: "USB_KEY_UNKNOWN"
                    "down $usageName"
                }
                Opcodes.KEY_UP -> {
                    val usbUsage = op.payload.getOrNull(0)?.toInt()?.let { it and 0xFF } ?: 0
                    val usageName = Usages.byUsb(usbUsage)?.name ?: "USB_KEY_UNKNOWN"
                    "up $usageName"
                }
                Opcodes.ARM -> "arm"
                Opcodes.DISARM -> "disarm"
                Opcodes.KILL -> "kill"
                Opcodes.RELEASE_ALL -> "release_all"
                Opcodes.KEYBOARD_TAP_SCRIPT -> "keyboard_tap_script"
                Opcodes.MOUSE_REL_REPORT -> "mouse_rel_report"
                else -> "unknown"
            }
        }

        /**
         * Reflectively read the Editor's internal [verificationState] (which
         * is `internal` in the core module) to recover the assumed document
         * for opaque-state replay.
         */
        internal fun reflectAssumedDocument(editor: Editor): String {
            return try {
                val getter = Editor::class.java.declaredMethods.firstOrNull {
                    it.name.startsWith("getVerificationState")
                }?.apply { isAccessible = true }
                val vs = getter?.invoke(editor) ?: return ""
                val docField = vs.javaClass.getDeclaredField("assumedDocument").apply { isAccessible = true }
                docField.get(vs) as? String ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    private var currentContext: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: run {
            SwLog.adb("missing_cmd")
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        val seq = intent.getIntExtra(EXTRA_SEQ, 0)
        val dx = intent.getIntExtra(EXTRA_DX, 0)
        val dy = intent.getIntExtra(EXTRA_DY, 0)
        val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
        val text = intent.getStringExtra(EXTRA_TEXT)
        val textEncoded = intent.getStringExtra(EXTRA_TEXT_ENCODED)
        val os = intent.getStringExtra(EXTRA_OS)
        val layout = intent.getStringExtra(EXTRA_LAYOUT)
        val variant = intent.getStringExtra(EXTRA_VARIANT)
        val opaqueInputState = intent.getStringExtra("opaque_input_state")
        val currentTextEncoded = intent.getStringExtra("current_text_encoded")
        val currentText = intent.getStringExtra("current_text")

        SwLog.adb("intake", seq, mapOf("cmd" to cmd, "key" to key,
            "dx" to dx, "dy" to dy, "amount" to amount, "text" to text, "text_encoded" to textEncoded,
            "os" to os, "layout" to layout, "variant" to variant,
            "opaque_input_state" to opaqueInputState, "current_text_encoded" to currentTextEncoded, "current_text" to currentText))

        currentContext = context

        // Use goAsync for up to ~10s of background work.
        val pending = goAsync()
        commandExecutor.execute {
            try {
                handleCommand(
                    cmd, key, text, textEncoded, seq, dx, dy, amount, os, layout, variant,
                    opaqueInputState = opaqueInputState,
                    currentTextEncoded = currentTextEncoded,
                    currentText = currentText,
                ) { pending.resultData = it }
            } catch (e: Exception) {
                SwLog.failure("exception", seq, mapOf("error" to (e.message ?: "unknown")))
                if (cmd == "set-text") {
                    pending.resultData = setTextDiagnosticJson(
                        invalidSetTextResult(
                            seq,
                            "command_exception:${e.javaClass.simpleName}",
                            "environment",
                        ),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleCommand(
        cmd: String, key: String?, text: String?, textEncoded: String?, seq: Int,
        dx: Int, dy: Int, amount: Int, os: String?, layout: String?, variant: String?,
        opaqueInputState: String? = null,
        currentTextEncoded: String? = null,
        currentText: String? = null,
        resultDataSink: (String) -> Unit,
    ) {
        SwLog.ble("command", seq, mapOf("cmd" to cmd, "key" to key, "text" to text, "text_encoded" to textEncoded,
            "os" to os, "layout" to layout, "variant" to variant))
        when (cmd) {
            "connect" -> currentContext?.let { SleepwalkerBleService.startScan(it) }
            "status" -> SwLog.ble("status", fields = mapOf(
                "connected" to (SleepwalkerBleService.gatt != null),
                "mtu" to SleepwalkerBleService.negotiatedMtu
            ))
            "arm" -> SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.arm(seqOrNext(seq)), seq)
            "disarm" -> SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.disarm(seqOrNext(seq)), seq)
            "kill" -> SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.kill(seqOrNext(seq)), seq)
            "release-all" -> SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.releaseAll(seqOrNext(seq)), seq)
            "inject" -> {
                val usage = if (key != null) {
                    try { Usages.byName(key) } catch (_: Exception) {
                        SwLog.failure("unknown_key", seq, mapOf("key" to key)); return
                    }
                } else Usages.USB_KEY_SPACE
                SleepwalkerBleService.sendOp(SleepwalkerBleService.hid.keyTap(usage, seqOrNext(seq)), seq)
            }
            "type-text" -> {
                // Decode encoded text if present, otherwise use plain text
                val decodedText = when {
                    textEncoded != null -> {
                        val decoded = decodeBase64Url(textEncoded)
                        if (decoded == null) {
                            SwLog.failure("decode_failed", seq, mapOf(
                                "reason" to "invalid_base64url",
                                "encoded" to textEncoded.take(100)))
                            return
                        }
                        SwLog.frame("decoded_text", seq, mapOf(
                            "encoded_length" to textEncoded.length,
                            "decoded_length" to decoded.length,
                            "encoded_preview" to textEncoded.take(50),
                            "decoded_preview" to decoded.take(50)))
                        decoded
                    }
                    text != null -> text
                    else -> ""
                }

                val resolvedOs = os ?: "linux"
                val resolvedLayout = layout ?: "us"
                val profile = HostProfile(resolvedOs, resolvedLayout, variant)

                val keymapDb = currentContext?.let { JsonKeymapDatabase(it.resources) } ?: SeedKeymapDatabase
                val planner = TextPlanner(database = keymapDb, hid = SleepwalkerBleService.hid)
                val result = planner.plan(decodedText, profile)
                if (result.ok) {
                    val ops = result.plan!!
                    val compiled = TapScriptCompiler.compile(ops, SleepwalkerBleService.hid)
                    SwLog.frame("type_text", seq, mapOf(
                        "ops" to compiled.size,
                        "text_length" to decodedText.length,
                        "text_preview" to decodedText.take(100)))
                    compiled.forEach {
                        SleepwalkerBleService.sendOp(it, it.seqId)
                        Thread.sleep(390)
                    }
                } else {
                    val err = result.failure
                    SwLog.failure("type_text_failed", seq, mapOf("reason" to err.toString()))
                }
            }
            "reset-editor" -> {
                val newEditor = synchronized(SleepwalkerBleService.editorLock) {
                    val ed = SleepwalkerBleService.recreateEditor(ExecutionPolicy.CONFORMANCE)
                    SleepwalkerBleService.lastVerification = null
                    ed
                }
                val sourceHash = try {
                    val targetField = Editor::class.java.getDeclaredField("target").apply { isAccessible = true }
                    val targetObj = targetField.get(newEditor)
                    val sourceHashField = targetObj.javaClass.getDeclaredField("sourceHash").apply { isAccessible = true }
                    sourceHashField.get(targetObj) as String
                } catch (e: Exception) {
                    ""
                }
                resultDataSink(
                    JSONObject()
                        .put("cmd", "reset-editor")
                        .put("seq", seq)
                        .put("ok", true)
                        .put("package_id", newEditor.targetId)
                        .put("package_version", newEditor.targetVersion)
                        .put("package_source_hash", sourceHash)
                        .put("host_abi", 1)
                        .put("layout_id", newEditor.profile.key)
                        .put("cost_metric_id", "op_count:1")
                        .put("policy_id", "CONFORMANCE")
                        .toString(),
                )
            }

            "set-text" -> {
                val result = decodeAndExecuteSetText(
                    textEncoded = textEncoded,
                    plainText = text,
                    seq = seq,
                    lock = SleepwalkerBleService.editorLock,
                    opaqueInputStateEncoded = opaqueInputState,
                    currentTextEncoded = currentTextEncoded,
                    currentTextRaw = currentText,
                )

                emitSetTextDiagnostics(result)
                resultDataSink(setTextDiagnosticJson(result))
            }

            // Mouse commands — routed through the public library mouse API.
            "mouse-click" -> {
                val ops = SleepwalkerBleService.mouse.leftClick()
                SwLog.frame("mouse_click", seq, mapOf(
                    "ops" to ops.size, "opcode" to ops[0].name))
                ops.forEach {
                    SleepwalkerBleService.sendOp(it, it.seqId)
                    Thread.sleep(50)
                }
            }
            "mouse-move" -> {
                val ops = SleepwalkerBleService.mouse.move(dx, dy, seqOrNext(seq))
                SwLog.frame("mouse_move", seq, mapOf(
                    "ops" to ops.size, "dx" to dx, "dy" to dy))
                ops.forEach {
                    SleepwalkerBleService.sendOp(it, it.seqId)
                    Thread.sleep(50)
                }
            }
            "mouse-scroll" -> {
                val ops = SleepwalkerBleService.mouse.scroll(amount, seqOrNext(seq))
                SwLog.frame("mouse_scroll", seq, mapOf(
                    "ops" to ops.size, "amount" to amount))
                ops.forEach {
                    SleepwalkerBleService.sendOp(it, it.seqId)
                    Thread.sleep(50)
                }
            }
            "mouse-pan" -> {
                val ops = SleepwalkerBleService.mouse.pan(amount, seqOrNext(seq))
                SwLog.frame("mouse_pan", seq, mapOf(
                    "ops" to ops.size, "amount" to amount))
                ops.forEach {
                    SleepwalkerBleService.sendOp(it, it.seqId)
                    Thread.sleep(50)
                }
            }
            "mouse-release" -> {
                SleepwalkerBleService.sendOp(SleepwalkerBleService.mouse.releaseButtons(seqOrNext(seq)), seq)
            }
            "disconnect" -> {
                SleepwalkerBleService.disconnect()
            }
            else -> SwLog.failure("unknown_cmd", seq, mapOf("cmd" to cmd))
        }
    }

    private fun seqOrNext(seq: Int): Int =
        if (seq > 0) seq else SleepwalkerBleService.hid.nextSeqId()
    // ── set-text command execution seam ──

    internal fun executeSetText(
        text: String,
        seq: Int,
        lock: Any = SleepwalkerBleService.editorLock,
        resultDecorator: (SetTextResult) -> SetTextResult = { it },
        setTextBlock: (String) -> EditorResult,
    ): SetTextResult = synchronized(lock) {
        SleepwalkerBleService.lastVerification = null
        val outcome = setTextBlock(text)
        val v = SleepwalkerBleService.lastVerification

        val result = when (outcome) {
            is EditorResult.Synced -> SetTextResult(
                seq = seq,
                textLength = text.length,
                editorResult = "Synced",
                planOps = outcome.plan.size,
                operationSeqs = outcome.plan.map { it.seqId },
                failureClass = null,
                transportStatus = "ok",
                abiVersion = v?.abiVersion,
                targetId = v?.targetId,
                targetVersion = v?.targetVersion,
                targetSourceHash = v?.targetSourceHash,
                currentText = v?.currentText,
                desiredText = v?.desiredText,
                opaqueInputState = v?.opaqueInputState,
                opaqueOutputState = v?.opaqueOutputState,
                symbolicActions = v?.symbolicActions,
                compiledOperations = outcome.plan.map { formatLowLevelOp(it) },
                layoutId = v?.layoutId,
                costMetricId = v?.costMetricId,
                policyId = v?.policyId,
                transactionOutcome = v?.outcome,
                classification = null,
                planOpNames = outcome.plan.map { it.name },
                operationStatuses = emptyList(),
                failure = null,
            )
            is EditorResult.EditorFailure -> {
                val classification = outcome.classification
                val transportStatus = when (classification) {
                    is FailureClassification.TransportFailure ->
                        "transport: ${classification.reason}"
                    else -> null
                }
                SetTextResult(
                    seq = seq,
                    textLength = text.length,
                    editorResult = "EditorFailure",
                    planOps = outcome.plan?.size ?: 0,
                    operationSeqs = outcome.plan?.map { it.seqId } ?: emptyList(),
                    failureClass = when (classification) {
                        is FailureClassification.TransportFailure -> "transport"
                        is FailureClassification.FixtureFailure -> "fixture"
                        is FailureClassification.SyncFailure -> "sync"
                        is FailureClassification.EnvironmentFailure -> "environment"
                        is FailureClassification.NonReproducible -> "non_reproducible"
                        else -> "planning"
                    },
                    transportStatus = transportStatus,
                    abiVersion = v?.abiVersion,
                    targetId = v?.targetId,
                    targetVersion = v?.targetVersion,
                    targetSourceHash = v?.targetSourceHash,
                    currentText = v?.currentText,
                    desiredText = v?.desiredText ?: text,
                    opaqueInputState = v?.opaqueInputState,
                    opaqueOutputState = v?.opaqueOutputState,
                    symbolicActions = v?.symbolicActions,
                    compiledOperations = (outcome.plan ?: v?.ops)?.map { formatLowLevelOp(it) },
                    layoutId = v?.layoutId,
                    costMetricId = v?.costMetricId,
                    policyId = v?.policyId,
                    transactionOutcome = v?.outcome,
                    classification = classification.javaClass.simpleName,
                    planOpNames = (outcome.plan ?: v?.ops)?.map { it.name } ?: emptyList(),
                    operationStatuses = emptyList(),
                    failure = classification.toString(),
                )
            }
        }
        resultDecorator(result)
    }

    /**
     * Production convenience — delegates to [executeSetText] with
     * [setTextBlock] = [editor][Editor.setText].
     *
     * @see executeSetText for the full contract.
     */
    internal fun executeSetText(
        text: String,
        seq: Int,
        editor: Editor = SleepwalkerBleService.editor,
        lock: Any = SleepwalkerBleService.editorLock,
    ): SetTextResult = executeSetText(
        text = text,
        seq = seq,
        lock = lock,
        resultDecorator = { result ->
            val statuses = (editor.executor as? BleEditorExecutor)?.lastStatuses.orEmpty()
            result.copy(
                targetId = editor.targetId,
                targetVersion = editor.targetVersion,
                operationSeqs = statuses.map { it.seqId },
                operationStatuses = statuses.map { "${it.seqId}:${it.statusName}" },
            )
        },
        setTextBlock = { editor.setText(it) },
    )


    // ── Combined decode + execute seam ──

    /**
     * Decode [textEncoded] and execute [setTextBlock] only on success.
     *
     * Returns [SetTextResult] when decoding succeeds and execution
     * completes; returns null when decoding fails (no-op, zero HID ops).
     */
    internal fun decodeAndExecuteSetText(
        textEncoded: String?,
        plainText: String?,
        seq: Int,
        lock: Any = SleepwalkerBleService.editorLock,
        opaqueInputStateEncoded: String? = null,
        currentTextEncoded: String? = null,
        currentTextRaw: String? = null,
        setTextBlock: ((String) -> EditorResult)? = null,
    ): SetTextResult {
        if (opaqueInputStateEncoded != null) {
            val jsonStr = decodeBase64Url(opaqueInputStateEncoded)
            if (jsonStr == null) {
                SwLog.failure("set_text_restore_failed", seq, mapOf("reason" to "invalid_opaque_base64"))
                return invalidSetTextResult(seq, "invalid_opaque_base64")
            }
            val jsonVal = try {
                if (jsonStr.trim().startsWith("[")) {
                    JSONArray(jsonStr)
                } else {
                    JSONObject(jsonStr)
                }
            } catch (e: Exception) {
                try {
                    JSONObject("{\"val\":$jsonStr}").get("val")
                } catch (e2: Exception) {
                    SwLog.failure("set_text_restore_failed", seq, mapOf("reason" to "invalid_json:${e.message}"))
                    return invalidSetTextResult(seq, "invalid_json_state")
                }
            }
            val abiVal = jsonToAbiValue(jsonVal)
            val currentDoc = when {
                currentTextEncoded != null -> {
                    val dec = decodeBase64UrlStrict(currentTextEncoded)
                    if (dec == null) {
                        SwLog.failure("set_text_restore_failed", seq, mapOf("reason" to "invalid_current_text_base64"))
                        return invalidSetTextResult(seq, "invalid_current_text_base64")
                    }
                    dec
                }
                currentTextRaw != null -> currentTextRaw
                else -> synchronized(lock) {
                    try {
                        reflectAssumedDocument(SleepwalkerBleService.editor)
                    } catch (e: Exception) {
                        ""
                    }
                }
            }
            synchronized(lock) {
                try {
                    SleepwalkerBleService.editor.restore(currentDoc, abiVal)
                    SwLog.ble("state_restored", seq, mapOf("doc" to currentDoc, "state" to abiVal.toString()))
                } catch (e: Exception) {
                    SwLog.failure("state_restore_failed", seq, mapOf("error" to (e.message ?: "unknown")))
                    return invalidSetTextResult(seq, "restore_exception:${e.javaClass.simpleName}")
                }
            }
        }

        val decodedText = when {
            textEncoded != null -> {
                val decoded = decodeBase64UrlStrict(textEncoded)
                if (decoded == null) {
                    SwLog.failure("set_text_decode_failed", seq, mapOf(
                        "reason" to "invalid_base64url_or_utf8",
                        "encoded" to textEncoded.take(100)))
                    return invalidSetTextResult(seq, "invalid_base64url_or_utf8")
                }
                decoded
            }
            plainText != null -> plainText
            else -> {
                SwLog.failure("set_text_missing_input", seq)
                return invalidSetTextResult(seq, "missing_text_input")
            }
        }

        return if (setTextBlock == null) {
            executeSetText(decodedText, seq, SleepwalkerBleService.editor, lock)
        } else {
            executeSetText(
                text = decodedText,
                seq = seq,
                lock = lock,
                setTextBlock = setTextBlock,
            )
        }
    }

    private fun invalidSetTextResult(
        seq: Int,
        reason: String,
        failureClass: String = "planning",
        classification: String? = null,
    ) = SetTextResult(
        seq = seq,
        textLength = 0,
        editorResult = "EditorFailure",
        planOps = 0,
        operationSeqs = emptyList(),
        failureClass = failureClass,
        transportStatus = "not_sent",
        abiVersion = null,
        targetId = null,
        targetVersion = null,
        targetSourceHash = null,
        currentText = null,
        desiredText = null,
        opaqueInputState = null,
        opaqueOutputState = null,
        symbolicActions = null,
        compiledOperations = null,
        layoutId = null,
        costMetricId = null,
        policyId = null,
        transactionOutcome = null,
        classification = classification,
        failure = reason,
    )

    // ── Diagnostics ──

    internal fun setTextDiagnosticFields(r: SetTextResult): Map<String, Any?> {
        val fields = mutableMapOf<String, Any?>(
            "seq" to r.seq,
            "text_length" to r.textLength,
            "result" to r.editorResult,
            "plan_ops" to r.planOps,
            "operation_seqs" to r.operationSeqs.joinToString(","),
        )

        if (r.failureClass != null) fields["failure_class"] = r.failureClass
        if (r.transportStatus != null) fields["transport_status"] = r.transportStatus

        if (r.abiVersion != null) fields["abi_version"] = r.abiVersion
        if (r.targetId != null) fields["package_id"] = r.targetId
        if (r.targetVersion != null) fields["package_version"] = r.targetVersion
        if (r.targetSourceHash != null) fields["package_source_hash"] = r.targetSourceHash
        if (r.currentText != null) fields["current_text"] = r.currentText
        if (r.desiredText != null) fields["desired_text"] = r.desiredText
        if (r.opaqueInputState != null) fields["opaque_input_state"] = r.opaqueInputState.toString()
        if (r.opaqueOutputState != null) fields["opaque_output_state"] = r.opaqueOutputState.toString()
        if (r.symbolicActions != null) fields["symbolic_actions"] = r.symbolicActions.toString()
        if (r.compiledOperations != null) fields["compiled_operations"] = r.compiledOperations.joinToString(",")
        if (r.layoutId != null) fields["layout_id"] = r.layoutId
        if (r.costMetricId != null) fields["cost_metric_id"] = r.costMetricId
        if (r.policyId != null) fields["policy_id"] = r.policyId
        if (r.transactionOutcome != null) fields["transaction_outcome"] = r.transactionOutcome
        if (r.classification != null) fields["classification"] = r.classification

        return fields
    }

    internal fun setTextDiagnosticJson(r: SetTextResult): String {
        return JSONObject().apply {
            put("seq", r.seq)
            put("ok", r.editorResult == "Synced")
            put("cmd", "set-text")
            put("failure", r.failure ?: JSONObject.NULL)
            put("failure_class", r.failureClass ?: JSONObject.NULL)
            put("decoded_len", r.textLength)
            put("plan_ops", JSONArray(r.planOpNames))
            put("transport_status", r.transportStatus ?: JSONObject.NULL)
            put("operation_seqs", JSONArray(r.operationSeqs))
            put("operation_statuses", JSONArray(r.operationStatuses))

            // Pure ABI fields
            put("package_id", r.targetId ?: JSONObject.NULL)
            put("package_version", r.targetVersion ?: JSONObject.NULL)
            put("package_source_hash", r.targetSourceHash ?: JSONObject.NULL)
            put("host_abi", r.abiVersion ?: JSONObject.NULL)
            put("current_text", r.currentText ?: JSONObject.NULL)
            put("desired_text", r.desiredText ?: JSONObject.NULL)
            put("opaque_input_state", abiValueToJson(r.opaqueInputState))
            put("opaque_output_state", abiValueToJson(r.opaqueOutputState))
            put("symbolic_actions", symbolicActionsToJson(r.symbolicActions))
            put("compiled_operations", JSONArray(r.compiledOperations ?: emptyList<String>()))
            put("layout_id", r.layoutId ?: JSONObject.NULL)
            put("cost_metric_id", r.costMetricId ?: JSONObject.NULL)
            put("policy_id", r.policyId ?: JSONObject.NULL)
            put("transaction_outcome", r.transactionOutcome ?: JSONObject.NULL)
            put("classification", r.classification ?: JSONObject.NULL)
        }.toString()
    }

    private fun emitSetTextDiagnostics(r: SetTextResult) {
        SwLog.event("editor", "set_text_result", r.seq, setTextDiagnosticFields(r))
    }

    // ── Helper Codecs ──


    internal fun jsonToAbiValue(json: Any?): AbiValue {
        if (json == null || json == JSONObject.NULL) return AbiValue.Null
        return when (json) {
            is Boolean -> AbiValue.Bool(json)
            is Int -> AbiValue.Int64(json.toLong())
            is Long -> AbiValue.Int64(json)
            is Double -> {
                val l = json.toLong()
                if (l.toDouble() == json) AbiValue.Int64(l) else AbiValue.Null
            }
            is String -> AbiValue.Str(json)
            is JSONObject -> {
                val fields = mutableMapOf<String, AbiValue>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    fields[key] = jsonToAbiValue(json.get(key))
                }
                AbiValue.Obj(fields)
            }
            is JSONArray -> {
                val list = mutableListOf<AbiValue>()
                for (i in 0 until json.length()) {
                    list.add(jsonToAbiValue(json.get(i)))
                }
                AbiValue.Array(list)
            }
            else -> AbiValue.Str(json.toString())
        }
    }

    internal fun abiValueToJson(value: AbiValue?): Any {
        if (value == null) return JSONObject.NULL
        return when (value) {
            is AbiValue.Null -> JSONObject.NULL
            is AbiValue.Bool -> value.value
            is AbiValue.Int64 -> value.value
            is AbiValue.Str -> value.value
            is AbiValue.Array -> JSONArray().apply {
                value.values.forEach { put(abiValueToJson(it)) }
            }
            is AbiValue.Obj -> JSONObject().apply {
                value.fields.forEach { (k, v) -> put(k, abiValueToJson(v)) }
            }
        }
    }

    private fun symbolicActionToJson(action: SymbolicAction): JSONObject {
        val obj = JSONObject()
        when (action) {
            is SymbolicAction.Tap -> {
                obj.put("kind", "tap")
                obj.put("usage", action.usage)
            }
            is SymbolicAction.Down -> {
                obj.put("kind", "down")
                obj.put("usage", action.usage)
            }
            is SymbolicAction.Up -> {
                obj.put("kind", "up")
                obj.put("usage", action.usage)
            }
            is SymbolicAction.Text -> {
                obj.put("kind", "text")
                obj.put("text", action.text)
            }
        }
        return obj
    }

    private fun symbolicActionsToJson(actions: List<SymbolicAction>?): Any {
        if (actions == null) return JSONObject.NULL
        return JSONArray().apply {
            actions.forEach { put(symbolicActionToJson(it)) }
        }
    }
}
