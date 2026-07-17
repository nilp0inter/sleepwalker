## MODIFIED Requirements

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

## ADDED Requirements

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
