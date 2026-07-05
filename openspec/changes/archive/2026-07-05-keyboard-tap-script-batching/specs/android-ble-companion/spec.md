## ADDED Requirements

### Requirement: Companion app uses tap scripts for text streaming
The Android companion app UI and ADB text commands SHALL compile input text plans into keyboard tap script frames and transmit them in batches instead of sending individual operations with sleep delays.

#### Scenario: Text streaming uses tap script
- **WHEN** the app is connected and armed and the user streams text or sends an ADB type-text command
- **THEN** the app compiles the planned keystrokes into `KEYBOARD_TAP_SCRIPT` frames and sends them to the device without app-side sleep delays between characters
