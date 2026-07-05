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
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        // All BLE session state is delegated to SleepwalkerBleService.

        /**
         * Decode a base64url-encoded UTF-8 string.
         * @return Decoded string or null if decoding fails.
         */
        private fun decodeBase64Url(encoded: String?): String? {
            if (encoded == null) return null
            return try {
                val bytes = Base64.decode(encoded, Base64.URL_SAFE)
                String(bytes, Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                null
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
        SwLog.adb("intake", seq, mapOf("cmd" to cmd, "key" to key,
            "dx" to dx, "dy" to dy, "amount" to amount, "text" to text, "text_encoded" to textEncoded))

        currentContext = context

        // Use goAsync for up to ~10s of background work.
        val pending = goAsync()
        Thread {
            try {
                handleCommand(cmd, key, text, textEncoded, seq, dx, dy, amount)
            } catch (e: Exception) {
                SwLog.failure("exception", seq, mapOf("error" to (e.message ?: "unknown")))
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleCommand(cmd: String, key: String?, text: String?, textEncoded: String?, seq: Int,
                              dx: Int, dy: Int, amount: Int) {
        SwLog.ble("command", seq, mapOf("cmd" to cmd, "key" to key, "text" to text, "text_encoded" to textEncoded))
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

                val planner = TextPlanner(hid = SleepwalkerBleService.hid)
                val result = planner.plan(decodedText, HostProfile.LINUX_US)
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

}
