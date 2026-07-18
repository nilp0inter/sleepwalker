## Purpose
Sacrificial NixOS-based HID observer system configuration and helpers, enabling automated and secure verification of physical USB HID inputs.
## Requirements
### Requirement: Flake-built observer ISO
The project SHALL provide a flake-built bootable NixOS ISO for the sacrificial HID observer host. The ISO SHALL include a minimal system configuration for observing ESP32-S3 USB HID input events over SSH.

#### Scenario: Observer ISO artifact built
- **WHEN** the coding agent builds the observer ISO flake output
- **THEN** the result is a bootable NixOS ISO image containing the configured sleepwalker HID observer environment

### Requirement: Noninteractive SSH access
The observer ISO SHALL enable SSH access for a dedicated observer user using the harness host's configured public key material or documented local configuration. SSH access SHALL be usable by automation without password prompts during HIL runs.

#### Scenario: Agent reaches observer host
- **WHEN** the observer host is booted and network-reachable
- **THEN** the coding agent can run the HID observer helper over SSH without interactive login prompts

### Requirement: HID input permissions and discovery
The observer ISO SHALL configure input-device permissions and discovery so the HID observer helper can locate Sleepwalker keyboard and relative mouse event devices by stable descriptor and capability information rather than by unstable `/dev/input/eventX` numbering.

#### Scenario: Keyboard HID device discovered
- **WHEN** the ESP32-S3 enumerates as a Sleepwalker composite USB HID device with keyboard capability
- **THEN** the observer helper identifies the matching keyboard-capable event device and reports a structured device-found event

#### Scenario: Relative mouse HID device discovered
- **WHEN** the ESP32-S3 enumerates as a Sleepwalker composite USB HID device with relative mouse capability
- **THEN** the observer helper identifies the matching mouse-capable event device and reports a structured device-found event

#### Scenario: Combined node accepted
- **WHEN** the same event device exposes both keyboard and relative mouse capabilities for the Sleepwalker device
- **THEN** the observer helper accepts the device for both keyboard and mouse observation roles

### Requirement: JSONL evdev reporting
The observer helper SHALL emit line-oriented structured events for Linux input events, including event type, symbolic event name, numeric type/code/value, timestamp, and matched device identity. The helper SHALL decode Sleepwalker keyboard keys, mouse buttons, relative axes, and sync events into stable symbolic names.

#### Scenario: Space key observed
- **WHEN** the ESP32-S3 emits a `USB_KEY_SPACE` key-down and key-up sequence
- **THEN** the observer helper emits JSONL events for `EV_KEY KEY_SPACE 1`, `EV_SYN SYN_REPORT`, `EV_KEY KEY_SPACE 0`, and `EV_SYN SYN_REPORT`

#### Scenario: Mouse button observed
- **WHEN** the ESP32-S3 emits a left mouse button press and release
- **THEN** the observer helper emits JSONL events for `EV_KEY BTN_LEFT 1` and `EV_KEY BTN_LEFT 0`

#### Scenario: Relative movement observed
- **WHEN** the ESP32-S3 emits relative mouse movement
- **THEN** the observer helper emits JSONL events for `EV_REL REL_X` or `EV_REL REL_Y` with the observed relative value

### Requirement: Exclusive input grab during tests
The observer helper SHALL support exclusive input grab during active tests to prevent injected HID events from affecting the sacrificial host console/session.

#### Scenario: Input grabbed for smoke test
- **WHEN** the keyboard smoke operation starts HID observation with exclusive grab enabled
- **THEN** matching HID events are captured by the observer helper and are not delivered to other userspace consumers during the active observation window

### Requirement: Device enumeration preflight wait
The HID observer invocation SHALL wait for all requested device paths to appear on the observer host before opening them, within a bounded timeout, to avoid failing with ENOENT when USB HID enumeration has not yet completed.

#### Scenario: Observer waits for device paths
- **WHEN** the observer is invoked before the ESP32-S3 has finished USB enumeration
- **THEN** the observer polls for all device paths to exist before opening them, and proceeds once they appear

#### Scenario: Devices not present after timeout
- **WHEN** device paths do not appear within the bounded timeout
- **THEN** the observer reports a structured failure indicating devices were not present after preflight, rather than a silent ENOENT

### Requirement: Text smoke key decoding
The observer helper SHALL decode the keyboard keys needed by the high-level text smoke, including letters, digits, Shift, and existing space/sync events.

#### Scenario: Letter and digit observed
- **WHEN** the device emits `USB_KEY_A` or `USB_KEY_1`
- **THEN** the observer helper emits symbolic JSONL events for `KEY_A` or `KEY_1`

#### Scenario: Shift observed
- **WHEN** the device emits a Shift modifier press or release
- **THEN** the observer helper emits symbolic JSONL events for `KEY_LEFTSHIFT`

### Requirement: Linux console text sink
The observer ISO SHALL provide a raw-mode Linux console text sink that can act as the target output oracle for text identity HIL scenarios.

#### Scenario: Text sink captures rendered input
- **WHEN** the ESP32-S3 emits USB HID keyboard reports while the text sink owns the active Linux virtual console
- **THEN** the sink records the bytes delivered by the Linux console input path to an SSH-readable artifact

#### Scenario: Text sink runs in raw mode
- **WHEN** the text sink is active for an identity test
- **THEN** it disables canonical input buffering, echo, and terminal signal interpretation so printable text can be captured without shell or line-editing side effects

#### Scenario: Text sink reset isolates examples
- **WHEN** the HIL resets the text sink before a generated example
- **THEN** subsequent reads expose only text captured after that reset

### Requirement: Console keymap configuration for identity tests
The observer environment SHALL allow HIL automation to configure the Linux console keymap used by the text sink for supported identity-test profiles.

#### Scenario: Linux US console keymap selected
- **WHEN** the text identity scenario prepares the `linux:us` backend
- **THEN** the observer host is configured to use the Linux console US keymap before generated text is sent

### Requirement: Non-exclusive evdev diagnostics for text identity
The observer environment SHALL allow identity tests to collect evdev diagnostics without exclusively grabbing the Sleepwalker keyboard event device, so the Linux console text sink still receives injected input.

#### Scenario: Text identity observes without grab
- **WHEN** the text identity scenario starts optional evdev diagnostics
- **THEN** the observer helper is run without exclusive grab and injected HID events remain deliverable to the console text sink

#### Scenario: Existing grab smokes remain supported
- **WHEN** fixed keyboard, mouse, text, or composite smoke scenarios request exclusive observation
- **THEN** the observer helper still supports exclusive grab behavior for those scenarios


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

