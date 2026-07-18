## Purpose
Hardware-in-the-Loop (HIL) automation framework and smoke scenarios for physical Sleepwalker device validation, including Android ADB interfaces, firmware flashing, serial captures, and observer verification.
## Requirements
### Requirement: Relative mouse physical smoke scenario
The HIL SHALL provide an autonomous physical smoke scenario that injects a relative mouse command through the app/library/BLE/firmware path and verifies Linux evdev mouse events on the observer host.

#### Scenario: Mouse click observed
- **WHEN** HIL injects a left-button raw relative mouse report through the public command path
- **THEN** the observer emits JSONL events for `EV_KEY BTN_LEFT 1` and `EV_KEY BTN_LEFT 0`

#### Scenario: Mouse movement observed
- **WHEN** HIL injects a raw relative mouse report with non-zero relative movement
- **THEN** the observer emits JSONL events for `EV_REL REL_X` or `EV_REL REL_Y`

### Requirement: Library-driven command smoke
The HIL SHALL exercise at least one command path that uses public `sleepwalker-core` library behavior rather than private frame construction.

#### Scenario: Library path correlated
- **WHEN** HIL sends a library-driven keyboard or mouse command
- **THEN** Android diagnostics, ESP UART diagnostics, HID observer output, and smoke summary artifacts reference the same command sequence identifier

### Requirement: Keyboard regression remains required
The existing keyboard smoke scenario SHALL remain part of the validation path after adding relative mouse support.

#### Scenario: Keyboard still passes after mouse support
- **WHEN** relative mouse support is present
- **THEN** the keyboard smoke scenario still reports `KEY_SPACE` down/up evidence for `USB_KEY_SPACE`

### Requirement: Reserved opcode behavior covered
Protocol/HIL tests SHALL verify that reserved-but-unimplemented future absolute pointer and virtual serial opcodes fail with structured unsupported-opcode evidence rather than malformed behavior or unintended USB output.

#### Scenario: Reserved future opcode rejected safely
- **WHEN** a reserved future opcode is sent to firmware that does not implement that feature
- **THEN** no USB HID or CDC output is emitted and structured unsupported-opcode evidence is preserved

### Requirement: Mouse smoke artifacts
The mouse smoke operation SHALL write structured artifacts containing bench configuration, Android diagnostics, ESP UART diagnostics, HID observer JSONL, and a machine-readable summary.

#### Scenario: Mouse smoke preserves evidence
- **WHEN** the mouse smoke scenario passes or fails
- **THEN** the artifact directory contains enough structured evidence to identify the layer that emitted or rejected the mouse command

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

### Requirement: ESP32-S3 reset before smoke captures
The HIL SHALL reset the ESP32-S3 into a known-good state via UART RTS pulse before starting smoke captures, so that stale BLE/firmware state from a previous smoke does not cause command failures.

#### Scenario: Board reset before smoke
- **WHEN** a smoke operation begins on a commissioned bench
- **THEN** the ESP32-S3 is reset via RTS (EN) with DTR deasserted (GPIO0 high = normal boot) before UART capture and BLE commands start

### Requirement: BLE session readiness wait before arming
The HIL SHALL wait for BLE GATT session readiness before sending arm or inject commands, polling Android diagnostics for subscribe or services-discovered events within a bounded timeout.

#### Scenario: Commands wait for BLE connection
- **WHEN** a smoke operation sends a connect command
- **THEN** the operation waits for BLE subscribe or services_discovered evidence before proceeding to arm

#### Scenario: BLE not ready produces clear failure
- **WHEN** BLE does not reach subscribe or services_discovered within the bounded timeout
- **THEN** the smoke writes a failing summary identifying the connection layer as the failure point and does not send arm or inject commands

### Requirement: UART capture without board reset
The HIL UART capture SHALL open the serial port with DTR and RTS deasserted so that opening the port does not reset the ESP32-S3 and cause USB HID re-enumeration during observation.

#### Scenario: Serial open preserves HID enumeration
- **WHEN** the UART capture opens the ESP32-S3 serial port
- **THEN** DTR and RTS remain deasserted and the observer host's USB HID devices stay enumerated throughout the capture window

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

### Requirement: Text batch HIL verification
The HIL text smoke scenario SHALL verify keyboard event sequence and timing when streaming text using the batched tap script path, including shifted repeated-key input that would expose release-gap coalescing.

#### Scenario: Batched text smoke passes
- **WHEN** the HIL text smoke scenario runs using the batched tap script path for `aA1`
- **THEN** it verifies the `KEY_A`, `KEY_LEFTSHIFT`, and `KEY_1` event sequence on the observer host and confirms the execution duration is within the expected speed envelope

### Requirement: Property-based text identity HIL scenario
The HIL SHALL provide a Hypothesis-driven text identity scenario that sends generated supported text through the direct ADB command path, Android companion app, `sleepwalker-core`, BLE transport, ESP32-S3 firmware, and USB HID output, then verifies that the observer host captures the same text as target output.

#### Scenario: Generated Linux US text renders identically
- **WHEN** the property identity scenario generates a printable text string from the `linux:us` seed identity alphabet and sends it through the direct ADB text command path
- **THEN** the observer host's Linux console text sink captures exactly the same text bytes as the generated input

#### Scenario: Unsupported identity characters excluded
- **WHEN** the property identity scenario builds its initial `linux:us` generation alphabet
- **THEN** it includes printable seed-US text characters and excludes ESC and other control-key behaviors that do not have stable text-output identity semantics

### Requirement: Session-scoped property execution
The HIL property identity scenario SHALL amortize expensive bench setup across generated examples rather than resetting, reconnecting, or rebuilding the entire bench loop for each example.

#### Scenario: Setup shared across examples
- **WHEN** the quick identity profile starts on a commissioned bench
- **THEN** it validates bench configuration, resets the ESP32-S3, starts captures, prepares the observer text sink, connects BLE, and arms the firmware once before running generated examples

#### Scenario: Each generated example isolated
- **WHEN** a generated text example is executed inside the property run
- **THEN** the HIL resets the observer text sink before sending the example and compares only the output captured for that example

### Requirement: Quick and deep identity profiles
The HIL SHALL expose bounded quick and deep execution profiles for text identity testing. The quick profile SHALL be suitable for end-of-iteration verification on the commissioned bench, while the deep profile SHALL allow larger manual sweeps.

#### Scenario: Quick profile bounded
- **WHEN** the quick text identity profile runs
- **THEN** it uses bounded Hypothesis settings for example count and string length and writes those settings to the summary artifact

#### Scenario: Deep profile available
- **WHEN** a caller requests the deep text identity profile
- **THEN** the HIL runs the same identity property with larger configured bounds and the same artifact schema

### Requirement: Text identity failure artifacts
The HIL text identity scenario SHALL preserve enough structured evidence to classify and replay generated E2E text failures.

#### Scenario: Passing summary includes property metadata
- **WHEN** the text identity scenario passes
- **THEN** its summary records the profile, backend, alphabet, Hypothesis settings, generated example count, seed when available, duration, and artifact paths

#### Scenario: Failing example preserves layer evidence
- **WHEN** a generated text identity example fails
- **THEN** the artifact directory preserves generated input, Android-received input metadata, captured target output, failure classification, Hypothesis replay data, Android diagnostics, ESP UART diagnostics, and non-grabbing evdev diagnostics when available

#### Scenario: Flaky hardware failure classified separately
- **WHEN** a generated text identity example fails once but replaying the same example does not reproduce the failure
- **THEN** the scenario reports an infrastructure or timing instability classification rather than presenting the example as a deterministic text identity counterexample


### Requirement: Editor snapshot-sequence property HIL scenario
The HIL SHALL provide a Hypothesis-driven Editor conformance scenario that generates related complete-text snapshot sequences, calls `setText` repeatedly through the Editor over the full app/library/BLE/firmware/USB path, and compares requested, predicted, and authoritative fixture state after every synchronized call. The scenario SHALL use the Readline fixture as the authoritative target and SHALL compare state per step, not only at the end of the sequence.

#### Scenario: Related snapshot sequences generated
- **WHEN** the Editor conformance scenario runs on a commissioned bench
- **THEN** it generates related complete-text snapshot sequences using Hypothesis and invokes `setText` once per snapshot in sequence through the direct ADB Editor command path

#### Scenario: Per-step state comparison
- **WHEN** the scenario executes each `setText` call in a generated sequence
- **THEN** it compares the requested snapshot, the Editor predicted state, and the authoritative Readline fixture snapshot after that single synchronized call before proceeding to the next snapshot

#### Scenario: Full path exercised
- **WHEN** the Editor conformance scenario runs
- **THEN** each `setText` call traverses the ADB command path, Android companion, `sleepwalker-core` Editor, BLE transport, ESP32-S3 firmware, USB HID, and the Readline fixture on the observer host

### Requirement: Synchronization barrier usage in HIL
The HIL SHALL send the reserved symbolic HID synchronization usage `USB_KEY_F24` after each Editor reconciliation plan and SHALL wait for the Readline fixture to consume the barrier before taking an authoritative snapshot. The HIL SHALL NOT rely on fixed sleeps or evdev event absence as proof of input consumption. The barrier SHALL be consumed by the fixture itself.

#### Scenario: Barrier sent before snapshot
- **WHEN** the HIL completes an Editor reconciliation plan for a snapshot step
- **THEN** it sends the `USB_KEY_F24` key sequence and waits for the Readline fixture to confirm consumption before taking the authoritative snapshot

#### Scenario: No fixed sleep for input consumption
- **WHEN** the HIL waits for preceding input to be consumed
- **THEN** it waits on the fixture barrier confirmation and does not use a fixed sleep to infer consumption

### Requirement: Non-grabbing observation for Editor conformance
The HIL Editor conformance scenario SHALL NOT exclusively grab the keyboard evdev node during conformance runs. Normal HID input SHALL reach the Linux virtual terminal and the Readline fixture. A non-grabbing evdev observer MAY run only as diagnostics and SHALL NOT be used as the authoritative consumption proof for the physical-input barrier.

#### Scenario: Editor conformance does not grab
- **WHEN** the Editor conformance scenario starts observation
- **THEN** the keyboard evdev node is not exclusively grabbed and HID input reaches the Linux virtual terminal and the Readline fixture

#### Scenario: Non-grabbing evdev is diagnostics only
- **WHEN** the Editor conformance scenario collects evdev diagnostics
- **THEN** the evdev observer runs without exclusive grab and its output is used only as diagnostics, not as authoritative barrier consumption proof

### Requirement: Per-step target-state comparison artifacts
The HIL Editor conformance scenario SHALL write per-step comparison artifacts recording, for each snapshot in a generated sequence, the requested snapshot, the Editor predicted state, the authoritative fixture observed state, and the match outcome. The artifacts SHALL be sufficient to identify the exact step where a mismatch occurred.

#### Scenario: Per-step comparison recorded
- **WHEN** the Editor conformance scenario executes a generated sequence
- **THEN** it records the requested snapshot, predicted state, observed fixture state, and match outcome for each step

#### Scenario: Mismatch step identified
- **WHEN** a per-step comparison detects a mismatch
- **THEN** the artifact directory identifies the failing step index and preserves the requested, predicted, and observed states for that step

### Requirement: Editor replay artifacts
The HIL Editor conformance scenario SHALL preserve replay artifacts for every failing sequence, including the generated snapshot sequence, per-step requested and predicted and observed states, Editor plans, host ABI version, target package identity, fixture control contract version, Android diagnostics, ESP UART diagnostics, non-grabbing evdev diagnostics, and Hypothesis replay data. The artifacts SHALL be sufficient to reproduce the failing sequence without re-running Hypothesis generation.

#### Scenario: Failing sequence replayable
- **WHEN** an Editor conformance sequence fails at a given step
- **THEN** the artifact directory preserves the full snapshot sequence, per-step states, Editor plans, ABI version, target package identity, fixture contract version, and Android diagnostics, ESP UART diagnostics, non-grabbing evdev diagnostics, and Hypothesis replay data
- **AND** the artifacts are sufficient to reproduce the failing sequence without re-running Hypothesis generation

### Requirement: Editor failure classification
The HIL Editor conformance scenario SHALL classify failures into semantic and infrastructure classes. Semantic failures are target behavior mismatches where the Editor predicted state does not match the authoritative fixture state while the fixture is healthy. Infrastructure failures include fixture malfunction, synchronization failure, transport failure, environment failure, and non-reproducible hardware failure. Non-reproducible hardware failures SHALL be classified separately from deterministic semantic counterexamples.

#### Scenario: Semantic mismatch classified
- **WHEN** the Editor predicted state does not match the authoritative fixture state and the fixture is healthy
- **THEN** the scenario classifies the failure as a semantic reconciliation mismatch

#### Scenario: Infrastructure failure classified separately
- **WHEN** an Editor conformance step fails due to fixture malfunction, synchronization, transport, environment, or hardware cause
- **THEN** the scenario classifies the failure under the matching infrastructure class rather than as a semantic counterexample

#### Scenario: Non-reproducible hardware failure classified separately
- **WHEN** a failing Editor sequence does not reproduce on replay and the fixture reports intermittent behavior
- **THEN** the scenario classifies the failure as a non-reproducible hardware or infrastructure instability rather than a semantic counterexample

### Requirement: Session-scoped Editor conformance execution
The HIL Editor conformance scenario SHALL amortize expensive bench setup across generated snapshot sequences rather than resetting, reconnecting, or rebuilding the entire bench loop for each example. The scenario SHALL reset the Readline fixture before each generated sequence and SHALL synchronize per step within the sequence.

#### Scenario: Setup shared across sequences
- **WHEN** the Editor conformance scenario starts on a commissioned bench
- **THEN** it validates bench configuration, resets the ESP32-S3, starts captures, prepares the Readline fixture, connects BLE, and arms the firmware once before running generated sequences

#### Scenario: Fixture reset before each sequence
- **WHEN** a generated snapshot sequence begins
- **THEN** the HIL resets the Readline fixture to empty known state before sending the first `setText` of that sequence

### Requirement: Existing text identity scenario preserved
The existing property-based text identity HIL scenario SHALL remain available and unchanged after adding Editor conformance. The append-only text identity scenario and the Editor conformance scenario SHALL coexist as separate HIL surfaces.

#### Scenario: Text identity scenario still passes
- **WHEN** Editor conformance support is present
- **THEN** the existing text identity property scenario still runs and reports generated text identity evidence unchanged


### Requirement: Explicit Editor F24 conformance policy
The Editor conformance HIL SHALL configure F24 reservation explicitly for the conformance run. Under that policy, the conformance Editor plan compiler SHALL reject symbolic F24 actions from the package so the separately injected physical F24 synchronization barrier remains authoritative; the policy SHALL be recorded in artifacts.

#### Scenario: Conformance plan reserves F24
- **WHEN** a conformance-run package plan contains a symbolic F24 action
- **THEN** the compiler rejects the plan before execution and the artifact records that F24 reservation policy was active

### Requirement: Conformance barrier remains fixture-authoritative
After each successfully completed Editor plan in a conformance run, HIL SHALL inject the physical F24 barrier using the existing low-level keyboard path and wait for the fixture to acknowledge consumption before obtaining the authoritative rendered-text snapshot. HIL SHALL not infer consumption from fixed delays or from event absence.

#### Scenario: Barrier precedes authoritative snapshot
- **WHEN** a conformance Editor request completes execution
- **THEN** HIL waits for fixture acknowledgement of the separately injected F24 barrier before recording the authoritative snapshot

### Requirement: ABI conformance and replay artifacts
Editor conformance artifacts SHALL preserve, for every request, current and desired rendered text, opaque input and output state, symbolic actions, compiled low-level operations, package identity, host ABI version, keyboard-layout identity, text-cost metric identity, F24 reservation policy, execution outcome, and authoritative fixture result. Failing sequences SHALL additionally preserve sequence ordering and replay data sufficient to rerun the same ABI inputs without regenerating them.

#### Scenario: Failing ABI sequence is replayable
- **WHEN** an Editor conformance sequence fails
- **THEN** its artifacts identify the failing request and preserve its opaque state values, symbolic actions, compiled operations, package/ABI identity, layout/metric identities, policy, fixture result, and replay sequence data

### Requirement: Conformance validates rendered text without decoding state
The HIL conformance oracle SHALL compare requested and authoritative rendered text and may retain opaque package-state values for replay, but SHALL not decode or assert caret, cursor, selection, mode, or any target-specific field within opaque state.

#### Scenario: Opaque state remains opaque to HIL
- **WHEN** a conformance artifact contains a package state value
- **THEN** HIL records and replays the value as constrained opaque data while determining pass or failure from rendered-text and execution evidence rather than named editor-state fields
