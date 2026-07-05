## ADDED Requirements

### Requirement: Reference app delegates to library
The Android reference app SHALL demonstrate and exercise public `sleepwalker-core` library behavior. It SHALL NOT own protocol encoding, keymap rendering, or HID semantic logic that belongs in the reusable library.

#### Scenario: App injects key through library
- **WHEN** the reference app or ADB command path requests a key injection
- **THEN** the command is encoded through `sleepwalker-core` library behavior rather than app-local protocol construction

### Requirement: ADB command path uses public surfaces
ADB-driven commands SHALL exercise the same library/session behavior available to normal library consumers.

#### Scenario: ADB mouse command uses library path
- **WHEN** HIL sends an ADB command for relative mouse movement or button click
- **THEN** the app delegates to the public library/session path and records structured diagnostics for that command sequence

### Requirement: Single BLE session ownership
BLE scan, connect, GATT write, MTU handling, and status notification parsing SHALL be owned by one session/service path rather than duplicated across app entry points.

#### Scenario: Receiver delegates long-running BLE work
- **WHEN** the ADB receiver receives a command requiring BLE I/O
- **THEN** it delegates to the service/session owner instead of performing independent scan/connect/write logic

### Requirement: Reference app exposes capability demos
The reference app SHALL expose demo/debug surfaces for connection, arm/disarm/kill, low-level keyboard, low-level relative mouse, and high-level text planning once supported by the library.

#### Scenario: Mouse demo command available
- **WHEN** the app is installed for HIL or manual demonstration
- **THEN** a caller can request a relative mouse click or movement through an explicit command surface
