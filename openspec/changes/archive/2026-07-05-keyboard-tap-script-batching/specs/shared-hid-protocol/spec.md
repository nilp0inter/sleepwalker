## ADDED Requirements

### Requirement: Keyboard tap script opcode and payload
The protocol SHALL define a keyboard tap script opcode `KEYBOARD_TAP_SCRIPT` (0x0014) in the keyboard HID namespace. Its payload SHALL contain a count byte (`count:u8`) indicating the number of taps, followed by that many 2-byte tap records, where each record consists of: `modifiers:u8` and `usage:u8`.

#### Scenario: Tap script frame accepted
- **WHEN** a frame contains a supported version, valid CRC, opcode `KEYBOARD_TAP_SCRIPT`, and a payload with a valid count and matching number of 2-byte records
- **THEN** protocol decoders accept the frame and expose the raw payload to callers
