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
import io.sleepwalker.core.SleepwalkerCommands
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.ble.StatusNotification
import io.sleepwalker.core.protocol.Opcodes
import io.sleepwalker.core.protocol.Status
import io.sleepwalker.core.protocol.Usages
import java.util.UUID

/**
 * Service-owned BLE connection/session for the sleepwalker companion.
 *
 * Owns:
 *   - BLE scan / connect / bond state
 *   - GATT RX (write) and TX (notify) characteristics
 *   - MTU negotiation and MTU-aware frame writes
 *   - Status notification parsing and structured logcat diagnostics
 *
 * Commands arrive from AdbCommandReceiver via startForegroundService.
 * The service is the only component that performs BLE I/O.
 */
class SleepwalkerBleService : Service() {

    private val commands = SleepwalkerCommands()
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
            // Connect to the first matching device; in a real bench the
            // device address would come from bench config. For the smoke
            // slice we connect to any device named "sleepwalker".
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
            // Enable notifications on TX.
            g.setCharacteristicNotification(txChar, true)
            val cccd = txChar!!.getDescriptor(UUID.fromString(CCCD_UUID))
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            // Request a larger MTU for frame writes.
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
            val note = StatusNotification.parse(data)
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
            handleCommand(cmd, key, seq)
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(cmd: String, key: String?, seq: Int) {
        SwLog.ble("command", seq, mapOf("cmd" to cmd, "key" to key))
        when (cmd) {
            "connect" -> startScan()
            "status" -> SwLog.ble("status", fields = mapOf(
                "connected" to (gatt != null), "mtu" to negotiatedMtu))
            "arm" -> sendFrame(commands.arm(seqId = seqOrNext(seq)), seq)
            "disarm" -> sendFrame(commands.disarm(seqId = seqOrNext(seq)), seq)
            "kill" -> sendFrame(commands.kill(seqId = seqOrNext(seq)), seq)
            "release-all" -> sendFrame(commands.releaseAll(seqId = seqOrNext(seq)), seq)
            "inject" -> {
                val usage = if (key != null) {
                    try { Usages.byName(key) } catch (_: Exception) {
                        SwLog.failure("unknown_key", seq, mapOf("key" to key)); return
                    }
                } else Usages.USB_KEY_SPACE
                sendFrame(commands.keyTap(usage, seqId = seqOrNext(seq)), seq)
            }
            "disconnect" -> {
                gatt?.disconnect()
                SwLog.ble("disconnect_requested")
            }
            else -> SwLog.failure("unknown_cmd", seq, mapOf("cmd" to cmd))
        }
    }

    private fun seqOrNext(seq: Int): Int = if (seq > 0) seq else commands.nextSeqId()

    private fun sendFrame(frame: ByteArray, seq: Int) {
        val g = gatt ?: run {
            SwLog.failure("not_connected", seq); return
        }
        val rx = rxChar ?: run {
            SwLog.failure("no_rx_char", seq); return
        }
        SwLog.frame("encode", seq, mapOf("len" to frame.size))
        // MTU-aware chunking.
        val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
        for (chunk in chunks) {
            rx.value = chunk
            // Use write-without-response for lower latency on small frames.
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
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}