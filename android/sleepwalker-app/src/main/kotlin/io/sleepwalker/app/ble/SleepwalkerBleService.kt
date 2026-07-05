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
import android.os.IBinder
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.MouseOps
import io.sleepwalker.core.hid.SessionStatusParser
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.protocol.Usages
import java.util.UUID

/**
 * Service-owned BLE connection/session for the sleepwalker companion.
 *
 * This service is an alternative entry point for command-driven
 * operation. The ADB receiver is the canonical single BLE session owner
 * for the HIL bench; this service exists for foreground/demo use and
 * delegates command construction to the same `sleepwalker-core`
 * library surfaces ([LowLevelHid], [MouseOps]).
 *
 * Owns:
 *   - BLE scan / connect / bond state
 *   - GATT RX (write) and TX (notify) characteristics
 *   - MTU negotiation and MTU-aware frame writes
 *   - Status notification parsing via the library session boundary
 */
class SleepwalkerBleService : Service() {

    private val hid: LowLevelHid = LowLevelHidImpl()
    private val mouse: MouseOps = MouseOps(hid)
    private var bleAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var targetDevice: BluetoothDevice? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            SwLog.ble("scan_result", fields = mapOf("name" to (dev.name ?: "?")))
            if ((dev.name ?: "").contains("sleepwalker", ignoreCase = true)) {
                bleAdapter?.bluetoothLeScanner?.stopScan(this)
                targetDevice = dev
                connectToDevice(dev)
            }
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
            val note = SessionStatusParser.parse(data)
            if (note == null) {
                SwLog.ack("parse_failed")
                return
            }
            SwLog.ack(note.statusName, note.seqId, mapOf("status" to note.status))
        }
    }

    private fun connectToDevice(dev: BluetoothDevice) {
        SwLog.ble("connect", fields = mapOf("addr" to dev.address))
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(this, false, gattCallback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleAdapter = mgr.adapter
        SwLog.ble("service_create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMAND) {
            val cmd = intent.getStringExtra(EXTRA_CMD) ?: return START_NOT_STICKY
            val key = intent.getStringExtra(EXTRA_KEY)
            val seq = intent.getIntExtra(EXTRA_SEQ, 0)
            val dx = intent.getIntExtra(EXTRA_DX, 0)
            val dy = intent.getIntExtra(EXTRA_DY, 0)
            val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
            handleCommand(cmd, key, seq, dx, dy, amount)
        }
        return START_NOT_STICKY
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
            "mouse-click" -> {
                val ops = mouse.leftClick()
                SwLog.frame("mouse_click", seq, mapOf("ops" to ops.size))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-move" -> {
                val ops = mouse.move(dx, dy, seqOrNext(seq))
                SwLog.frame("mouse_move", seq, mapOf("ops" to ops.size, "dx" to dx, "dy" to dy))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-scroll" -> {
                val ops = mouse.scroll(amount, seqOrNext(seq))
                SwLog.frame("mouse_scroll", seq, mapOf("ops" to ops.size, "amount" to amount))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-pan" -> {
                val ops = mouse.pan(amount, seqOrNext(seq))
                SwLog.frame("mouse_pan", seq, mapOf("ops" to ops.size, "amount" to amount))
                ops.forEach { sendOp(it, it.seqId) }
            }
            "mouse-release" -> sendOp(mouse.releaseButtons(seqOrNext(seq)), seq)
            "disconnect" -> {
                gatt?.disconnect()
                SwLog.ble("disconnect_requested")
            }
            else -> SwLog.failure("unknown_cmd", seq, mapOf("cmd" to cmd))
        }
    }

    private fun seqOrNext(seq: Int): Int = if (seq > 0) seq else hid.nextSeqId()

    private fun sendOp(op: io.sleepwalker.core.hid.LowLevelOp, seq: Int) {
        val frame = op.toFrameBytes()
        val g = gatt ?: run {
            SwLog.failure("not_connected", seq); return
        }
        val rx = rxChar ?: run {
            SwLog.failure("no_rx_char", seq); return
        }
        SwLog.frame("encode", seq, mapOf("len" to frame.size, "opcode" to op.name))
        val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
        for (chunk in chunks) {
            rx.value = chunk
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(rx)
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

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_COMMAND = "io.sleepwalker.app.COMMAND"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_KEY = "key"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_DX = "dx"
        const val EXTRA_DY = "dy"
        const val EXTRA_AMOUNT = "amount"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
