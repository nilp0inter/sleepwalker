## ADDED Requirements

### Requirement: Modifier key tracking
The firmware keyboard HID emission SHALL track active modifier key states and include them in the modifier byte of standard USB keyboard reports.

#### Scenario: Modifier state tracked
- **WHEN** the firmware receives a `KEY_DOWN` command for a modifier key
- **THEN** it sets the corresponding modifier bit and includes it in all subsequent keyboard reports until a release-all or `KEY_UP` command clears it
