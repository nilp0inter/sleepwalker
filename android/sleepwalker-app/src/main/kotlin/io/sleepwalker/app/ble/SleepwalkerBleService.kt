package io.sleepwalker.app.ble

import android.app.Service
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
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.MouseOps
import io.sleepwalker.core.hid.SessionStatusParser
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.editor.Editor
import io.sleepwalker.core.editor.EditorState
import io.sleepwalker.core.editor.EditorResult
import io.sleepwalker.core.editor.EditorTrace
import io.sleepwalker.core.editor.ExecutionOutcome
import io.sleepwalker.core.editor.FailureClassification
import io.sleepwalker.core.editor.TargetPackageLoader
import io.sleepwalker.core.editor.VerificationEntry
import io.sleepwalker.core.editor.AbiValue
import io.sleepwalker.core.editor.SymbolicAction
import io.sleepwalker.core.editor.ExecutionPolicy
import io.sleepwalker.core.editor.VerificationSink
import io.sleepwalker.core.protocol.Status
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single BLE session owner.
 *
 * Implements scan, connect, write, MTU, and status parsing. Exposes static
 * surfaces so both AdbCommandReceiver and MainActivity share the same session
 * without duplication.
 */

/**
 * Structured Editor snapshot accessible outside the core module.
 *
 * Combines the public [Editor.state] with the last [VerificationEntry]
 * captured from [EditorTrace.sink]. Nullable fields are absent before
 * the first [Editor.setText] call completes.
 *
 * @property state              current public [EditorState].
 * @property targetId           target package id.
 * @property targetVersion      target package semantic version.
 * @property targetSourceHash   exact bundled package source identity.
 * @property hostAbi            host ABI version.
 * @property currentText        committed rendered text before planning.
 * @property desiredText        complete rendered-text goal.
 * @property opaqueInputState   package-owned committed input state.
 * @property opaqueOutputState  package-returned candidate state.
 * @property symbolicActions    validated inert package actions.
 * @property ops                compiled low-level operations.
 * @property layoutId           pinned keyboard-layout identity.
 * @property costMetricId       pinned text-cost metric identity.
 * @property policyId           active execution-policy identity.
 * @property outcome            transaction commit outcome.
 * @property lastPlanOps        compiled operation count.
 * @property lastClassification structured failure class, or null on success.
 */
data class EditorSnapshot(
    val state: EditorState,
    val targetId: String?,
    val targetVersion: String?,
    val targetSourceHash: String?,
    val hostAbi: Int?,
    val currentText: String?,
    val desiredText: String?,
    val opaqueInputState: AbiValue?,
    val opaqueOutputState: AbiValue?,
    val symbolicActions: List<SymbolicAction>?,
    val ops: List<io.sleepwalker.core.hid.LowLevelOp>?,
    val layoutId: String?,
    val costMetricId: String?,
    val policyId: String?,
    val outcome: String?,
    val lastPlanOps: Int,
    val lastClassification: String?,
)
sealed class UiEditorRequest {
    abstract val id: Long
    abstract val generation: Long

    data class Snapshot(
        override val id: Long,
        override val generation: Long,
        val text: String
    ) : UiEditorRequest()

    data class Reset(
        override val id: Long,
        override val generation: Long,
        val acknowledgedEmpty: Boolean
    ) : UiEditorRequest()
}

sealed class UiEditorResult {
    abstract val id: Long
    abstract val generation: Long

    data class Snapshot(
        override val id: Long,
        override val generation: Long,
        val text: String,
        val result: io.sleepwalker.core.editor.EditorResult,
        val snapshot: io.sleepwalker.app.ble.EditorSnapshot?
    ) : UiEditorResult()

    data class Reset(
        override val id: Long,
        override val generation: Long
    ) : UiEditorResult()
}

interface UiEditorListener {
    fun onUiEditorResult(request: UiEditorRequest, result: UiEditorResult)
}

class SleepwalkerBleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        var bleAdapter: BluetoothAdapter? = null
        var gatt: BluetoothGatt? = null
        var rxChar: BluetoothGattCharacteristic? = null
        var txChar: BluetoothGattCharacteristic? = null
        var negotiatedMtu: Int = 23
        val hid: LowLevelHid = LowLevelHidImpl()
        val mouse: MouseOps = MouseOps(hid)
        private val handler = Handler(Looper.getMainLooper())
        @Volatile
        var connecting = false
        enum class SafetyState { UNKNOWN, ARMED, DISARMED, KILLED }

        @Volatile
        var safetyState: SafetyState = SafetyState.UNKNOWN
            private set

        private val controlOperations = ConcurrentHashMap<Int, String>()


        interface StatusListener {
            fun onStatusReceived(seqId: Int, status: Int, statusName: String)
            fun onConnectionChanged(connected: Boolean)
        }
        @Volatile var statusListener: StatusListener? = null
        @Volatile var executorStatusListener: StatusListener? = null

        // ── Editor state ──

        /** Serialization lock for [editor] operations. */
        val editorLock = Any()

        // ── UI Editor FIFO Lane ──

        /** Single-thread FIFO lane for UI Editor commands. */
        val uiEditorCommandLane = UiEditorCommandLane(
            executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "SleepwalkerUiEditorFIFO")
            },
            mainHandler = handler,
            editorLock = editorLock,
            editorProvider = { editor },
            snapshotProvider = { editorSnapshot() },
            onReset = {
                lastVerification = null
                SwLog.event("editor", "reset_acknowledged_empty")
            },
        )

        var uiEditorListener: UiEditorListener?
            get() = uiEditorCommandLane.listener
            set(value) { uiEditorCommandLane.listener = value }

        fun nextUiChangeId(): Long = uiEditorCommandLane.nextUiChangeId()

        fun submitUiEditorRequest(request: UiEditorRequest) = uiEditorCommandLane.submit(request)


        @Volatile
        private var _editor: Editor? = null

        /** Get or initialize the [Editor] instance with default PRODUCTION policy. */
        val editor: Editor
            get() = synchronized(editorLock) {
                if (_editor == null) {
                    _editor = createEditorInstance(ExecutionPolicy.PRODUCTION)
                }
                _editor!!
            }

        /** Recreate the Editor with a specific [ExecutionPolicy] (thread-safe). */
        fun recreateEditor(policy: ExecutionPolicy): Editor = synchronized(editorLock) {
            _editor = createEditorInstance(policy)
            return _editor!!
        }

        private fun createEditorInstance(policy: ExecutionPolicy): Editor {
            val ctx = appContext
                ?: error("SleepwalkerBleService not initialized; call startScan first")
            val loader = TargetPackageLoader(ctx.assets)
            val exec = BleEditorExecutor.create()
            // Install the verification trace sink once.
            EditorTrace.sink = object : VerificationSink {
                override fun record(entry: VerificationEntry) {
                    lastVerification = entry
                    SwLog.event(
                        "editor",
                        "trace",
                        fields = mapOf(
                            "abiVersion" to entry.abiVersion,
                            "targetId" to entry.targetId,
                            "targetVersion" to entry.targetVersion,
                            "targetSourceHash" to entry.targetSourceHash,
                            "currentText" to entry.currentText,
                            "desiredText" to entry.desiredText,
                            "opaqueInputState" to entry.opaqueInputState.toString(),
                            "opaqueOutputState" to entry.opaqueOutputState?.toString(),
                            "symbolicActions" to entry.symbolicActions?.toString(),
                            "ops" to entry.ops.size,
                            "layoutId" to entry.layoutId,
                            "costMetricId" to entry.costMetricId,
                            "policyId" to entry.policyId,
                            "outcome" to entry.outcome,
                            "classification" to
                                (entry.classification?.javaClass?.simpleName ?: "null"),
                        ),
                    )
                }
            }
            return loader.createEditor("readline-emacs-ascii", exec, hid, policy)
        }

        /** Last [VerificationEntry] captured from [EditorTrace.sink]. */
        @Volatile
        var lastVerification: VerificationEntry? = null

        /**
         * Structured Editor snapshot for verification.
         * Returns null if the editor has not been initialized.
         */
        fun editorSnapshot(): EditorSnapshot? {
            val v = lastVerification ?: return null
            return EditorSnapshot(
                state = editor.state(),
                targetId = v.targetId,
                targetVersion = v.targetVersion,
                targetSourceHash = v.targetSourceHash,
                hostAbi = v.abiVersion,
                currentText = v.currentText,
                desiredText = v.desiredText,
                opaqueInputState = v.opaqueInputState,
                opaqueOutputState = v.opaqueOutputState,
                symbolicActions = v.symbolicActions,
                ops = v.ops,
                layoutId = v.layoutId,
                costMetricId = v.costMetricId,
                policyId = v.policyId,
                outcome = v.outcome,
                lastPlanOps = v.ops.size,
                lastClassification = v.classification?.javaClass?.simpleName,
            )
        }

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                SwLog.ble("scan_result", fields = mapOf("name" to (dev.name ?: "?"), "addr" to dev.address))
                if ((dev.name ?: "").contains("sleepwalker", ignoreCase = true)) {
                    if (!connecting && gatt == null) {
                        connecting = true
                        bleAdapter?.bluetoothLeScanner?.stopScan(this)
                        connectToDevice(dev)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                connecting = false
                SwLog.failure("scan_failed", fields = mapOf("error" to errorCode))
            }
        }

        fun startScan(context: Context) {
            appContext = context.applicationContext
            if (bleAdapter == null) {
                val mgr = appContext!!.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bleAdapter = mgr.adapter
            }
            val scanner = bleAdapter?.bluetoothLeScanner ?: run {
                SwLog.failure("no_scanner"); return
            }
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {}
            val filter = ScanFilter.Builder()
                .setDeviceName("sleepwalker")
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            SwLog.ble("scan_start")
        }

        fun disconnect() {
            gatt?.disconnect()
            SwLog.ble("disconnect_requested")
        }

        private fun connectToDevice(dev: BluetoothDevice) {
            val ctx = appContext ?: return
            SwLog.ble("connect", fields = mapOf("addr" to dev.address))
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(ctx, false, gattCallback)
            }
        }

        var appContext: Context? = null

        private val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                SwLog.ble("conn_state", fields = mapOf("status" to status, "newState" to newState))
                connecting = false
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SwLog.ble("disconnected")
                    g.close()
                    gatt = null
                    safetyState = SafetyState.UNKNOWN
                    controlOperations.clear()
                    handler.post { statusListener?.onConnectionChanged(false); executorStatusListener?.onConnectionChanged(false) }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(UUID.fromString(BleUuids.SERVICE))
                if (svc == null) {
                    SwLog.failure("service_not_found", fields = mapOf("status" to status))
                    return
                }
                rxChar = svc.getCharacteristic(UUID.fromString(BleUuids.RX_CHARACTERISTIC))
                txChar = svc.getCharacteristic(UUID.fromString(BleUuids.TX_CHARACTERISTIC))
                if (rxChar == null || txChar == null) {
                    SwLog.failure("characteristic_not_found")
                    return
                }
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar!!.getDescriptor(UUID.fromString(CCCD_UUID))
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
                g.requestMtu(247)
                SwLog.ble("services_discovered")
                handler.post { statusListener?.onConnectionChanged(true) }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                negotiatedMtu = mtu
                SwLog.ble("mtu", fields = mapOf("mtu" to mtu, "status" to status))
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                SwLog.frame("write", fields = mapOf("status" to status))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value ?: return
                val note = SessionStatusParser.parse(data)
                if (note == null) {
                    SwLog.ack("parse_failed")
                    return
                }
                SwLog.ack(note.statusName, note.seqId, mapOf(
                    "status" to note.status, "ctx_len" to note.context.size))
                val controlOperation = controlOperations[note.seqId]
                when (note.status) {
                    Status.QUEUED -> if (controlOperation == "arm") {
                        safetyState = SafetyState.ARMED
                        controlOperations.remove(note.seqId)
                    }
                    Status.SENT_TO_USB -> {
                        when (controlOperation) {
                            "disarm" -> safetyState = SafetyState.DISARMED
                            "kill" -> safetyState = SafetyState.KILLED
                        }
                        controlOperations.remove(note.seqId)
                    }
                    Status.DISARMED -> safetyState = SafetyState.DISARMED
                    Status.KILLED -> safetyState = SafetyState.KILLED
                }
                handler.post {
                    // Complete executor futures before UI listeners inspect Editor state.
                    executorStatusListener?.onStatusReceived(note.seqId, note.status, note.statusName)
                    statusListener?.onStatusReceived(note.seqId, note.status, note.statusName)
                }
            }
        }

        fun sendOp(op: io.sleepwalker.core.hid.LowLevelOp, seq: Int) {
            if (op.name == "arm" || op.name == "disarm" || op.name == "kill") {
                controlOperations[op.seqId] = op.name
            }
            val frame = op.toFrameBytes()
            val g = gatt ?: run {
                SwLog.failure("not_connected", seq); return
            }
            val rx = rxChar ?: run {
                SwLog.failure("no_rx_char", seq); return
            }
            SwLog.frame("encode", seq, mapOf(
                "len" to frame.size, "opcode" to op.name))
            val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
            synchronized(g) {
                for (chunk in chunks) {
                    rx.value = chunk
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    g.writeCharacteristic(rx)
                }
            }
        }
    }
}
