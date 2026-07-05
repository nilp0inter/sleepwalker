## ADDED Requirements

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
