## ADDED Requirements

### Requirement: Text smoke key decoding
The observer helper SHALL decode the keyboard keys needed by the high-level text smoke, including letters, digits, Shift, and existing space/sync events.

#### Scenario: Letter and digit observed
- **WHEN** the device emits `USB_KEY_A` or `USB_KEY_1`
- **THEN** the observer helper emits symbolic JSONL events for `KEY_A` or `KEY_1`

#### Scenario: Shift observed
- **WHEN** the device emits a Shift modifier press or release
- **THEN** the observer helper emits symbolic JSONL events for `KEY_LEFTSHIFT`
