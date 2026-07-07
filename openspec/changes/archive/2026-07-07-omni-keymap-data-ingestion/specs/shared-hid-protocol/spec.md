## ADDED Requirements

### Requirement: Full modifier symbolic usages
The shared protocol registry SHALL define symbolic usages for all 8 standard USB HID modifier keys (Left Control, Left Shift, Left Alt, Left Meta, Right Control, Right Shift, Right Alt, Right Meta).

#### Scenario: Modifier usages available
- **WHEN** Python or Kotlin code requests symbolic usages for any modifier key (e.g., `USB_KEY_LEFTCTRL`, `USB_KEY_RIGHTALT`)
- **THEN** the registry resolves the symbol to the canonical USB HID usage value in the range `0xE0..0xE7`.
