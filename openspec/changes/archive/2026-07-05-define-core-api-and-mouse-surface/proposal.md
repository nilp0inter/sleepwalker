## Why

`sleepwalker` is becoming a FOSS-facing Kotlin library plus firmware device, not just an agent-operated keyboard smoke harness. The next change should make the Kotlin library the product boundary, add the smallest missing low-level HID surface (relative mouse), and reserve protocol space for absolute pointer and virtual serial without making those future features part of this implementation slice.

## What Changes

- Add a new `sleepwalker-core` capability describing the reusable Kotlin library contract.
- Define the library as a two-level API:
  - low-level keyboard/mouse primitives over the device protocol;
  - high-level text planning over a bundled keymap database abstraction.
- Add raw relative mouse support through the protocol, Kotlin library, firmware, reference app command path, and autonomous HIL smoke evidence.
- Clarify opcode namespaces for safety/control, keyboard HID, relative mouse HID, future absolute pointer HID, future virtual serial/CDC, and future device capability/configuration commands.
- Preserve the v1 frame layout and status notification context as the extensibility boundary.
- Clarify that the Android reference app demonstrates library behavior and does not own protocol encoding, keymap rendering, or duplicated BLE/session logic.
- Add autonomous verification for relative mouse events and public-library-driven command paths.

## Non-goals

- No absolute mouse/pointer implementation in this change; only opcode/report-ID/namespace reservation.
- No virtual serial/CDC implementation in this change; only opcode namespace reservation.
- No complete global keymap corpus ingestion in this change. The change defines the bundled keymap database contract and may include a small seed/conformance dataset for tests.
- No cryptographic command signing or authentication beyond the existing BLE bonding plus firmware safety state boundary.
- No host-side software agent, clipboard bridge, Android emulator BLE path, broad macro language, or public Maven release automation.

## Capabilities

### New Capabilities

- `sleepwalker-core`: Public Kotlin library contract, including low-level HID primitives, high-level text planning, bundled keymap database boundary, structured rendering failures, inspectable execution plans, and public session behavior.

### Modified Capabilities

- `shared-hid-protocol`: Add device-class opcode namespaces, raw relative mouse report payload, future absolute pointer and virtual serial reservations, and protocol extensibility requirements.
- `esp32-s3-hid-firmware`: Add raw relative mouse HID emission, mouse safety release behavior, future-compatible HID report identity, and firmware layout-unaware boundaries.
- `android-ble-companion`: Clarify the reference app boundary and require ADB/demo paths to delegate through reusable library/session behavior rather than duplicated protocol/BLE logic.
- `agent-operated-hil`: Add autonomous mouse smoke evidence and library-driven command smoke coverage.

## Impact

- Affects Kotlin public APIs under `android/sleepwalker-core` and app usage under `android/sleepwalker-app`.
- Affects shared protocol constants/fixtures in Python, Kotlin, and firmware C headers.
- Affects firmware HID descriptors, HID report emission, safety release-all behavior, and UART diagnostics.
- Affects HIL tooling and smoke artifacts under `sleepwalker-hil/` and `artifacts/`.
- Does not require new manual commissioning beyond the existing Android/BLE/observer bench setup.
