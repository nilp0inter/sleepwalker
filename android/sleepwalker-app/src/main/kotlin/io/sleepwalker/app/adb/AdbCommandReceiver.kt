package io.sleepwalker.app.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.sleepwalker.app.diagnostics.SwLog
import io.sleepwalker.app.ble.SleepwalkerBleService

/**
 * ADB command intake surface.
 *
 * Agent sends explicit ADB broadcasts:
 *   adb shell am broadcast -a io.sleepwalker.app.COMMAND \
 *     -n io.sleepwalker.app/.adb.AdbCommandReceiver \
 *     --es cmd <command> [--es key <USB_KEY_SPACE>] [--ei seq <id>]
 *
 * Supported commands: connect, status, arm, inject, release-all, kill,
 * disconnect. The receiver parses quickly and delegates BLE work to the
 * service; it does NOT perform BLE operations in its own callback.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: run {
            SwLog.adb("missing_cmd")
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        val seq = intent.getIntExtra(EXTRA_SEQ, 0)
        SwLog.adb("intake", seq, mapOf("cmd" to cmd, "key" to key))

        // Delegate to the service-owned BLE session. The receiver returns
        // quickly; long-running BLE work happens in the service.
        val svc = Intent(context, SleepwalkerBleService::class.java).apply {
            action = SleepwalkerBleService.ACTION_COMMAND
            putExtra(SleepwalkerBleService.EXTRA_CMD, cmd)
            if (key != null) putExtra(SleepwalkerBleService.EXTRA_KEY, key)
            if (seq != 0) putExtra(SleepwalkerBleService.EXTRA_SEQ, seq)
        }
        // Use startForegroundService for connected-device work; the service
        // is responsible for promoting itself to the foreground as needed.
        context.startForegroundService(svc)
    }

    companion object {
        const val ACTION = "io.sleepwalker.app.COMMAND"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_KEY = "key"
        const val EXTRA_SEQ = "seq"
    }
}