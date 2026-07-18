## ADDED Requirements

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
- **THEN** the artifact directory preserves the full snapshot sequence, per-step states, Editor plans, ABI version, target package identity, fixture contract version, Android diagnostics, ESP UART diagnostics, non-grabbing evdev diagnostics, and Hypothesis replay data
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