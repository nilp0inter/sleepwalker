## 1. Protocol Definitions

- [x] 1.1 Add `KEYBOARD_TAP_SCRIPT` (0x0014) to Kotlin and Python opcode definition files (`Opcodes.kt` and `opcodes.py`)
- [x] 1.2 Add `SW_OPCODE_KEYBOARD_TAP_SCRIPT` (0x0014) to `sleepwalker_protocol.h`
- [x] 1.3 Update opcode namespace helper functions in `sleepwalker_protocol.c` (`sw_proto_is_known_opcode` and `sw_proto_namespace_for`)

## 2. Firmware Execution

- [x] 2.1 Update BLE RX handler in `ble_uart.c` to validate and enqueue `KEYBOARD_TAP_SCRIPT` frames
- [x] 2.2 Implement script execution loop in `sw_hid_worker_task` (`main.c`) that emits standard keyboard HID report sequences using local FreeRTOS task delays
- [x] 2.3 Add abort checks to the script loop to release all keys immediately on disarm, kill, timeout, or BLE disconnect

## 3. Core Library Support

- [x] 3.1 Add `KEYBOARD_TAP_SCRIPT` command frame encoding helper to `Frame.kt`
- [x] 3.2 Implement a tap script compiler and chunker in `sleepwalker-core` that lowers planned keyboard operations into chunked byte payloads
- [x] 3.3 Add unit tests for tap script serialization, layout mapping, and chunking in `sleepwalker-core`

## 4. App Integration

- [x] 4.1 Update `MainActivity` to use the batched tap script path for UI text streaming
- [x] 4.2 Update `AdbCommandReceiver` to use the batched tap script path for the `type-text` command

## 5. HIL Verification & Regression

- [x] 5.1 Update the HIL text smoke scenario to verify event sequence and timing using the batched script path
- [x] 5.2 Run composite and text smoke tests to verify performance improvements and ensure zero regression on existing mouse/keyboard APIs

## Verification Evidence

- Firmware flashed to ESP32-S3 on `/dev/ttyUSB0` with poll-safe tap timing (`15 ms` press, `15 ms` release gap).
- Debug APK rebuilt and installed on Pixel 6a serial `29211JEGR12028`.
- Runtime BLE permissions restored after app data reset.
- Real HIL text smoke passed: `artifacts/run_text_1783262308/summary.json`.
  - `direct_text`: `true`; observer saw `aA1` sequence in `80 ms`.
  - `ui_text`: `true`; observer saw `aA1` sequence in `1048 ms` through `MainActivity`.
- Composite smoke HID behavior passed in `artifacts/run_composite_1783262419/summary.json` (`keyboard.pass=true`, `mouse.pass=true`, `observer_ok=true`), but overall composite status remained failed because ESP UART capture died and correlation could not be computed (`esp_seqs=[]`).
