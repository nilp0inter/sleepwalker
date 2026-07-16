## ADDED Requirements

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