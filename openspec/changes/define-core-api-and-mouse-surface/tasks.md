## 1. Protocol Constants and Fixtures

- [x] 1.1 Add device-class opcode namespace constants for safety, keyboard, relative mouse, future absolute pointer, future serial, and future capabilities/configuration in Python, Kotlin, and firmware C headers
- [x] 1.2 Add `MOUSE_REL_REPORT` opcode and five-byte payload definition (`buttons`, `dx`, `dy`, `wheel`, `pan`) in Python protocol helpers
- [x] 1.3 Mirror `MOUSE_REL_REPORT` constants and payload helpers in Kotlin protocol code
- [x] 1.4 Mirror `MOUSE_REL_REPORT` constants and payload validation in firmware protocol headers/code
- [x] 1.5 Add golden fixtures and parity tests for relative mouse frame encoding/decoding and CRC agreement
- [x] 1.6 Add tests that reserved absolute pointer and serial opcodes decode as valid frames but are rejected as unsupported by current dispatch

## 2. Kotlin Core Library Surface

- [x] 2.1 Introduce public low-level API boundaries for keyboard, mouse, text planning, host profiles, and session/status behavior under `sleepwalker-core`
- [x] 2.2 Implement low-level relative mouse report construction with button mask, movement chunking, wheel, and pan support
- [x] 2.3 Implement low-level mouse convenience operations for button down/up, click, relative move, vertical scroll, horizontal pan, and release buttons using raw reports
- [x] 2.4 Add host profile and bundled keymap database abstractions with a seed/conformance layout dataset
- [x] 2.5 Implement high-level text planning from selected host profile to inspectable low-level keyboard operations for the seed dataset
- [x] 2.6 Implement structured failures for missing layout and unrepresentable glyph cases
- [x] 2.7 Expose parsed status notifications and sequence identifiers through the public library/session boundary
- [x] 2.8 Add Kotlin unit tests for mouse chunking, keymap lookup, text planning, rendering failures, status parsing, and public API boundaries

## 3. Firmware Relative Mouse Support

- [x] 3.1 Update TinyUSB HID descriptor/report strategy to support keyboard and relative mouse with future-compatible report identity
- [x] 3.2 Add firmware dispatch for valid armed `MOUSE_REL_REPORT` commands
- [x] 3.3 Emit TinyUSB relative mouse reports matching the raw report payload
- [x] 3.4 Reject malformed mouse payloads before HID dispatch with structured diagnostics/status
- [x] 3.5 Extend release-all, disarm, kill, timeout, and BLE disconnect paths to release mouse buttons
- [x] 3.6 Add structured UART diagnostics for mouse receipt, queueing, USB emission, and rejection
- [x] 3.7 Keep existing keyboard `USB_KEY_SPACE` behavior passing after descriptor/report changes

## 4. Reference App and ADB Command Path

- [x] 4.1 Refactor ADB command intake so BLE scan/connect/write/status behavior is owned by one service/session path
- [x] 4.2 Route existing keyboard commands through public `sleepwalker-core` library behavior rather than app-local protocol construction
- [x] 4.3 Add explicit ADB/app command handling for relative mouse click, move, scroll, and release using the library mouse API
- [x] 4.4 Emit structured Android diagnostics for mouse commands with sequence identifiers and command fields
- [x] 4.5 Preserve existing connect, arm, disarm, kill, release-all, inject, disconnect, and status command behavior

## 5. HIL Operations and Verification

- [x] 5.1 Extend HID observer matching/artifact parsing as needed for composite keyboard/mouse evdev devices
- [x] 5.2 Add an autonomous mouse smoke operation that injects left button down/up and relative movement through the app/library/BLE/firmware path
- [x] 5.3 Write mouse smoke summary fields for `BTN_LEFT` down/up, `REL_X` or `REL_Y`, Android diagnostics, ESP UART diagnostics, and sequence correlation
- [x] 5.4 Add library-driven command smoke coverage for at least one public keyboard or mouse command path
- [x] 5.5 Verify reserved future opcodes produce structured unsupported-opcode evidence and no unintended USB output
- [x] 5.6 Run targeted protocol, Kotlin, firmware, and HIL tests that cover this change
- [x] 5.7 Run keyboard smoke regression and mouse smoke on the physical bench, preserving structured artifacts
