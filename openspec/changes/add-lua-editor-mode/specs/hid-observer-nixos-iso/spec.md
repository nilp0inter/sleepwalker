## ADDED Requirements

### Requirement: GNU Readline target fixture in observer environment

The observer ISO SHALL include a reproducible GNU Readline target fixture built into the sacrificial observer environment. The fixture SHALL run a real GNU Readline instance in pinned Emacs mode on a Linux virtual terminal and SHALL expose its authoritative line buffer and hidden editing state without implementing a shadow editor. The fixture SHALL accept ASCII single-line input only for this change.

#### Scenario: Readline fixture built into observer ISO

- **WHEN** the observer ISO is built
- **THEN** the resulting image contains a reproducible GNU Readline target fixture running in pinned Emacs mode on a Linux virtual terminal

#### Scenario: Authoritative line buffer exposed without shadow editor

- **WHEN** the fixture exposes its state after input
- **THEN** it reports the authoritative line buffer and hidden editing state read from the real GNU Readline instance
- **AND** the state is not derived from a shadow editor model

### Requirement: Versioned fixture control surface

The observer ISO SHALL provide an isolated control surface for target fixtures implementing the versioned target-fixture control contract, including target description, deterministic reset, physical-input barrier, authoritative snapshot, health check, and shutdown operations. The control surface SHALL be reachable over SSH by HIL automation without interactive login prompts.

#### Scenario: Fixture control surface reachable

- **WHEN** HIL automation connects to the observer host over SSH
- **THEN** it can invoke target description, deterministic reset, physical-input barrier, authoritative snapshot, health check, and shutdown operations on the Readline fixture

#### Scenario: Fixture control contract version recorded

- **WHEN** the control surface responds to a fixture operation
- **THEN** the response records the target-fixture control contract version under which the operation was invoked

### Requirement: Physical-input barrier consumed by Readline fixture

The Readline fixture SHALL consume the reserved symbolic HID synchronization usage `USB_KEY_F24` as a physical-input barrier. The fixture SHALL detect the `USB_KEY_F24` key in its Readline input stream and SHALL confirm that all preceding physical input has been consumed before taking an authoritative snapshot. The barrier SHALL be consumed by the fixture itself, not inferred from evdev observation alone.

#### Scenario: Synchronization key detected by Readline

- **WHEN** the ESP32-S3 emits the `USB_KEY_F24` key sequence into the Readline fixture
- **THEN** the fixture detects the synchronization key and confirms preceding input consumption before snapshotting

#### Scenario: Barrier not inferred from evdev

- **WHEN** the fixture confirms preceding input consumption
- **THEN** the confirmation comes from the Readline fixture consuming the `USB_KEY_F24` key
- **AND** it does not rely on evdev event absence as proof of consumption

### Requirement: Non-grabbing evdev for Readline conformance

The observer environment SHALL allow Readline conformance runs to collect evdev diagnostics without exclusively grabbing the Sleepwalker keyboard event device, so normal HID input reaches the Linux virtual terminal and the Readline fixture. Non-grabbing evdev observation SHALL be diagnostics only and SHALL NOT be used as the authoritative consumption proof for the physical-input barrier. Existing exclusive-grab smoke scenarios SHALL remain supported.

#### Scenario: Readline conformance observes without grab

- **WHEN** a Readline conformance run collects evdev diagnostics
- **THEN** the observer helper is run without exclusive grab and injected HID events remain deliverable to the Linux virtual terminal and the Readline fixture

#### Scenario: Existing grab smokes remain supported

- **WHEN** fixed keyboard, mouse, text, or composite smoke scenarios request exclusive observation
- **THEN** the observer helper still supports exclusive grab behavior for those scenarios

### Requirement: Readline fixture deterministic reset

The Readline fixture control surface SHALL expose a deterministic reset operation that returns GNU Readline to an empty line buffer with a neutral cursor, no active selection, and no residual editing state. The reset SHALL be deterministic across repeated calls so each generated snapshot sequence starts from the same baseline.

#### Scenario: Readline reset returns to empty line

- **WHEN** the HIL invokes the Readline fixture reset operation
- **THEN** GNU Readline returns to an empty line buffer with neutral cursor, no active selection, and no residual editing state
- **AND** repeated resets produce the same baseline state

### Requirement: Readline authoritative snapshot

The Readline fixture control surface SHALL expose an authoritative snapshot operation returning the current line buffer content, cursor position, and hidden Readline editing state. The snapshot SHALL be authoritative because it is read from the real GNU Readline instance, not from a shadow editor model.

#### Scenario: Snapshot read from real Readline

- **WHEN** the HIL requests an authoritative snapshot after a reconciliation
- **THEN** the fixture returns the line buffer content, cursor position, and hidden Readline editing state read from the real GNU Readline instance
- **AND** the snapshot is not derived from a shadow editor model