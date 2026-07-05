## Why

The physical keyboard+mouse loop is now stable, but the FOSS-facing value of `sleepwalker` depends on the Kotlin library being able to turn user text into correct HID operations. This change makes a first useful high-level text path real and visible: a minimal reference app UI where valid characters typed into a text field are streamed through `sleepwalker-core` to the device.

## What Changes

- Expand the `sleepwalker-core` text planner from a seed skeleton into a modifier-aware planner for a complete seed US QWERTY printable subset.
- Expand symbolic keyboard usage coverage so letters, digits, selected controls, Shift, and punctuation needed by the seed text planner are represented consistently.
- Add inspectable text plans that show the ordered low-level keyboard operations used to render text.
- Add text execution through the same low-level keyboard API already used by the app and HIL.
- Add a minimal reference app UI with connect, arm, kill/status affordances and a text input that streams inserted valid characters to the device.
- Add autonomous HIL coverage for high-level text input by verifying the resulting HID event sequence on the observer host.
- Update ESP32-S3 firmware to track active modifier key states and include them in the modifier byte of standard USB keyboard reports.

## Non-goals

- No full global keymap corpus ingestion.
- No layout selector or persisted host profile settings.
- No polished UI, Compose migration, navigation, themes, or app architecture expansion beyond a minimal demo surface.
- No deletion/backspace mirroring from the Android text field to the target host.
- No dead keys, AltGr, compose sequences, IME handling, or Unicode fallback methods.
- No absolute mouse, virtual serial, or protocol frame changes.
- No public package publishing or Maven release automation.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sleepwalker-core`: Add modifier-aware text planning, complete seed US QWERTY printable mappings, expanded inspectable plan behavior, and atomic rendering failures.
- `shared-hid-protocol`: Expand the symbolic USB keyboard usage registry required by the seed text planner.
- `android-ble-companion`: Add a minimal reference app UI that demonstrates high-level text input through the public library path.
- `agent-operated-hil`: Add high-level text smoke coverage that verifies the expected HID event sequence.
- `hid-observer-nixos-iso`: Add symbolic decoding coverage for the keyboard keys required by the text smoke.
- `esp32-s3-hid-firmware`: Implement active modifier key tracking in USB reports.

## Impact

- Affects Kotlin library text/keymap/planning code under `android/sleepwalker-core`.
- Affects Android reference app UI under `android/sleepwalker-app`.
- Affects symbolic usage registries in Kotlin/Python/protocol support where required.
- Affects HIL text smoke orchestration and observer event assertions.
- Affects firmware keyboard report generation.
