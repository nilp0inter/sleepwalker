## 1. Observer Console Text Sink

- [x] 1.1 Add a raw-mode Linux console text sink helper that captures rendered input bytes from the active VT to SSH-readable artifacts
- [x] 1.2 Add reset, read, status, and stop control surfaces for the text sink so generated examples are isolated
- [x] 1.3 Package the text sink and required console-keymap tools into the observer ISO environment
- [x] 1.4 Add observer-side preparation logic to select the Linux console US keymap for the `linux:us` identity backend
- [x] 1.5 Ensure identity diagnostics can run the evdev observer without exclusive grab while existing smoke paths can still request grab

## 2. Lossless Android Text Input
- [x] 2.1 Add an encoded UTF-8 text extra to the ADB command contract for high-level text commands
- [x] 2.2 Decode the encoded text payload exactly once in `AdbCommandReceiver` before calling the existing text planning path
- [x] 2.3 Preserve the existing plain text ADB command behavior for current smokes and callers
- [x] 2.4 Add structured diagnostics for encoded commands, decoded text length, command sequence, and escaped or encoded received text metadata
- [x] 2.5 Reject invalid encoded payloads with structured failure diagnostics and no HID sends
- [x] 2.6 Add or update Nix ADB adapter support for sending encoded text payloads without shell-sensitive corruption
## 3. Hypothesis Text Identity Runner

- [x] 3.1 Create a Hypothesis text identity test that writes a string of characters to a Linux console
- [x] 3.2 Use the observer-side console keymap preparation and text sink capture surfaces
- [x] 3.3 Use the encoded Android text input to avoid shell-sensitive payload corruption
- [x] 3.4 Wire the evdev observer to read the generated HID events without exclusive grab
- [x] 3.5 Assert that the captured console text matches the original input text
- [x] 3.6 Emit structured pass/fail results with captured text, HID events, and console output artifacts
- [x] 3.7 Write structured pass/fail artifacts with generated input, Android-received input metadata, target output, failure classification, replay data, log paths, and duration

- [x] 4.1 Write or update unit tests for text sink helper control flow (reset, start, read, stop)
- [x] 4.2 Add no-hardware tests for encoded text decoding, invalid encoded payload rejection, and preservation of the existing plain text path
- [x] 4.3 Update build flake to include text sink package in default outputs
+ [x] 4.4 Run smoke-text-identity end-to-end on commissioned hardware and capture pass/fail evidence
+ [x] 4.5 Verify that existing smokes (keyboard, text, mouse, composite) continue to pass
+ [x] 4.6 Run all no-hardware checks (protocol check, firmware build, APK build) successfully
+ [x] 4.7 Archive this change via openspec archive-change
## 5. Physical Bench Verification

+ [x] 5.1 Run the quick Hypothesis text identity scenario on `sleepwalker-hil/bench.toml`
+ [x] 5.2 Inspect the generated text identity summary and artifacts for profile, backend, generated examples, captured output, and failure-classification evidence
+ [x] 5.3 Run the existing fixed text smoke as a regression for event-sequence verification
+ [x] 5.4 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml` and verify keyboard, mouse, observer, and correlation evidence still pass
