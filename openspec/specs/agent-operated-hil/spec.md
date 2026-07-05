# agent-operated-hil

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

### Requirement: Composite keyboard and mouse smoke scenario
The HIL SHALL provide one autonomous composite smoke scenario that verifies keyboard and relative mouse behavior against the same firmware build, Android app build, BLE session, and observer session.

#### Scenario: Composite smoke passes
- **WHEN** the composite smoke runs on a commissioned bench
- **THEN** it observes `KEY_SPACE` down/up, `BTN_LEFT` down/up, at least one relative mouse movement event, and correlated command evidence across Android, ESP UART, HID observer, and summary artifacts

#### Scenario: Composite smoke avoids human dependency
- **WHEN** the bench is already commissioned and reachable
- **THEN** the composite smoke completes without invoking a human gate

### Requirement: Composite smoke artifact schema
The composite smoke operation SHALL write a machine-readable summary with separate keyboard, mouse, observer-device, and correlation sections.

#### Scenario: Composite summary separates evidence
- **WHEN** the composite smoke finishes
- **THEN** the summary reports keyboard evidence, mouse evidence, matched observer devices, and cross-layer sequence correlation as separate fields

#### Scenario: Composite failure preserves layer evidence
- **WHEN** keyboard or mouse evidence is missing
- **THEN** the artifact directory preserves Android diagnostics, ESP UART diagnostics, HID observer JSONL, matched device metadata, and a failing summary identifying the missing evidence class

### Requirement: Keyboard and mouse standalone smokes remain useful
Existing standalone keyboard and relative mouse smokes SHALL remain available and produce summaries that use the same symbolic evdev decoding as the composite smoke.

#### Scenario: Standalone summaries use symbolic names
- **WHEN** standalone keyboard or mouse smoke runs
- **THEN** its summary is based on symbolic evdev events rather than raw-code fallback notes

### Requirement: Composite observer grab
The HIL SHALL start observation with exclusive grab on every matched Sleepwalker evdev node used by the composite smoke.

#### Scenario: All matched nodes grabbed
- **WHEN** composite smoke starts observation
- **THEN** keyboard-capable and mouse-capable Sleepwalker event devices are grabbed for the active observation window

### Requirement: ESP32-S3 reset before smoke captures
The HIL SHALL reset the ESP32-S3 into a known-good state via UART RTS pulse before starting smoke captures, so that stale BLE/firmware state from a previous smoke does not cause command failures.

#### Scenario: Board reset before smoke
- **WHEN** a smoke operation begins on a commissioned bench
- **THEN** the ESP32-S3 is reset via RTS (EN) with DTR deasserted (GPIO0 high = normal boot) before UART capture and BLE commands start

### Requirement: BLE session readiness wait before arming
The HIL SHALL wait for BLE GATT session readiness before sending arm or inject commands, polling Android diagnostics for subscribe or services-discovered events within a bounded timeout.

#### Scenario: Commands wait for BLE connection
- **WHEN** a smoke operation sends a connect command
- **THEN** the operation waits for BLE subscribe or services_discovered evidence before proceeding to arm

#### Scenario: BLE not ready produces clear failure
- **WHEN** BLE does not reach subscribe or services_discovered within the bounded timeout
- **THEN** the smoke writes a failing summary identifying the connection layer as the failure point and does not send arm or inject commands

### Requirement: UART capture without board reset
The HIL UART capture SHALL open the serial port with DTR and RTS deasserted so that opening the port does not reset the ESP32-S3 and cause USB HID re-enumeration during observation.

#### Scenario: Serial open preserves HID enumeration
- **WHEN** the UART capture opens the ESP32-S3 serial port
- **THEN** DTR and RTS remain deasserted and the observer host's USB HID devices stay enumerated throughout the capture window
