## MODIFIED Requirements

### Requirement: HID input permissions and discovery
The observer ISO SHALL configure input-device permissions and discovery so the HID observer helper can locate Sleepwalker keyboard and relative mouse event devices by stable descriptor and capability information rather than by unstable `/dev/input/eventX` numbering.

#### Scenario: Keyboard HID device discovered
- **WHEN** the ESP32-S3 enumerates as a Sleepwalker composite USB HID device with keyboard capability
- **THEN** the observer helper identifies the matching keyboard-capable event device and reports a structured device-found event

#### Scenario: Relative mouse HID device discovered
- **WHEN** the ESP32-S3 enumerates as a Sleepwalker composite USB HID device with relative mouse capability
- **THEN** the observer helper identifies the matching mouse-capable event device and reports a structured device-found event

#### Scenario: Combined node accepted
- **WHEN** the same event device exposes both keyboard and relative mouse capabilities for the Sleepwalker device
- **THEN** the observer helper accepts the device for both keyboard and mouse observation roles

### Requirement: JSONL evdev reporting
The observer helper SHALL emit line-oriented structured events for Linux input events, including event type, symbolic event name, numeric type/code/value, timestamp, and matched device identity. The helper SHALL decode Sleepwalker keyboard keys, mouse buttons, relative axes, and sync events into stable symbolic names.

#### Scenario: Space key observed
- **WHEN** the ESP32-S3 emits a `USB_KEY_SPACE` key-down and key-up sequence
- **THEN** the observer helper emits JSONL events for `EV_KEY KEY_SPACE 1`, `EV_SYN SYN_REPORT`, `EV_KEY KEY_SPACE 0`, and `EV_SYN SYN_REPORT`

#### Scenario: Mouse button observed
- **WHEN** the ESP32-S3 emits a left mouse button press and release
- **THEN** the observer helper emits JSONL events for `EV_KEY BTN_LEFT 1` and `EV_KEY BTN_LEFT 0`

#### Scenario: Relative movement observed
- **WHEN** the ESP32-S3 emits relative mouse movement
- **THEN** the observer helper emits JSONL events for `EV_REL REL_X` or `EV_REL REL_Y` with the observed relative value
