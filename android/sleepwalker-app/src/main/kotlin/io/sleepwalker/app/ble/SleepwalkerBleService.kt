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
import java.util.UUID

/**
 * Single BLE session owner.
 *
 * Implements scan, connect, write, MTU, and status parsing. Exposes static
 * surfaces so both AdbCommandReceiver and MainActivity share the same session
 * without duplication.
 */
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
        var connecting = false

        interface StatusListener {
            fun onStatusReceived(seqId: Int, status: Int, statusName: String)
            fun onConnectionChanged(connected: Boolean)
        }
        @Volatile var statusListener: StatusListener? = null

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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SwLog.ble("disconnected")
                    g.close()
                    gatt = null
                    connecting = false
                    handler.post { statusListener?.onConnectionChanged(false) }
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
                handler.post {
                    statusListener?.onStatusReceived(note.seqId, note.status, note.statusName)
                }
            }
        }

        fun sendOp(op: io.sleepwalker.core.hid.LowLevelOp, seq: Int) {
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
