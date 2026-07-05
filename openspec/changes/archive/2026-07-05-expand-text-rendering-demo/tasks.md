## 1. Symbolic Usages and Seed Keymap

- [x] 1.1 Expand Kotlin symbolic USB keyboard usage registry for letters, digits, selected controls, Shift, and punctuation key positions required by seed US QWERTY text planning
- [x] 1.2 Expand Python symbolic USB keyboard usage registry to match the Kotlin seed usage set
- [x] 1.3 Add parity tests proving Kotlin and Python seed usage names map to the same USB HID usage values
- [x] 1.4 Complete the seed US QWERTY keymap entries for printable ASCII direct and shifted characters plus selected controls
- [x] 1.5 Keep full global keymap corpus ingestion out of this change while preserving the bundled database abstraction
- [x] 1.6 Implement modifier key tracking in firmware usb_hid
- [x] 1.7 Compile and flash the new firmware to the device
- [x] 1.8 Verify uppercase letters are typed correctly as uppercase

## 2. Text Planner Engine

- [x] 2.1 Update `TextPlanner` to translate modifier metadata into explicit modifier down/up operations around key taps
- [x] 2.2 Preserve simple `KEY_TAP` plans for unmodified characters
- [x] 2.3 Return inspectable ordered plans containing opcode, usage, sequence identifier, and modifier operations
- [x] 2.4 Enforce atomic text rendering failure when any requested glyph is missing from the selected host profile
- [x] 2.5 Add Kotlin unit tests for unmodified letters, shifted letters, digits, shifted punctuation, spaces, missing layouts, and unrepresentable glyphs
- [x] 2.6 Add unit tests showing text execution uses public low-level keyboard operations from the plan

## 3. Reference App Text Demo UI

- [x] 3.1 Factor a minimal app command/session boundary if needed so `MainActivity` and ADB commands use the same app/library send path
- [x] 3.2 Replace the empty `MainActivity` with a minimal programmatic UI containing connect, arm, kill, fixed host profile label, text input, and status/error feedback
- [x] 3.3 Implement insertion-only text streaming from the UI text input through `sleepwalker-core` text planning
- [x] 3.4 Reject invalid inserted characters with a visible structured error and no HID sends
- [x] 3.5 Ensure local deletions, selection replacement deletion, and field clearing do not emit backspace/delete HID operations
- [x] 3.6 Preserve existing ADB command behavior for connect, arm, kill, keyboard, mouse, and composite smokes

## 4. HIL and Observer Text Evidence

- [x] 4.1 Extend observer symbolic decoding for `KEY_A`, `KEY_1`, `KEY_LEFTSHIFT`, and any additional keys required by the text smoke
- [x] 4.2 Add high-level text smoke that sends representative text through the public app/library path and verifies the expected key event sequence
- [x] 4.3 Add UI text smoke that launches `MainActivity`, focuses the text input, inserts a valid smoke string through ADB input, and verifies the target HID event sequence
- [x] 4.4 Write text smoke summaries with expected key sequence, observed key sequence, missing events, extra events, ordering failures, and correlation evidence
- [x] 4.5 Keep composite keyboard/mouse smoke available as a regression after UI/session changes

## 5. Final Verification

- [x] 5.1 Run targeted protocol parity tests for expanded seed usages
- [x] 5.2 Run targeted Kotlin unit tests for keymap and text planner behavior
- [x] 5.3 Build the Android app and verify the minimal text demo UI compiles and launches
- [x] 5.4 Run high-level text smoke and UI text smoke on the commissioned bench
- [x] 5.5 Run composite keyboard/mouse smoke regression and confirm it still passes
