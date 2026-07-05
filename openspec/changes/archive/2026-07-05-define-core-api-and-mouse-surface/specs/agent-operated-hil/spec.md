## ADDED Requirements

### Requirement: Relative mouse physical smoke scenario
The HIL SHALL provide an autonomous physical smoke scenario that injects a relative mouse command through the app/library/BLE/firmware path and verifies Linux evdev mouse events on the observer host.

#### Scenario: Mouse click observed
- **WHEN** HIL injects a left-button raw relative mouse report through the public command path
- **THEN** the observer emits JSONL events for `EV_KEY BTN_LEFT 1` and `EV_KEY BTN_LEFT 0`

#### Scenario: Mouse movement observed
- **WHEN** HIL injects a raw relative mouse report with non-zero relative movement
- **THEN** the observer emits JSONL events for `EV_REL REL_X` or `EV_REL REL_Y`

### Requirement: Library-driven command smoke
The HIL SHALL exercise at least one command path that uses public `sleepwalker-core` library behavior rather than private frame construction.

#### Scenario: Library path correlated
- **WHEN** HIL sends a library-driven keyboard or mouse command
- **THEN** Android diagnostics, ESP UART diagnostics, HID observer output, and smoke summary artifacts reference the same command sequence identifier

### Requirement: Keyboard regression remains required
The existing keyboard smoke scenario SHALL remain part of the validation path after adding relative mouse support.

#### Scenario: Keyboard still passes after mouse support
- **WHEN** relative mouse support is present
- **THEN** the keyboard smoke scenario still reports `KEY_SPACE` down/up evidence for `USB_KEY_SPACE`

### Requirement: Reserved opcode behavior covered
Protocol/HIL tests SHALL verify that reserved-but-unimplemented future absolute pointer and virtual serial opcodes fail with structured unsupported-opcode evidence rather than malformed behavior or unintended USB output.

#### Scenario: Reserved future opcode rejected safely
- **WHEN** a reserved future opcode is sent to firmware that does not implement that feature
- **THEN** no USB HID or CDC output is emitted and structured unsupported-opcode evidence is preserved

### Requirement: Mouse smoke artifacts
The mouse smoke operation SHALL write structured artifacts containing bench configuration, Android diagnostics, ESP UART diagnostics, HID observer JSONL, and a machine-readable summary.

#### Scenario: Mouse smoke preserves evidence
- **WHEN** the mouse smoke scenario passes or fails
- **THEN** the artifact directory contains enough structured evidence to identify the layer that emitted or rejected the mouse command
