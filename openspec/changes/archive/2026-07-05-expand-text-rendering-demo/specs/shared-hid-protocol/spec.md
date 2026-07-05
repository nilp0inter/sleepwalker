## ADDED Requirements

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
