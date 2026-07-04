## ADDED Requirements

### Requirement: Agent-callable flake operations
The project SHALL expose collision-resistant flake apps or commands for the coding agent to build firmware, flash firmware, monitor ESP UART, build the Android APK, install the APK, issue Android commands, start HID observation, invoke human gates, and run the first keyboard smoke scenario.

#### Scenario: Agent invokes keyboard smoke
- **WHEN** the coding agent runs the keyboard smoke operation with a bench configuration
- **THEN** the operation uses project-provided tools and emits machine-readable artifacts without requiring ad-hoc global tools

### Requirement: Bench configuration contract
The project SHALL define a bench configuration format that identifies the Android ADB serial, ESP UART/flash port, sacrificial HID observer SSH target, observer device matching criteria, and artifact output directory.

#### Scenario: Missing bench field rejected
- **WHEN** a required bench configuration field is absent
- **THEN** the HIL operation fails before touching hardware and reports the missing field

### Requirement: Human gates through noti
The project SHALL provide an explicit human gate mechanism that rings the AFK human through the harness host `noti` command, gives exact physical instructions, and waits for an observable condition before continuing.

#### Scenario: Manual BLE pairing gate
- **WHEN** commissioning requires the human to accept a BLE pairing prompt on the Android device
- **THEN** the human gate sends a `noti` message and waits for Android bond state to become observable

#### Scenario: Regression avoids human dependency
- **WHEN** a normal regression smoke test encounters a missing manual prerequisite
- **THEN** it fails with structured evidence instead of silently waiting for human action

### Requirement: Keyboard-only first physical smoke scenario
The first E2E HIL scenario SHALL inject `USB_KEY_SPACE` through ADB, drive BLE to the ESP32-S3, require firmware queue/TinyUSB execution, and verify `KEY_SPACE` down/up events on the remote NixOS observer host.

#### Scenario: Keyboard smoke passes
- **WHEN** Android, BLE, ESP firmware, TinyUSB, SSH, and HID observation all work for `USB_KEY_SPACE`
- **THEN** the smoke summary reports success with correlated Android, ESP UART, and HID observer evidence

### Requirement: Structured HIL artifacts
Each HIL smoke operation SHALL write an artifact directory containing bench configuration, command logs, Android logcat diagnostics, ESP UART diagnostics, HID observer JSONL, and a machine-readable summary.

#### Scenario: Failed smoke preserves evidence
- **WHEN** the keyboard smoke scenario fails at any layer
- **THEN** the artifact directory contains enough structured evidence to identify the failing layer without rerunning the test
