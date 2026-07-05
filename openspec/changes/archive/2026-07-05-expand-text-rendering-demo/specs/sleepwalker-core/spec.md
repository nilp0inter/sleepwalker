## ADDED Requirements

### Requirement: Modifier-aware text planning
The `sleepwalker-core` text planner SHALL use keymap modifier metadata to emit explicit modifier key down/up operations around key taps.

#### Scenario: Shifted letter planned
- **WHEN** a caller plans text `A` for the seed US QWERTY host profile
- **THEN** the resulting plan contains `KEY_DOWN USB_KEY_LEFTSHIFT`, `KEY_TAP USB_KEY_A`, and `KEY_UP USB_KEY_LEFTSHIFT` in that order

#### Scenario: Unmodified letter planned
- **WHEN** a caller plans text `a` for the seed US QWERTY host profile
- **THEN** the resulting plan contains `KEY_TAP USB_KEY_A` without modifier operations

### Requirement: Complete seed US QWERTY printable subset
The bundled seed keymap database SHALL include direct and shifted mappings for printable US QWERTY ASCII characters and selected controls needed by the first text demo.

#### Scenario: Shifted digit punctuation planned
- **WHEN** a caller plans text `!` for the seed US QWERTY host profile
- **THEN** the resulting plan contains Shift around `USB_KEY_1`

#### Scenario: Space and digit planned
- **WHEN** a caller plans text ` 1` for the seed US QWERTY host profile
- **THEN** the resulting plan contains taps for `USB_KEY_SPACE` and `USB_KEY_1`

### Requirement: Inspectable text execution plan
The high-level text API SHALL return an inspectable ordered plan containing low-level keyboard operations before those operations are sent over BLE.

#### Scenario: Plan can be inspected
- **WHEN** a caller plans text `aA1`
- **THEN** the caller can inspect every low-level operation, including opcode, USB usage, sequence identifier, and modifier operations

### Requirement: Atomic text rendering failure
The text planner SHALL fail before emitting any HID operation when any character in the requested text is not representable by the selected host profile.

#### Scenario: Invalid glyph blocks whole request
- **WHEN** a caller plans text containing a glyph absent from the seed US QWERTY profile
- **THEN** the planner returns a structured unrepresentable-glyph failure and no low-level HID operations

### Requirement: Text execution uses low-level keyboard API
Executing a text plan SHALL send operations through the same low-level keyboard API exposed to library consumers.

#### Scenario: Text execution reuses low-level operations
- **WHEN** a text plan is executed
- **THEN** each emitted command is one of the public low-level keyboard operations from the plan
