## ADDED Requirements

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
