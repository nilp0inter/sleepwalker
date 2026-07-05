## ADDED Requirements

### Requirement: Keyboard tap script execution
The firmware SHALL accept valid keyboard tap script commands while armed and execute the sequence by emitting a standard keyboard HID report for each record's modifier mask and USB usage, followed by a release report, using firmware-local timing that holds each press and release state long enough to be observed by the HID host.

#### Scenario: Script executed with poll-safe local timing
- **WHEN** armed firmware receives a valid `KEYBOARD_TAP_SCRIPT` frame containing three tap records
- **THEN** the HID worker emits three keyboard press/release report sequences in order and holds each press and release state for longer than the device HID endpoint polling interval

### Requirement: Keyboard tap script safety release
If the safety state transitions away from armed (disarmed, killed, timed out, or BLE disconnects) during tap script execution, the firmware SHALL abort execution and emit a keyboard release report immediately.

#### Scenario: Script aborted on disarm
- **WHEN** the firmware is executing a tap script and a `DISARM` command is dequeued or BLE disconnects
- **THEN** the firmware immediately aborts execution, emits a keyboard release report, and returns to a safe disarmed state
