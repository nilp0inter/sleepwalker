package io.sleepwalker.app.diagnostics

import android.util.Log
import org.json.JSONObject

/**
 * Structured logcat diagnostics for the sleepwalker companion app.
 *
 * Emits one JSON object per logcat line with a stable component name,
 * event name, optional sequence id, and fields, so the HIL harness can
 * correlate one command through ADB, BLE, firmware queueing, USB HID
 * emission, and Linux evdev observation.
 *
 * Lines are tagged "sleepwalker" so `adb logcat -s sleepwalker` collects
 * only our structured events.
 */
object SwLog {
    private const val TAG = "sleepwalker"

    fun event(component: String, event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) {
        val ts = System.currentTimeMillis()
        val obj = JSONObject().apply {
            put("ts_ms", ts)
            put("component", component)
            put("event", event)
            put("seq", seqId)
            for ((k, v) in fields) {
                put(k, v)
            }
        }
        Log.i(TAG, obj.toString())
    }

    fun adb(event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) =
        event("adb", event, seqId, fields)

    fun ble(event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) =
        event("ble", event, seqId, fields)

    fun frame(event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) =
        event("frame", event, seqId, fields)

    fun ack(event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) =
        event("ack", event, seqId, fields)

    fun failure(event: String, seqId: Int = 0, fields: Map<String, Any?> = emptyMap()) =
        event("failure", event, seqId, fields)
}