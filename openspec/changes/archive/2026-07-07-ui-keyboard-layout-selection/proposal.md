## Why

Currently, the Android reference application's UI hardcodes text injection to the US QWERTY layout. There is no way for a user to select alternative layouts or host profiles when using the app manually, preventing the manual verification of international typing layouts.

## What Changes

- **Dynamic Layout Selectors in App UI**: Introduce dropdown selectors (spinners) in the `MainActivity` UI to allow selecting the target OS, Layout, and Variant.
- **Dynamic Profile Discovery**: Populate the selectors dynamically by querying `GeneratedKeymapDatabase.profiles` so the UI automatically mirrors whatever layouts are compiled into the core library.
- **Layout-Aware Text Streaming**: Update `MainActivity` to pass the user's selected `HostProfile` to the `TextPlanner` when planning and streaming text to the ESP32-S3 board.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `android-ble-companion`: Add UI requirement for dynamic keyboard layout selection when inputting text in the reference application.
