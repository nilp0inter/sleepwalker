---
name: sleepwalker-commands
description: Complete reference for all sleepwalker-* commands — no-hardware checks, firmware build/flash/reset, APK build/install, ADB adapters, observer commands, and smoke orchestrators. Use when the agent needs to invoke a command, look up arguments, understand exit codes, or drive the bench programmatically.
---

# The Interface: sleepwalker-* Commands

All commands are exposed via `nix flake` and available in the dev shell.
Every command emits JSON on stdout: `{"ok": true, ...}` on success,
`{"ok": false, "reason": "...", ...}` on stderr on failure. Exit codes:
0=success, 1=failure, 2=config/usage error, 3=human-gate timeout.

## No-Hardware Commands (safe to run anytime)

| Command | Args | What it does |
|---------|------|--------------|
| `sleepwalker-protocol-check` | none | Runs pytest against `protocol/` golden-frame fixtures + round-trip tests |
| `sleepwalker-bench-validate` | `<bench.toml>` | Validates bench TOML has all required fields |
| `sleepwalker-fw-build` | `[firmware_dir]` | Runs `idf.py build` using Nix ESP-IDF toolchain |
| `sleepwalker-apk-build` | `[android_dir]` | Runs `gradle :sleepwalker-app:assembleDebug` with Nix-pinned JDK17+Android SDK |

## Side-Effectful Hardware Commands

| Command | Args | What it does |
|---------|------|--------------|
| `sleepwalker-fw-flash` | `<port> [baud] [firmware_dir]` | Flash firmware via UART using `idf.py flash` |
| `sleepwalker-fw-flash-usb` | `<port> [baud] [firmware_dir] [bin_dir]` | Flash firmware via native USB using `esptool.py write_flash` |
| `sleepwalker-fw-uart` | `<port> <out.jsonl> [baud] [timeout_sec]` | Capture ESP32-S3 auxiliary UART JSONL logs (background-safe, DTR/RTS deasserted) |
| `sleepwalker-esp-reset` | `<port> [baud]` | Reset ESP32-S3 via UART RTS pulse (DTR=GPIO0 high=normal boot, RTS=EN pulse) |
| `sleepwalker-apk-install` | `<apk> [serial]` | `adb install -r` the APK |
| `sleepwalker-adb-logcat` | `<out.jsonl> [serial] [timeout_sec]` | Capture structured logcat (sleepwalker tag) to JSONL |

## ADB Command Adapters

All invoke `adb shell am broadcast -a io.sleepwalker.app.COMMAND -n io.sleepwalker.app/.adb.AdbCommandReceiver --es cmd <cmd>`. First arg is always `<serial>` (optional if only one device).

| Command | Extra Args | ADB `cmd` value |
|---------|-----------|----------------|
| `sleepwalker-adb-status` | — | `status` |
| `sleepwalker-adb-connect` | — | `connect` |
| `sleepwalker-adb-arm` | `<seq>` | `arm` |
| `sleepwalker-adb-inject-key` | `<key> <seq>` | `inject` (key = USB_KEY_SPACE, etc.) |
| `sleepwalker-adb-release-all` | `<seq>` | `release-all` |
| `sleepwalker-adb-kill` | `<seq>` | `kill` |
| `sleepwalker-adb-mouse-click` | `<seq>` | `mouse-click` |
| `sleepwalker-adb-mouse-move` | `<dx> <dy> <seq>` | `mouse-move` |
| `sleepwalker-adb-mouse-scroll` | `<amount> <seq>` | `mouse-scroll` |
| `sleepwalker-adb-mouse-release` | `<seq>` | `mouse-release` |
| `sleepwalker-adb-type-text` | `<text> <seq>` | `type-text` |

## Observer Commands

| Command | Args | What it does |
|---------|------|--------------|
| `sleepwalker-hid-observe` | `<ssh_target> <out.jsonl> [timeout_sec] [identity] [known_hosts] [device...] [--grab]` | SSH to observer host, run `sleepwalker-hid-observer` helper, collect JSONL evdev events. Defaults devices to `/dev/input/by-id/sleepwalker-hid-{keyboard,mouse}`. Preflight waits 10s for device paths. |
| `sleepwalker-human-gate` | `<message> <poll-command> [timeout_sec=300] [poll_interval=5]` | Ring `noti` with message, poll shell command until exit 0 or timeout |
| `sleepwalker-artifacts` | `<out_dir> <summary_json> [files...]` | Copy artifact files into structured directory |

## Smoke Orchestrators (End-to-End Scenarios)

All take `<bench.toml>` as sole arg. Each:
1. Validates bench config
2. Parses TOML → shell vars
3. Resets ESP32-S3 via `sleepwalker-esp-reset`
4. Starts background captures (fw-uart, adb-logcat, hid-observe)
5. Drives ADB commands (connect → wait for BLE subscribe → arm → inject → release → kill)
6. Polls HID observer JSONL for evidence
7. Writes `summary.json` to `$ARTIFACT_DIR/run_<scenario>_<timestamp>/`
8. Exits 0 on pass, 1 on fail, 2 on config error

| Command | Scenario | Evidence Checked | Timeout |
|---------|----------|-----------------|---------|
| `sleepwalker-smoke-keyboard` | `keyboard_smoke` | `KEY_SPACE` value 1 + value 0 | 30s |
| `sleepwalker-smoke-text` | `text_smoke` (direct + UI) | `KEY_A`, `KEY_LEFTSHIFT`, `KEY_1` sequence within 250ms (direct) or 5000ms (UI) | 15s each |
| `sleepwalker-smoke-mouse` | `mouse_smoke` | `BTN_LEFT` down/up + `REL_X`/`REL_Y` | 30s |
| `sleepwalker-smoke-composite` | `composite_smoke` | All keyboard + mouse evidence + cross-layer frame seq correlation | 60s |
