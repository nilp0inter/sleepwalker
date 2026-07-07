## ADDED Requirements

### Requirement: JSON Resource Keymap Parser

The system MUST provide a `JsonKeymapDatabase` class that implements `KeymapDatabase` by parsing bundled JSON resources at runtime.

#### Scenario: Parse a single layout JSON file

- **WHEN** a JSON file from the OmniKeymap database is loaded
- **THEN** the parser MUST extract `metadata.platform` as the OS, `metadata.layout_name` as the layout, and the variant from the filename (`<layout>+<variant>.json`)
- **AND** each character mapping MUST be converted to `KeymapEntry` with `KeymapTap` objects containing USB HID usage IDs and modifier bitmasks

#### Scenario: Map X11 key names to USB HID usages

- **WHEN** the parser encounters a key name like "KeyW" or "BracketLeft" in the JSON
- **THEN** it MUST map it to the corresponding USB HID usage ID using an X11-to-USB translation table
- **AND** modifier names like "Shift", "Control" MUST be mapped to their bit flag values

#### Scenario: Build a lookup map from all bundled JSON files

- **WHEN** `JsonKeymapDatabase` is initialized
- **THEN** it MUST scan the `res/raw/keymaps/` resource directory for all `*.json` files
- **AND** build a `Map<HostProfile, List<KeymapEntry>>` from all valid layouts
- **AND** skip entries with multi-character strings that cannot fit in a `Char`