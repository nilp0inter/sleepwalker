## 1. Observer Console Text Sink

- [ ] 1.1 Add a raw-mode Linux console text sink helper that captures rendered input bytes from the active VT to SSH-readable artifacts
- [ ] 1.2 Add reset, read, status, and stop control surfaces for the text sink so generated examples are isolated
- [ ] 1.3 Package the text sink and required console-keymap tools into the observer ISO environment
- [ ] 1.4 Add observer-side preparation logic to select the Linux console US keymap for the `linux:us` identity backend
- [ ] 1.5 Ensure identity diagnostics can run the evdev observer without exclusive grab while existing smoke paths can still request grab

## 2. Lossless Android Text Input

- [ ] 2.1 Add an encoded UTF-8 text extra to the ADB command contract for high-level text commands
- [ ] 2.2 Decode the encoded text payload exactly once in `AdbCommandReceiver` before calling the existing text planning path
- [ ] 2.3 Preserve the existing plain text ADB command behavior for current smokes and callers
- [ ] 2.4 Add structured diagnostics for encoded commands, decoded text length, command sequence, and escaped or encoded received text metadata
- [ ] 2.5 Reject invalid encoded payloads with structured failure diagnostics and no HID sends
- [ ] 2.6 Add or update Nix ADB adapter support for sending encoded text payloads without shell-sensitive corruption

## 3. Hypothesis Text Identity Runner

- [ ] 3.1 Add Hypothesis to the HIL/property-test environment without adding runtime dependencies to firmware or the Kotlin library
- [ ] 3.2 Define the initial `linux:us` printable identity alphabet from the seed US text domain, excluding ESC and other control semantics
- [ ] 3.3 Implement session-scoped setup: bench validation, ESP reset, captures, observer sink preparation, BLE connect readiness wait, and firmware arm
- [ ] 3.4 Implement per-example execution: sink reset, encoded ADB text send, output-length polling, quiet-period stability check, and byte-for-byte comparison
- [ ] 3.5 Implement quick and deep profiles with bounded Hypothesis example count and string length settings recorded in summaries
- [ ] 3.6 Implement replay-on-failure so non-reproducible hardware/timing failures are classified separately from deterministic text counterexamples
- [ ] 3.7 Write structured pass/fail artifacts with generated input, Android-received input metadata, target output, failure classification, replay data, log paths, and duration

## 4. Targeted Tests and Builds

- [ ] 4.1 Add no-hardware tests for encoded text decoding, invalid encoded payload rejection, and preservation of the existing plain text path
- [ ] 4.2 Add no-hardware tests or local checks for text sink reset/read behavior where practical
- [ ] 4.3 Run `sleepwalker-protocol-check` to confirm protocol parity remains unchanged
- [ ] 4.4 Run `sleepwalker-apk-build` to confirm Android changes compile
- [ ] 4.5 Run the observer helper or observer package build check needed to confirm the text sink is included in the observer environment

## 5. Physical Bench Verification

- [ ] 5.1 Run the quick Hypothesis text identity scenario on `sleepwalker-hil/bench.toml`
- [ ] 5.2 Inspect the generated text identity summary and artifacts for profile, backend, generated examples, captured output, and failure-classification evidence
- [ ] 5.3 Run the existing fixed text smoke as a regression for event-sequence verification
- [ ] 5.4 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml` and verify keyboard, mouse, observer, and correlation evidence still pass
