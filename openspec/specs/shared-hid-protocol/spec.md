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
The shared protocol support libraries SHALL define symbolic USB keyboard usages required by the seed US QWERTY text planner, including letters, digits, selected controls, Shift, punctuation key positions, and the canonical `USB_KEY_F24` usage. `USB_KEY_F24` SHALL retain its canonical USB HID keyboard usage value and SHALL be available for fixture synchronization and for Editor plans when the applicable execution policy does not reserve it.

#### Scenario: Letter and digit usages available
- **WHEN** Kotlin or Python code requests symbolic usages for `USB_KEY_A` and `USB_KEY_1`
- **THEN** the registry resolves each symbol to the canonical USB HID keyboard usage value

#### Scenario: Shift usage available
- **WHEN** the text planner needs a Shift modifier key operation
- **THEN** the registry resolves `USB_KEY_LEFTSHIFT` to the canonical USB HID modifier usage representation used by the low-level keyboard API

#### Scenario: F24 usage is canonical and policy-neutral
- **WHEN** Kotlin or Python code requests `USB_KEY_F24`
- **THEN** both registries resolve the same canonical USB HID keyboard usage value and the registry itself does not prohibit an Editor plan from using it
### Requirement: Cross-language seed usage parity
Python protocol helpers and Kotlin protocol helpers SHALL agree on the symbolic names and USB usage values required by the seed US QWERTY text planner.

#### Scenario: Seed usage parity checked
- **WHEN** protocol tests inspect seed text usages across Python and Kotlin
- **THEN** matching symbolic names resolve to the same USB HID usage values

### Requirement: Keyboard tap script opcode and payload
The protocol SHALL define a keyboard tap script opcode `KEYBOARD_TAP_SCRIPT` (0x0014) in the keyboard HID namespace. Its payload SHALL contain a count byte (`count:u8`) indicating the number of taps, followed by that many 2-byte tap records, where each record consists of: `modifiers:u8` and `usage:u8`.

#### Scenario: Tap script frame accepted
- **WHEN** a frame contains a supported version, valid CRC, opcode `KEYBOARD_TAP_SCRIPT`, and a payload with a valid count and matching number of 2-byte records
- **THEN** protocol decoders accept the frame and expose the raw payload to callers

### Requirement: Symbolic synchronization keyboard usage
The shared protocol support libraries SHALL define a reserved symbolic USB keyboard usage `USB_KEY_F24` for target-fixture synchronization. The usage SHALL be the only new symbolic keyboard usage added by this change and SHALL be reserved so target fixtures can prove that all preceding physical input was consumed before an authoritative snapshot is taken, without relying on fixed sleeps. The usage SHALL be distinct from all existing symbolic keyboard usages and SHALL NOT be used for text rendering.

#### Scenario: Synchronization usage available
- **WHEN** Kotlin or Python code requests the symbolic usage `USB_KEY_F24`
- **THEN** the registry resolves it to a canonical reserved USB HID keyboard usage value distinct from all text-rendering usages

#### Scenario: Synchronization usage not used for text
- **WHEN** the text planner or Editor plans printable text or reconciliation plans
- **THEN** the `USB_KEY_F24` usage is never emitted as part of a text rendering or reconciliation plan

### Requirement: Synchronization usage registry parity and raw firmware acceptance
The Python and Kotlin shared protocol support libraries SHALL resolve `USB_KEY_F24` to the same canonical USB HID usage value. Firmware SHALL remain symbolic-usage-unaware: it SHALL accept that raw usage through the existing generic keyboard opcode path and emit the corresponding USB HID report without a new symbolic registry, opcode, frame layout, or dispatch branch.

#### Scenario: Registry parity and raw firmware path checked
- **WHEN** protocol tests inspect `USB_KEY_F24` in Python and Kotlin and send its canonical raw value through firmware's existing keyboard-tap path
- **THEN** the Python and Kotlin symbolic names resolve to the same USB HID usage value and firmware emits the corresponding USB HID report without target-specific behavior

#### Scenario: Synchronization key observed by fixture
- **WHEN** the ESP32-S3 emits the `USB_KEY_F24` key-down and key-up sequence
- **THEN** the target fixture observes the synchronization key and confirms all preceding physical input has been consumed before taking an authoritative snapshot


### Requirement: Conditional F24 execution reservation
F24 reservation SHALL be an explicit Editor execution-policy decision, not an unconditional shared-protocol or production-Editor-plan prohibition. A policy that reserves F24 SHALL reject it before execution; absent such a policy, the Editor compiler SHALL accept a structurally valid symbolic F24 action and MAY emit it through the ordinary keyboard path when selected by a package plan. Reservation policy SHALL not alter F24's canonical usage value or wire encoding.

#### Scenario: Reserved policy rejects F24
- **WHEN** an Editor compiler is configured with F24 reservation and receives a symbolic F24 action
- **THEN** it rejects the action before sending a low-level keyboard operation

### Requirement: Unchanged firmware and wire path for F24
Firmware SHALL remain unaware of symbolic usage names and execution policies. F24 and every other validated keyboard usage SHALL traverse the existing generic raw keyboard opcode, frame format, dispatch path, and USB HID report path; this change SHALL introduce no F24-specific firmware opcode, wire field, protocol version, or dispatch branch.

#### Scenario: Raw F24 path remains generic
- **WHEN** the host sends the canonical raw F24 usage through the existing keyboard operation path
- **THEN** firmware processes it through the generic keyboard dispatch and emits the corresponding USB HID report without F24-specific protocol behavior
