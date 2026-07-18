## Purpose

Target-fixture conformance spec.

## Requirements

### Requirement: Versioned target-fixture control contract

The `editor-target-conformance` capability SHALL define a versioned target-fixture control contract that any instrumented target fixture SHALL implement. The contract SHALL expose operations for target description, deterministic reset, physical-input barrier, authoritative snapshot, health check, and shutdown. The contract version SHALL be recorded with every snapshot and control interaction so incompatible fixtures are rejected.

#### Scenario: Fixture exposes required operations

- **WHEN** a target fixture implements the control contract
- **THEN** it exposes target description, deterministic reset, physical-input barrier, authoritative snapshot, health check, and shutdown operations
- **AND** each operation records the contract version it was invoked under

#### Scenario: Incompatible fixture version rejected

- **WHEN** a conformance run encounters a fixture reporting an incompatible control contract version
- **THEN** the run rejects the fixture before issuing text commands and reports a structured version-mismatch failure

### Requirement: Target description operation

The fixture control contract SHALL expose a target description operation reporting the target environment identity, the supported character set, the supported editing mode, and any target-specific constraints. The description SHALL be inspectable before any text is sent so the conformance runner can select a matching target behavior package.

#### Scenario: Target description inspected before commands

- **WHEN** the conformance runner connects to a fixture
- **THEN** it retrieves the target description before issuing any text command
- **AND** the description reports environment identity, supported character set, supported editing mode, and target-specific constraints

### Requirement: Deterministic reset operation

The fixture control contract SHALL expose a deterministic reset operation that returns the target to a known empty state with an empty document, neutral caret, no active selection, and no residual editing state. The reset SHALL be deterministic across repeated calls so each generated example starts from the same baseline.

#### Scenario: Reset returns to empty known state

- **WHEN** the conformance runner invokes the fixture reset operation
- **THEN** the target returns to an empty document with neutral caret, no active selection, and no residual editing state
- **AND** repeated resets produce the same baseline state

### Requirement: Physical-input barrier operation

The fixture control contract SHALL expose a physical-input barrier operation that the fixture consumes to prove all preceding physical input has been delivered to the target before a snapshot is taken. The barrier SHALL be consumed by the fixture itself after preceding input, not inferred from evdev observation alone. The barrier SHALL use the reserved symbolic HID synchronization usage `USB_KEY_F24` so the fixture can detect it authoritatively.

#### Scenario: Barrier consumed by fixture

- **WHEN** the conformance runner sends the physical-input barrier after a sequence of HID operations
- **THEN** the fixture consumes the barrier and confirms all preceding physical input has been delivered to the target
- **AND** the barrier confirmation comes from the fixture itself, not from evdev inference alone

#### Scenario: Barrier not inferred from evdev

- **WHEN** a conformance run needs to confirm that preceding input has been consumed
- **THEN** it relies on the fixture consuming the physical-input barrier via `USB_KEY_F24`
- **AND** it does not treat the absence of pending evdev events as proof of consumption

### Requirement: Authoritative snapshot operation

The fixture control contract SHALL expose an authoritative snapshot operation that returns the current target document content, caret position, and hidden editing state as observed by the fixture. The snapshot SHALL be authoritative because it is read from the real target program, not from a shadow editor model. The snapshot SHALL include enough hidden editing state to verify that the target behavior package prediction matches the real target.

#### Scenario: Snapshot read from real target

- **WHEN** the conformance runner requests an authoritative snapshot after a reconciliation
- **THEN** the fixture returns the current document content, caret position, and hidden editing state read from the real target program
- **AND** the snapshot is not derived from a shadow editor model

#### Scenario: Hidden editing state exposed for verification

- **WHEN** the authoritative snapshot is taken
- **THEN** it includes the real target caret position and hidden editing state sufficient to compare against the target behavior package prediction

### Requirement: Health check operation

The fixture control contract SHALL expose a health check operation reporting whether the fixture target is alive, responsive, and in an expected baseline condition. The conformance runner SHALL use the health check before and during runs to detect fixture malfunction separately from semantic failures.

#### Scenario: Healthy fixture reports ready

- **WHEN** the conformance runner invokes the health check on a responsive fixture
- **THEN** the fixture reports alive, responsive, and baseline-condition status

#### Scenario: Unhealthy fixture reports malfunction

- **WHEN** the fixture target is unresponsive or in an unexpected condition
- **THEN** the health check reports a malfunction classification distinct from semantic reconciliation failure

### Requirement: Shutdown operation

The fixture control contract SHALL expose a shutdown operation that cleanly terminates the fixture target and releases observer resources. The conformance runner SHALL invoke shutdown at the end of every run and on early abort so no fixture target leaks across runs.

#### Scenario: Clean shutdown on run completion

- **WHEN** a conformance run completes or aborts
- **THEN** the runner invokes the fixture shutdown operation and the fixture target terminates cleanly

### Requirement: Non-grabbing observation for conformance

The editor-target-conformance runner SHALL NOT exclusively grab the keyboard evdev node during conformance runs. Normal HID input SHALL reach the Linux virtual terminal and the target fixture. A non-grabbing evdev observer MAY run only as diagnostics alongside the fixture and SHALL NOT be used as the authoritative consumption proof for the physical-input barrier.

#### Scenario: HID input reaches target fixture

- **WHEN** a conformance run sends HID operations to the target fixture
- **THEN** the keyboard evdev node is not exclusively grabbed and the HID input reaches the Linux virtual terminal and the target fixture

#### Scenario: Non-grabbing evdev is diagnostics only

- **WHEN** a conformance run collects evdev diagnostics
- **THEN** the evdev observer runs without exclusive grab
- **AND** its output is used only as diagnostics, not as the authoritative proof that the physical-input barrier was consumed

### Requirement: Model-based physical property testing

The `editor-target-conformance` capability SHALL provide a Hypothesis-driven physical conformance scenario that generates related complete-text snapshot sequences, calls `setText` repeatedly through the Editor, and compares the requested and predicted state with the authoritative fixture snapshot after every synchronized call. The scenario SHALL compare state per step, not only at the end of the sequence.

#### Scenario: Related snapshot sequences generated

- **WHEN** the conformance scenario runs
- **THEN** it generates related complete-text snapshot sequences using Hypothesis and invokes `setText` once per snapshot in sequence

#### Scenario: Per-step state comparison

- **WHEN** the scenario executes each `setText` call in a generated sequence
- **THEN** it compares the requested snapshot, the Editor predicted state, and the authoritative fixture snapshot after that single synchronized call before proceeding to the next snapshot

#### Scenario: Mismatch classified per step

- **WHEN** a per-step comparison detects a mismatch between predicted and observed state
- **THEN** the scenario records the failing step index, the requested snapshot, the predicted state, and the observed fixture state
- **AND** it classifies the mismatch as semantic or infrastructure according to the Editor failure classification

### Requirement: Replay artifacts for conformance

The conformance scenario SHALL preserve replay artifacts for every failing sequence, including the generated snapshot sequence, the per-step requested and predicted and observed states, the Editor plans, the host ABI version, the target package identity, the fixture contract version, and Hypothesis replay data. The artifacts SHALL be sufficient to reproduce the failing sequence without re-running Hypothesis generation.

#### Scenario: Failing sequence replayable

- **WHEN** a conformance sequence fails at a given step
- **THEN** the artifact directory preserves the full snapshot sequence, per-step states, Editor plans, ABI version, target package identity, fixture contract version, and Hypothesis replay data
- **AND** the artifacts are sufficient to reproduce the failing sequence without re-running Hypothesis generation

### Requirement: Conformance failure classification

The conformance scenario SHALL classify failures into semantic and infrastructure classes. Semantic failures are target behavior mismatches where the predicted state does not match the authoritative fixture state. Infrastructure failures are fixture malfunction, synchronization failure, transport failure, environment failure, and non-reproducible hardware failure. The scenario SHALL classify non-reproducible hardware failures separately from deterministic semantic counterexamples.

#### Scenario: Semantic mismatch classified

- **WHEN** the predicted state does not match the authoritative fixture state and the fixture is healthy
- **THEN** the scenario classifies the failure as a semantic reconciliation mismatch

#### Scenario: Non-reproducible hardware failure classified separately

- **WHEN** a failing sequence does not reproduce on replay and the fixture reports intermittent behavior
- **THEN** the scenario classifies the failure as a non-reproducible hardware or infrastructure instability rather than a semantic counterexample
