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

