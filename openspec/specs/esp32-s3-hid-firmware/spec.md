## ADDED Requirements

### Requirement: BLE-only custom UART service
The ESP32-S3 firmware SHALL expose a BLE-only custom UART-style GATT service and SHALL NOT depend on Bluetooth Classic. The service SHALL provide an RX characteristic for Android-to-ESP writes and a TX characteristic for ESP-to-Android status notifications.

#### Scenario: BLE command received
- **WHEN** the bonded Android companion writes a valid command frame to the RX characteristic
- **THEN** the firmware validates the frame and emits a correlated status notification on the TX characteristic

### Requirement: Queue-mediated HID dispatch
The ESP32-S3 firmware SHALL decouple NimBLE GATT write handling from TinyUSB HID writes through a thread-safe `hid_bridge` queue. NimBLE write handlers SHALL NOT call TinyUSB report functions directly.

#### Scenario: BLE callback enqueues command
- **WHEN** a valid HID command is received by the BLE RX handler
- **THEN** the handler copies bounded command data into the `hid_bridge` queue and returns without performing TinyUSB HID output

#### Scenario: HID worker owns USB output
- **WHEN** the HID worker dequeues a command from `hid_bridge`
- **THEN** the HID worker performs safety checks and is the component that emits TinyUSB keyboard reports

### Requirement: Keyboard HID smoke behavior
The ESP32-S3 firmware SHALL support keyboard report emission for `USB_KEY_SPACE` as the first physical E2E command. It SHALL emit both key-down and key-up reports for a key tap.

#### Scenario: Space key tap emitted
- **WHEN** an armed firmware instance receives a valid `USB_KEY_SPACE` tap command
- **THEN** it emits a keyboard report containing usage `0x2c` followed by a release report containing no pressed keys

### Requirement: Safety state gates HID output
The ESP32-S3 firmware SHALL boot into a disarmed state and SHALL gate HID injection behind an explicit arm command. It SHALL support disarm, kill, timeout, BLE disconnect, and release-all behavior to prevent stuck keys or buttons.

#### Scenario: Disarmed command rejected
- **WHEN** the firmware is disarmed and receives a HID injection command
- **THEN** it rejects the command with a disarmed status and does not emit a USB HID report

#### Scenario: Disconnect releases keys
- **WHEN** BLE disconnects while any keyboard or mouse button state could be active
- **THEN** the firmware emits release-all reports and returns to a safe disarmed state

### Requirement: Structured auxiliary UART diagnostics
The ESP32-S3 firmware SHALL emit structured line-oriented diagnostics over the auxiliary UART, not over native USB, while native USB is used for HID device mode.

#### Scenario: Command lifecycle logged
- **WHEN** a command is received, queued, sent to USB, or rejected
- **THEN** the auxiliary UART log includes a structured event with component, event name, sequence identifier, and relevant status fields
