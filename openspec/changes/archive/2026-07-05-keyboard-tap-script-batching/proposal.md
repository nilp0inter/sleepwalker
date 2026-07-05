## Why

The current text rendering mechanism sends keys one at a time and uses a fixed 100 ms delay in the Android companion app, resulting in extremely poor typing speed (under 10 characters per second, and under 3 characters per second for uppercase text). This change enables batching keyboard inputs into compact keystroke tap scripts executed firmware-locally with deterministic timing, significantly increasing throughput while keeping the firmware layout-unaware.

## What Changes

- Introduce a new protocol opcode `KEYBOARD_TAP_SCRIPT` (0x0014) in the keyboard namespace.
- Modify `sleepwalker-core` `TextPlanner` and `LowLevelHid` to support planning and lowering text to a list of modifier-usage tap script records.
- Modify `sleepwalker-app` (`AdbCommandReceiver` and `MainActivity`) to stream text using batched tap script frames instead of sending individual operations with sleep delays.
- Modify ESP32-S3 firmware to decode the batched script frame and execute the sequence locally using FreeRTOS task delays.
- Update HIL tests to verify tap script batch execution.
- Deploying the change requires both a firmware flash and Android APK reinstall; a new APK alone will emit opcode `0x0014` that old firmware rejects as unsupported.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `shared-hid-protocol`: Add symbolic definition and layout requirements for the `KEYBOARD_TAP_SCRIPT` opcode.
- `sleepwalker-core`: Require support for lowering text plans to compact batch representations.
- `esp32-s3-hid-firmware`: Implement the script execution loop in the HID worker task, keeping the firmware layout-unaware.
- `android-ble-companion`: Update UI and ADB command receiver to utilize batched transmission of tap scripts.
- `agent-operated-hil`: Verify batched key rendering correctness under HIL.

## Impact

- `protocol/`: usages, opcodes, frame serialization.
- `android/sleepwalker-core/`: Text planner output lowering, frame generation.
- `android/sleepwalker-app/`: MainActivity, AdbCommandReceiver.
- `firmware/`: main (worker task), ble_uart, sleepwalker_protocol.
- `nix/`, `tests/`: text smoke test.

## Verification

- Protocol conformance: `sleepwalker-protocol-check` passed (`63 passed`).
- Firmware build: `nix run .#sleepwalker-fw-build` returned `{"ok":true}`.
- Android unit/build verification: Gradle `test` and `assembleDebug` completed successfully.
- Real device cutover: firmware flashed to ESP32-S3 on `/dev/ttyUSB0`; debug APK installed on Pixel 6a `29211JEGR12028`.
- Real HIL text smoke: `artifacts/run_text_1783262308/summary.json` reported `{"ok": true, "status": "pass"}`.
- Composite HID regression: keyboard/mouse observer behavior passed in `artifacts/run_composite_1783262419`, but overall composite status remained failed due to ESP UART capture correlation loss, not HID behavior.
