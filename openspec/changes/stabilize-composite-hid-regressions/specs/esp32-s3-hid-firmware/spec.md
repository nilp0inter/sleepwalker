## MODIFIED Requirements

### Requirement: Future-compatible HID report identity
The firmware USB HID descriptor strategy SHALL identify keyboard and relative mouse reports in a way that permits a future absolute pointer report without redefining existing keyboard or relative mouse report semantics. When relative mouse support is active, keyboard and mouse report emission SHALL both remain observable from the same firmware build.

#### Scenario: Keyboard regression after mouse addition
- **WHEN** relative mouse support is present
- **THEN** the existing keyboard smoke scenario still observes `KEY_SPACE` down/up for `USB_KEY_SPACE`

#### Scenario: Composite HID output supports both roles
- **WHEN** firmware emits keyboard and relative mouse reports during one composite smoke run
- **THEN** the observer host can observe keyboard key events and relative mouse events without reflashing or changing firmware configuration
