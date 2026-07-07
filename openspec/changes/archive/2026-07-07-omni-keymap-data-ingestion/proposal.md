## Why

The current layout resolution is limited to a hardcoded US-QWERTY layout, preventing the library from typing international text or handling non-US keymaps correctly. Integrating a comprehensive keymap database (OmniKeymap) and adding modifier-state awareness to the text planner enables correct character-to-key sequence mapping for any operating system, layout, and variant, while significantly reducing BLE write overhead for sequential keystrokes.

## What Changes

- **Full International Layout Support**: Replace the hardcoded `SeedKeymapDatabase` with a generated registry that lazily loads layout-specific static Kotlin classes compiled from the OmniKeymap database.
- **Support for Multi-Modifier and Dead-Key Sequences**: Modernize the keymap mapping models to handle sequences of taps, supporting layouts requiring `AltGr`, `Option` combinations, and dead-key inputs.
- **State-Aware Text Planning**: Refactor `TextPlanner` to track active modifier keys, emitting `keyDown` and `keyUp` events only on state transitions, reducing the number of BLE/HID operations by over 50% for typical capitalized/symbolic typing.
- **ADB Command Layout Selection**: Update the ADB companion app to accept OS, layout, and variant intent parameters to allow dynamically selecting the active target keyboard layout.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sleepwalker-core`: Expand keymap database, entry models, and text planning logic to support multi-modifier/dead-key sequence mapping and modifier state awareness.
- `shared-hid-protocol`: Expose missing modifier key symbolic usages (Left Control, Left Alt, Left Meta, Right Control, Right Shift, Right Alt, Right Meta) to ensure the protocol supports all modifier key events.
- `android-ble-companion`: Update ADB interface commands to parse and pass target layout profiles (OS, layout, variant) for text input injection.
