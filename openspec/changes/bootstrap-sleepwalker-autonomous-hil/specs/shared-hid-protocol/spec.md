## ADDED Requirements

### Requirement: Versioned HID command framing
The system SHALL define a versioned binary command frame shared by Android, firmware, and tests. Each frame SHALL include a sequence identifier, opcode, payload length, payload, and CRC-32 corruption check. CRC-32 SHALL NOT be treated as authorization or authentication.

#### Scenario: Valid frame accepted
- **WHEN** a frame has a supported version, valid length, known opcode, and matching CRC-32
- **THEN** protocol decoders accept the frame and expose its sequence identifier, opcode, and payload to the caller

#### Scenario: Corrupt frame rejected
- **WHEN** a frame CRC-32 does not match the frame contents
- **THEN** protocol decoders reject the frame and report a bad-CRC error without dispatching a HID command

### Requirement: Symbolic USB HID usages
The system SHALL use symbolic USB HID usage names at external command boundaries instead of platform-specific numeric keycodes. The protocol SHALL define a canonical mapping for `USB_KEY_SPACE` to USB keyboard usage `0x2c` and Linux evdev `KEY_SPACE` for the first keyboard smoke scenario.

#### Scenario: Symbolic key command encoded
- **WHEN** the Android command surface receives a request to inject `USB_KEY_SPACE`
- **THEN** the core protocol layer encodes the corresponding USB keyboard usage without exposing Android `KeyEvent` or Linux evdev numeric keycodes as the protocol contract

### Requirement: Sequenced status acknowledgements
The system SHALL define status notifications correlated by sequence identifier. Status values SHALL distinguish at least received, queued, sent-to-USB, malformed frame, bad CRC, disarmed, queue full, USB not mounted, and unsupported opcode outcomes.

#### Scenario: Successful command status chain
- **WHEN** a command is received, queued, and emitted as a USB HID report
- **THEN** status events for the same sequence identifier include received, queued, and sent-to-USB states

#### Scenario: Rejected command status
- **WHEN** a command is rejected before HID dispatch
- **THEN** the status event for the same sequence identifier identifies the rejection reason and no HID report is emitted for that command

### Requirement: Cross-layer event correlation
The system SHALL use stable event names and sequence identifiers in Android logs, ESP UART logs, and HIL artifacts so the coding agent can correlate one command through all layers.

#### Scenario: Correlated keyboard smoke command
- **WHEN** the first keyboard smoke scenario injects `USB_KEY_SPACE`
- **THEN** Android diagnostics, ESP UART diagnostics, and smoke summary artifacts reference the same sequence identifier for that command
