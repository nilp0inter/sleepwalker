package io.sleepwalker.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal activity. The companion app is agent-driven via ADB; the
 * activity exists only to satisfy the manifest and provide a UI affordance
 * for manual commissioning (BLE pairing prompts).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI in the first slice; commissioning prompts are system-driven.
    }
}