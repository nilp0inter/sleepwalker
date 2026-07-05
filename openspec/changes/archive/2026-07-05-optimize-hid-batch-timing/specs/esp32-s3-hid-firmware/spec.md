## MODIFIED Requirements

### Requirement: Keyboard tap script execution
The firmware SHALL accept valid keyboard tap script commands while armed and execute the sequence by emitting a standard keyboard HID report for each record's modifier mask and USB usage, followed by a release report, using firmware-local timing that holds each press and release state longer than the device HID endpoint polling interval. The HID endpoint polling interval SHALL be 4 ms and each press and release state SHALL be held for 6 ms, giving a 2 ms safety margin over the polling interval and a per-tap time of 12 ms.

#### Scenario: Script executed with poll-safe local timing
- **WHEN** armed firmware receives a valid `KEYBOARD_TAP_SCRIPT` frame containing three tap records
- **THEN** the HID worker emits three keyboard press/release report sequences in order and holds each press and release state for 6 ms, which is longer than the 4 ms HID endpoint polling interval