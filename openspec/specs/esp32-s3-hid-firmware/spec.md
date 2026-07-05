# esp32-s3-hid-firmware

### Requirement: Raw relative mouse HID emission
The ESP32-S3 firmware SHALL accept valid `MOUSE_REL_REPORT` commands while armed and emit the corresponding TinyUSB relative mouse report.

#### Scenario: Relative movement emitted
- **WHEN** armed firmware receives a valid raw relative mouse report with non-zero `dx` or `dy`
- **THEN** the HID worker emits a USB mouse report with the same relative movement values

#### Scenario: Mouse button emitted
- **WHEN** armed firmware receives a valid raw relative mouse report with the left button bit set
- **THEN** the HID worker emits a USB mouse report indicating the left button is pressed

### Requirement: Mouse safety release behavior
Firmware release-all behavior SHALL release all mouse buttons in addition to keyboard keys. Disarm, kill, timeout, and BLE disconnect paths SHALL perform release-all behavior.

#### Scenario: Disconnect releases mouse buttons
- **WHEN** BLE disconnects while any mouse button could be pressed
- **THEN** firmware emits a release-all mouse report and returns to a safe disarmed state

### Requirement: Future-compatible HID report identity
The firmware USB HID descriptor strategy SHALL identify keyboard and relative mouse reports in a way that permits a future absolute pointer report without redefining existing keyboard or relative mouse report semantics. When relative mouse support is active, keyboard and mouse report emission SHALL both remain observable from the same firmware build.

#### Scenario: Keyboard regression after mouse addition
- **WHEN** relative mouse support is present
- **THEN** the existing keyboard smoke scenario still observes `KEY_SPACE` down/up for `USB_KEY_SPACE`

#### Scenario: Composite HID output supports both roles
- **WHEN** firmware emits keyboard and relative mouse reports during one composite smoke run
- **THEN** the observer host can observe keyboard key events and relative mouse events without reflashing or changing firmware configuration

### Requirement: Firmware remains layout-unaware
The firmware SHALL NOT interpret text, Unicode, keyboard layouts, host OS profiles, locale, macros, or keymap data.

#### Scenario: Text-like payload rejected
- **WHEN** firmware receives a command that is not a known low-level device opcode, even if its payload contains printable text bytes
- **THEN** firmware rejects it with structured unsupported-opcode or malformed status and emits no HID report

### Requirement: Mouse lifecycle diagnostics
The firmware SHALL emit structured auxiliary UART diagnostics for mouse command receipt, queueing, USB emission, and rejection using the same sequence identifier carried by the command frame.

#### Scenario: Mouse command logged
- **WHEN** firmware receives and emits a valid `MOUSE_REL_REPORT`
- **THEN** auxiliary UART diagnostics include structured events for the command lifecycle with the command sequence identifier
