## Purpose
Android companion reference application demonstrating Sleepwalker library integrations, user-facing control UI, and background BLE communication.
## Requirements
### Requirement: Reference app delegates to library
The Android reference app SHALL demonstrate and exercise public `sleepwalker-core` library behavior. It SHALL NOT own protocol encoding, keymap rendering, or HID semantic logic that belongs in the reusable library.

#### Scenario: App injects key through library
- **WHEN** the reference app or ADB command path requests a key injection
- **THEN** the command is encoded through `sleepwalker-core` library behavior rather than app-local protocol construction

### Requirement: ADB command path uses public surfaces
ADB-driven commands SHALL exercise the same library/session behavior available to normal library consumers.

#### Scenario: ADB mouse command uses library path
- **WHEN** HIL sends an ADB command for relative mouse movement or button click
- **THEN** the app delegates to the public library/session path and records structured diagnostics for that command sequence

### Requirement: Single BLE session ownership
BLE scan, connect, GATT write, MTU handling, and status notification parsing SHALL be owned by one session/service path rather than duplicated across app entry points.

#### Scenario: Receiver delegates long-running BLE work
- **WHEN** the ADB receiver receives a command requiring BLE I/O
- **THEN** it delegates to the service/session owner instead of performing independent scan/connect/write logic

### Requirement: Reference app exposes capability demos
The reference app SHALL expose demo/debug surfaces for connection, arm/disarm/kill, low-level keyboard, low-level relative mouse, and high-level text planning once supported by the library.

#### Scenario: Mouse demo command available
- **WHEN** the app is installed for HIL or manual demonstration
- **THEN** a caller can request a relative mouse click or movement through an explicit command surface

### Requirement: Minimal text demo UI
The Android reference app SHALL expose a minimal UI containing connection/safety affordances, a fixed host profile label, a text input, and status/error feedback.

#### Scenario: Main activity shows text demo
- **WHEN** the reference app main activity opens
- **THEN** it shows a fixed `US QWERTY seed` host profile label, connect control, arm control, kill control, text input, and last-status or last-error display

### Requirement: Text input streams inserted valid characters
The reference app text input SHALL stream inserted valid characters through the `sleepwalker-core` high-level text planner and low-level execution path.

#### Scenario: Valid character inserted
- **WHEN** the app is connected and armed and the user inserts a valid character into the text input
- **THEN** the app plans that inserted character through `sleepwalker-core` and sends the resulting low-level operations to the device

#### Scenario: Paste valid text
- **WHEN** the app is connected and armed and the user pastes valid text into the text input
- **THEN** the app plans and sends only the inserted pasted substring in character order

### Requirement: Text input rejects invalid characters without approximating
The reference app SHALL not send HID operations for characters that the selected host profile cannot represent.

#### Scenario: Invalid character inserted
- **WHEN** the user inserts a character that the fixed host profile cannot represent
- **THEN** the app displays a structured error and sends no HID operations for that insertion

### Requirement: Text input is not a remote editor
The reference app SHALL treat the text input as a keystroke stream rather than a synchronized remote text field. Deletions and local field clearing SHALL NOT be mirrored to the target host in this change.

#### Scenario: Local deletion not mirrored
- **WHEN** the user deletes text from the Android text input
- **THEN** the app updates the local field without sending backspace or delete HID operations to the device

### Requirement: Demo UI uses public library path
The reference app UI SHALL use `sleepwalker-core` text planning and the same app/session send path used by ADB commands rather than constructing protocol frames in UI code.

#### Scenario: UI text command uses library path
- **WHEN** the UI sends text to the device
- **THEN** command construction is delegated to `sleepwalker-core` and BLE sending uses the shared app/session path

### Requirement: Companion app uses tap scripts for text streaming
The Android companion app UI and ADB text commands SHALL compile input text plans into keyboard tap script frames and transmit them in batches instead of sending individual operations with sleep delays.

#### Scenario: Text streaming uses tap script
- **WHEN** the app is connected and armed and the user streams text or sends an ADB type-text command
- **THEN** the app compiles the planned keystrokes into `KEYBOARD_TAP_SCRIPT` frames and sends them to the device without app-side sleep delays between characters

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

