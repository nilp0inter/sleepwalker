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
import android.os.Handler
import android.os.Looper
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.core.hid.LowLevelHid
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
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        // Static BLE state — survives across receiver invocations.
        // This is the single BLE session owner; no other component
        // performs scan/connect/write.
        private var bleAdapter: BluetoothAdapter? = null
        private var gatt: BluetoothGatt? = null
        private var rxChar: BluetoothGattCharacteristic? = null
        private var txChar: BluetoothGattCharacteristic? = null
        private var negotiatedMtu: Int = 23
        private val hid: LowLevelHid = LowLevelHidImpl()
        private val mouse: MouseOps = MouseOps(hid)
        private val handler = Handler(Looper.getMainLooper())
        private var connecting = false
    }

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
        SwLog.adb("intake", seq, mapOf("cmd" to cmd, "key" to key,
            "dx" to dx, "dy" to dy, "amount" to amount))

        // Initialize BLE adapter on first use.
        if (bleAdapter == null) {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bleAdapter = mgr.adapter
        }

        // Use goAsync for up to ~10s of background work.
        val pending = goAsync()
        Thread {
            try {
                handleCommand(cmd, key, seq, dx, dy, amount)
            } catch (e: Exception) {
                SwLog.failure("exception", seq, mapOf("error" to (e.message ?: "unknown")))
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleCommand(cmd: String, key: String?, seq: Int,
                              dx: Int, dy: Int, amount: Int) {
        SwLog.ble("command", seq, mapOf("cmd" to cmd, "key" to key))
        when (cmd) {
            "connect" -> startScan()
            "status" -> SwLog.ble("status", fields = mapOf(
                "connected" to (gatt != null), "mtu" to negotiatedMtu))
            "arm" -> sendOp(hid.arm(seqOrNext(seq)), seq)
            "disarm" -> sendOp(hid.disarm(seqOrNext(seq)), seq)
            "kill" -> sendOp(hid.kill(seqOrNext(seq)), seq)
            "release-all" -> sendOp(hid.releaseAll(seqOrNext(seq)), seq)
            "inject" -> {
                val usage = if (key != null) {
                    try { Usages.byName(key) } catch (_: Exception) {
                        SwLog.failure("unknown_key", seq, mapOf("key" to key)); return
                    }
                } else Usages.USB_KEY_SPACE
                sendOp(hid.keyTap(usage, seqOrNext(seq)), seq)
            }
            // Mouse commands — routed through the public library mouse API.
            "mouse-click" -> {
                val ops = mouse.leftClick()
                SwLog.frame("mouse_click", seq, mapOf(
                    "ops" to ops.size, "opcode" to ops[0].name))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-move" -> {
                val ops = mouse.move(dx, dy, seqOrNext(seq))
                SwLog.frame("mouse_move", seq, mapOf(
                    "ops" to ops.size, "dx" to dx, "dy" to dy))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-scroll" -> {
                val ops = mouse.scroll(amount, seqOrNext(seq))
                SwLog.frame("mouse_scroll", seq, mapOf(
                    "ops" to ops.size, "amount" to amount))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-pan" -> {
                val ops = mouse.pan(amount, seqOrNext(seq))
                SwLog.frame("mouse_pan", seq, mapOf(
                    "ops" to ops.size, "amount" to amount))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-release" -> {
                sendOp(mouse.releaseButtons(seqOrNext(seq)), seq)
            }
            "disconnect" -> {
                gatt?.disconnect()
                SwLog.ble("disconnect_requested")
            }
            else -> SwLog.failure("unknown_cmd", seq, mapOf("cmd" to cmd))
        }
    }

    private fun seqOrNext(seq: Int): Int = if (seq > 0) seq else hid.nextSeqId()

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
            SwLog.failure("scan_failed", fields = mapOf("error" to errorCode))
        }
    }

    private fun startScan() {
        val scanner = bleAdapter?.bluetoothLeScanner ?: run {
            SwLog.failure("no_scanner"); return
        }
        val filter = ScanFilter.Builder()
            .setDeviceName("sleepwalker")
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        SwLog.ble("scan_start")
    }

    private fun connectToDevice(dev: BluetoothDevice) {
        SwLog.ble("connect", fields = mapOf("addr" to dev.address))
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(null, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            SwLog.ble("conn_state", fields = mapOf("status" to status, "newState" to newState))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                SwLog.ble("disconnected")
                g.close()
                gatt = null
                connecting = false
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
            // Parse through the public library session boundary so
            // callers correlate sequence ids with firmware acks.
            val note = SessionStatusParser.parse(data)
            if (note == null) {
                SwLog.ack("parse_failed")
                return
            }
            SwLog.ack(note.statusName, note.seqId, mapOf(
                "status" to note.status, "ctx_len" to note.context.size))
        }
    }

    private fun sendOp(op: io.sleepwalker.core.hid.LowLevelOp, seq: Int) {
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
        for (chunk in chunks) {
            rx.value = chunk
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(rx)
        }
    }
}
