## MODIFIED Requirements

### Requirement: Companion app uses tap scripts for text streaming
The Android companion app UI and ADB text commands SHALL compile input text plans into keyboard tap script frames and transmit them in batches. The app SHALL pace inter-batch transmission at 390 ms — matched to the firmware drain time for a 32-tap batch (32 × 12 ms = 384 ms) plus a 6 ms margin — so the next batch arrives just as the current one finishes, keeping the firmware's 16-deep `hid_bridge` queue at approximately one item without overflow.

#### Scenario: Text streaming uses tap script with drain-rate pacing
- **WHEN** the app is connected and armed and the user streams text or sends an ADB type-text command
- **THEN** the app compiles the planned keystrokes into `KEYBOARD_TAP_SCRIPT` frames and sends them to the device with a 390 ms inter-batch delay, without app-side sleep delays between individual characters within a batch