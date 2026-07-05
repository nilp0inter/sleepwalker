## ADDED Requirements

### Requirement: High-level text smoke scenario
The HIL SHALL provide an autonomous smoke scenario that sends representative text through the public app/library text path and verifies the expected keyboard HID event sequence on the observer host.

#### Scenario: Text smoke passes
- **WHEN** HIL sends representative text such as `aA1` through the high-level text path
- **THEN** the observer sees the expected `KEY_A`, `KEY_LEFTSHIFT`, and `KEY_1` event sequence with cross-layer command correlation

### Requirement: Text UI smoke scenario
The HIL SHALL be able to launch the reference app UI and inject text into the Android text field using ADB so the UI path can be tested without a human.

#### Scenario: UI text field drives device
- **WHEN** HIL focuses the reference app text input and inserts a valid smoke string through Android ADB input
- **THEN** the observer sees the expected HID key event sequence on the target host

### Requirement: Text smoke artifacts
Text smoke operations SHALL write structured artifacts containing Android diagnostics, HID observer JSONL, ESP UART diagnostics when captured, and a summary with expected and observed key sequences.

#### Scenario: Text smoke preserves sequence evidence
- **WHEN** text smoke passes or fails
- **THEN** the artifact summary identifies missing, extra, or misordered key events separately from connection or correlation failures

### Requirement: Composite regression remains required
The combined keyboard and mouse smoke SHALL remain available after text demo work.

#### Scenario: Composite smoke still passes
- **WHEN** high-level text demo support is present
- **THEN** the composite keyboard and mouse smoke still reports passing keyboard, mouse, observer, and correlation evidence
