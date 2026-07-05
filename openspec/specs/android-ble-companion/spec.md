## ADDED Requirements

### Requirement: ADB command intake surface
The Android companion app SHALL expose an ADB-friendly command surface for agent-driven operation. Commands SHALL be accepted through explicit package/component targeting and translated into service-owned BLE operations.

#### Scenario: Keyboard command received from ADB
- **WHEN** the harness host sends an explicit ADB command to inject `USB_KEY_SPACE`
- **THEN** the Android app records a structured diagnostic event and passes the command to the BLE service path

### Requirement: Service-owned BLE connection
The Android app SHALL use a service-owned BLE connection/session so command execution is not performed directly inside a short-lived broadcast receiver callback.

#### Scenario: Receiver delegates long-running work
- **WHEN** an ADB broadcast or shell command requests BLE connect, arm, or key injection
- **THEN** the receiver parses the request quickly and delegates BLE work to the app service

### Requirement: Core protocol and BLE library
The `sleepwalker-core` library SHALL own command frame encoding, CRC insertion, symbolic HID usage mapping, BLE UUID constants, negotiated-MTU-aware writes, and status notification parsing.

#### Scenario: Core encodes symbolic key
- **WHEN** the app service requests injection of `USB_KEY_SPACE`
- **THEN** `sleepwalker-core` creates a sequenced protocol frame for the canonical USB HID usage

### Requirement: BLE bonding and permission accounting
The Android companion SHALL account for BLE runtime permissions and bonding state. First-time manual pairing MAY be treated as commissioning, but normal regression commands SHALL detect missing permission or bond state and report structured failures.

#### Scenario: Missing bond reported
- **WHEN** a regression command requires a bonded ESP32-S3 but no usable bond exists
- **THEN** the app reports a structured missing-bond failure instead of silently timing out

### Requirement: Structured Android diagnostics
The Android companion SHALL emit structured logcat diagnostics for ADB command intake, BLE connection state, frame writes, status notifications, and failures.

#### Scenario: BLE ACK logged
- **WHEN** the ESP32-S3 notifies a status acknowledgement for a command sequence
- **THEN** Android logcat contains a structured event with the same sequence identifier and acknowledgement status
