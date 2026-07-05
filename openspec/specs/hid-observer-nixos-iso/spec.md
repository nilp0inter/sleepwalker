## ADDED Requirements

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
The observer ISO SHALL configure input-device permissions and discovery so the HID observer helper can locate the ESP32-S3 keyboard event device by stable descriptor information rather than by unstable `/dev/input/eventX` numbering.

#### Scenario: ESP HID device discovered
- **WHEN** the ESP32-S3 enumerates as a USB keyboard on the observer host
- **THEN** the observer helper identifies the matching device and reports a structured device-found event

### Requirement: JSONL evdev reporting
The observer helper SHALL emit line-oriented structured events for Linux input events, including event type, code, value, timestamp, and matched device identity.

#### Scenario: Space key observed
- **WHEN** the ESP32-S3 emits a `USB_KEY_SPACE` key-down and key-up sequence
- **THEN** the observer helper emits JSONL events for `EV_KEY KEY_SPACE 1`, `EV_SYN SYN_REPORT`, `EV_KEY KEY_SPACE 0`, and `EV_SYN SYN_REPORT`

### Requirement: Exclusive input grab during tests
The observer helper SHALL support exclusive input grab during active tests to prevent injected HID events from affecting the sacrificial host console/session.

#### Scenario: Input grabbed for smoke test
- **WHEN** the keyboard smoke operation starts HID observation with exclusive grab enabled
- **THEN** matching HID events are captured by the observer helper and are not delivered to other userspace consumers during the active observation window
