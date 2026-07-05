## Purpose
Shared Sleepwalker HID protocol definitions and constants across Kotlin, Python, and C/C++ firmware, ensuring wire-format compatibility.

## Requirements

### Requirement: Device-class opcode namespaces
The shared command protocol SHALL define stable opcode namespaces for safety/control, keyboard HID, relative mouse HID, future absolute pointer HID, future virtual serial/CDC, and future device capability/configuration commands.

#### Scenario: Opcode namespace documented
- **WHEN** protocol constants are inspected in Python, Kotlin, and firmware C headers
- **THEN** the same device-class opcode ranges are represented without overlap

### Requirement: Raw relative mouse report opcode
The protocol SHALL define a raw relative mouse report opcode in the relative mouse namespace. Its payload SHALL be exactly five bytes: `buttons:u8`, `dx:i8`, `dy:i8`, `wheel:i8`, and `pan:i8`.

#### Scenario: Relative mouse frame accepted
- **WHEN** a frame contains a supported version, valid CRC, opcode `MOUSE_REL_REPORT`, and a five-byte payload
- **THEN** protocol decoders accept the frame and expose the raw payload to callers

#### Scenario: Relative mouse payload length rejected
- **WHEN** a `MOUSE_REL_REPORT` frame has any payload length other than five bytes
- **THEN** the command is rejected as malformed or unsupported before HID dispatch

### Requirement: Future absolute pointer reservation
The protocol SHALL reserve an opcode namespace for future absolute pointer HID reports without requiring a v1 frame layout change.

#### Scenario: Reserved absolute pointer opcode unsupported
- **WHEN** firmware that does not implement absolute pointer receives an opcode from the reserved absolute pointer namespace
- **THEN** it reports a structured unsupported-opcode status and emits no USB HID report

### Requirement: Future virtual serial reservation
The protocol SHALL reserve an opcode namespace for future USB CDC / virtual serial commands without requiring a v1 frame layout change.

#### Scenario: Reserved serial opcode unsupported
- **WHEN** firmware that does not implement virtual serial receives an opcode from the reserved serial namespace
- **THEN** it reports a structured unsupported-opcode status and emits no USB CDC or HID output

### Requirement: Extensible status context
Status notifications SHALL preserve opaque context bytes so future capability responses and device-class metadata can be added without changing the frame format.

#### Scenario: Status context parsed opaquely
- **WHEN** a status notification contains context bytes unknown to the current library
- **THEN** the library preserves the context bytes with the parsed sequence identifier and status value

### Requirement: Cross-language mouse constant parity
Python protocol helpers, Kotlin protocol helpers, and firmware C constants SHALL agree on the relative mouse opcode value, payload field order, and button bit assignments.

#### Scenario: Mouse fixture parity
- **WHEN** the relative mouse golden fixture is encoded and decoded across Python, Kotlin, and firmware tests
- **THEN** every implementation observes the same opcode, payload bytes, and CRC result

### Requirement: Expanded symbolic keyboard usage registry
The shared protocol support libraries SHALL define symbolic USB keyboard usages required by the seed US QWERTY text planner, including letters, digits, selected controls, Shift, and punctuation key positions.

#### Scenario: Letter and digit usages available
- **WHEN** Kotlin or Python code requests symbolic usages for `USB_KEY_A` and `USB_KEY_1`
- **THEN** the registry resolves each symbol to the canonical USB HID keyboard usage value

#### Scenario: Shift usage available
- **WHEN** the text planner needs a Shift modifier key operation
- **THEN** the registry resolves `USB_KEY_LEFTSHIFT` to the canonical USB HID modifier usage representation used by the low-level keyboard API

### Requirement: Cross-language seed usage parity
Python protocol helpers and Kotlin protocol helpers SHALL agree on the symbolic names and USB usage values required by the seed US QWERTY text planner.

#### Scenario: Seed usage parity checked
- **WHEN** protocol tests inspect seed text usages across Python and Kotlin
- **THEN** matching symbolic names resolve to the same USB HID usage values
