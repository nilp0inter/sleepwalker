---
name: sleepwalker-verification
description: Post-change verification loop, artifact structure, failure diagnosis, and key invariants for the Sleepwalker project. Use when the agent has made a change and needs to verify it works, inspect smoke test artifacts, diagnose failures from JSONL logs, or understand the invariants that must hold (protocol parity, safety state machine, sequence IDs, exclusive grab).
---

# The Verification Loop

After making a change:

1. **No-hardware checks first:**
   - `sleepwalker-protocol-check` — verifies protocol parity (Python ↔ Kotlin ↔ C)
   - `sleepwalker-fw-build` — verifies firmware compiles
   - `sleepwalker-apk-build` — verifies APK compiles

2. **If hardware is available and commissioned:**
   - `sleepwalker-smoke-composite sleepwalker-hil/bench.toml` — runs the full E2E scenario
   - Inspect `artifacts/run_composite_<timestamp>/summary.json` for pass/fail evidence
   - If failed, inspect the JSONL logs in the same directory:
     - `android_logcat.jsonl` — BLE session events, status notifications
     - `esp_uart.jsonl` — firmware diagnostics, command decoding
     - `hid_observer.jsonl` — evdev events on the target host
     - `summary.json` — cross-layer correlation and evidence

3. **Diagnosing failures:**
   - If `summary.json` shows missing keyboard evidence → check `esp_uart.jsonl` for `DISARMED` or `MALFORMED` status
   - If BLE never reaches `subscribe` → check `android_logcat.jsonl` for connection errors
   - If observer sees no events → check `hid_observer.jsonl` for device match failures
   - If cross-layer correlation fails → sequence IDs (`seq`) don't match; check both logcat and UART for the same seq

# When to Ask for Help

You are autonomous on a commissioned bench. Ask the human when:

1. **Bench is not commissioned** — `sleepwalker-bench-validate` fails, or hardware is unreachable
2. **Physical intervention needed** — ESP32-S3 needs manual reset (BOOT button), cables need replugging, observer host needs ISO reflash
3. **Human gate is invoked** — a smoke operation calls `sleepwalker-human-gate`, which rings `noti` and waits for the human to resolve a condition
4. **Ambiguous failure** — all logs are clean but the test still fails; the human may need to inspect physical connections or observer state
5. **New hardware** — a new device needs to be added to `bench.toml` (new USB VID/PID, new ADB serial, new SSH target)

**Do NOT ask for help when:**
- A build fails — read the compiler output and fix it
- A smoke test fails — inspect the artifacts and diagnose the layer
- A command returns `{"ok": false}` — read the `reason` field and act on it

# Artifact Structure

Every smoke operation writes to `artifacts/run_<scenario>_<timestamp>/`:

```
artifacts/run_composite_1783262444/
├── bench.toml              # Copy of the bench config used
├── android_logcat.jsonl    # Structured logcat from the Android app
├── esp_uart.jsonl          # Auxiliary UART logs from the ESP32-S3
├── hid_observer.jsonl      # evdev events from the observer host
└── summary.json            # Machine-readable pass/fail with evidence
```

**summary.json schema:**

```json
{
  "scenario": "composite_smoke",
  "status": "pass",
  "keyboard": {
    "expected": ["KEY_SPACE:1", "KEY_SPACE:0"],
    "observed": ["KEY_SPACE:1", "KEY_SPACE:0"],
    "match": true
  },
  "mouse": {
    "expected": ["BTN_LEFT:1", "BTN_LEFT:0", "REL_X"],
    "observed": ["BTN_LEFT:1", "BTN_LEFT:0", "REL_X:10"],
    "match": true
  },
  "observer": {
    "devices": ["/dev/input/event3", "/dev/input/event4"],
    "grab": true
  },
  "correlation": {
    "android_seq": [1, 2, 3],
    "esp_seq": [1, 2, 3],
    "match": true
  }
}
```

# Key Invariants

1. **Protocol parity** — Python, Kotlin, and C must agree on frame layout, opcodes, and status codes. `sleepwalker-protocol-check` verifies this.
2. **Safety state machine** — Firmware starts DISARMED. Must receive `arm` before emitting HID. `kill` is permanent until reset. `release-all` on BLE disconnect.
3. **Sequence IDs** — Every command carries a `seq` (u16). The app, firmware, and observer all log it. Cross-layer correlation in `summary.json` depends on it.
4. **Exclusive grab** — The observer helper grabs evdev devices exclusively during smoke tests to prevent injected events from reaching other userspace consumers.
5. **No human gates in commissioned bench** — All smoke operations complete autonomously. Human gates are only for commissioning and recovery.
