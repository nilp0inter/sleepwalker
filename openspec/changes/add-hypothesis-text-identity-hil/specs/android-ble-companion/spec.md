## ADDED Requirements

### Requirement: Lossless encoded ADB text command
The Android companion ADB command path SHALL accept an encoded text payload for high-level text commands so generated property-test strings reach `sleepwalker-core` unchanged despite shell-sensitive characters.

#### Scenario: Encoded text decoded before planning
- **WHEN** an ADB text command includes an encoded UTF-8 text payload
- **THEN** the Android command receiver decodes it exactly once and passes the decoded string to the existing `sleepwalker-core` text planning and tap-script compilation path

#### Scenario: Shell-sensitive printable text preserved
- **WHEN** the encoded payload represents printable text containing spaces, quotes, backslashes, punctuation, or shell metacharacters
- **THEN** the Android command receiver observes the same decoded text that the HIL generated

#### Scenario: Existing plain text command remains available
- **WHEN** an existing smoke or caller sends the current plain text extra for a text command
- **THEN** the app continues to process that text through the existing public library path

### Requirement: Encoded text diagnostics
The Android companion SHALL emit structured diagnostics for encoded text commands sufficient for HIL identity artifacts to distinguish input corruption from downstream typing failures.

#### Scenario: Encoded command logs decoded input metadata
- **WHEN** the Android command receiver accepts an encoded text command
- **THEN** diagnostics include command identity, command sequence, decoded text length, and enough encoded or escaped text metadata for the HIL artifact to compare generated input with Android-received input

#### Scenario: Invalid encoded text rejected clearly
- **WHEN** an encoded text command contains invalid encoding or invalid UTF-8 for the selected payload format
- **THEN** the app reports a structured command failure and sends no HID operations for that command
