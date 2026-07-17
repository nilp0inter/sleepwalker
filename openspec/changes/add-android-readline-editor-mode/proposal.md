## Why

The Android reference app can only stream newly inserted characters, so its text area cannot demonstrate the existing stateful `Editor` API or keep a GNU Readline target synchronized after deletions and replacements. A dedicated Readline editor mode is needed to exercise complete-snapshot reconciliation through the same user-facing demo used for the other public library capabilities.

## What Changes

- Add a selectable Readline editor mode to the Android demo app while preserving the existing append-only text-stream mode.
- In Readline editor mode, submit the text area's complete current value to the existing `Editor.setText` API after every user-visible text change, including insertion, deletion, replacement, paste, and clearing.
- Restrict the mode to the bundled `readline-emacs-ascii` target and expose its single-line printable-ASCII constraints in the UI.
- Serialize UI reconciliation with existing Editor operations and execute generated operations through the shared BLE service/session path.
- Report synchronized, validation-failure, transport/safety-failure, and terminal unknown-state outcomes through the existing status/error surface; require explicit reset before further edits after unknown target state.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `android-ble-companion`: Extend the reference app's text demo with a selectable, complete-snapshot Readline editor mode that uses the public `sleepwalker-core` Editor API while retaining append-only streaming behavior.

## Impact

- Android demo UI and UI-state handling in `sleepwalker-app`, primarily `MainActivity`.
- Existing `SleepwalkerBleService` Editor singleton, serialization lock, reset behavior, BLE-backed executor, and status feedback are reused rather than duplicated.
- `sleepwalker-core`, target packages, firmware, shared protocol, and BLE framing remain unchanged.
- Android app tests and physical composite HIL coverage must demonstrate insertion, deletion, replacement, clearing, validation failure, and recovery/reset behavior through the UI mode.
