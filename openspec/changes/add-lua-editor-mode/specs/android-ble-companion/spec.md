## ADDED Requirements

### Requirement: Encoded Editor complete-text ADB command path

The Android companion ADB command path SHALL accept an encoded complete-text payload for Editor `setText` commands so generated snapshot-sequence strings reach `sleepwalker-core` unchanged despite shell-sensitive characters. The command path SHALL decode the encoded payload exactly once and pass the decoded complete snapshot to the `sleepwalker-core` Editor reconciliation API. The existing plain text and encoded append-only text command paths SHALL remain available.

#### Scenario: Encoded Editor text decoded before reconciliation

- **WHEN** an ADB Editor command includes an encoded UTF-8 complete-text payload
- **THEN** the Android command receiver decodes it exactly once and passes the decoded complete snapshot to the `sleepwalker-core` Editor `setText` path

#### Scenario: Shell-sensitive complete text preserved

- **WHEN** the encoded Editor payload represents complete text containing spaces, quotes, backslashes, punctuation, or shell metacharacters
- **THEN** the Android command receiver observes the same decoded text that the HIL generated

#### Scenario: Existing append-only text commands remain available

- **WHEN** an existing smoke or caller sends the current plain or encoded append-only text command
- **THEN** the app continues to process that text through the existing public library append-only text path

### Requirement: Editor execution through shared BLE session

The Android companion SHALL exercise the Editor through the same shared BLE session and service path that owns scan, connect, GATT write, MTU handling, and status notification parsing. Editor reconciliation plans SHALL be executed through the serialized executor and emitted as low-level keyboard operations over the shared BLE session. The app SHALL NOT duplicate BLE session logic for Editor commands.

#### Scenario: Editor plan uses shared BLE session

- **WHEN** an Editor `setText` command is processed
- **THEN** the reconciliation plan is executed through the serialized executor and its low-level operations are sent over the shared BLE session owned by the service path

#### Scenario: No duplicated BLE logic for Editor

- **WHEN** the ADB receiver receives an Editor command requiring BLE I/O
- **THEN** it delegates to the service/session owner instead of performing independent scan/connect/write logic

### Requirement: Serialized status-aware Editor execution

The Android companion SHALL execute Editor reconciliation plans in a serialized, status-aware manner. The executor SHALL correlate each emitted low-level operation with its firmware status notification using the command sequence identifier and SHALL NOT begin the next `setText` call until the current plan completes. The executor SHALL detect armed/disarmed/kill states and SHALL NOT emit Editor HID operations while the firmware is disarmed or killed.

#### Scenario: Editor operations correlated with status

- **WHEN** the executor emits a low-level operation from an Editor plan
- **THEN** it correlates the operation with the firmware status notification carrying the same sequence identifier before proceeding

#### Scenario: Editor execution serialized across calls

- **WHEN** multiple Editor `setText` commands arrive while one is executing
- **THEN** the executor completes the current plan before beginning the next and does not interleave them

#### Scenario: Disarmed firmware blocks Editor execution

- **WHEN** an Editor plan is ready to execute but the firmware is disarmed or killed
- **THEN** the executor does not emit Editor HID operations and reports a structured safety-state failure

### Requirement: Editor diagnostics

The Android companion SHALL emit structured diagnostics for Editor commands sufficient for HIL artifacts to distinguish input corruption from reconciliation and typing failures. Diagnostics SHALL include command identity, command sequence, decoded complete snapshot length, target package identity, host ABI version, plan operation count, and per-operation status correlation.

#### Scenario: Editor command logs reconciliation metadata

- **WHEN** the Android command receiver accepts an Editor `setText` command
- **THEN** diagnostics include command identity, command sequence, decoded snapshot length, target package identity, host ABI version, and plan operation count

#### Scenario: Invalid encoded Editor text rejected clearly

- **WHEN** an encoded Editor command contains invalid encoding or invalid UTF-8 for the selected payload format
- **THEN** the app reports a structured command failure and sends no HID operations for that command