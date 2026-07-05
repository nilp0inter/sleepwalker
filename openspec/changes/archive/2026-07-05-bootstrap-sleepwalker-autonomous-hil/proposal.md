## Why

`sleepwalker` needs a from-scratch foundation for autonomous hardware-in-the-loop iteration across firmware, Android BLE control, physical USB HID output, and a reproducible Nix lab environment. The coding agent is the harness/orchestrator, so the project must expose deterministic, machine-readable operations rather than rely on opaque manual workflows.

## What Changes

- Add a monorepo topology for ESP32-S3 firmware, Android companion app/library, shared protocol contracts, and collision-resistant HIL tooling under `sleepwalker-hil/`.
- Add Nix flake outputs for deterministic firmware builds, Android builds, agent-callable operations, and a bootable sacrificial NixOS HID observer ISO.
- Add an ESP32-S3 firmware architecture using BLE-only custom UART GATT, a thread-safe HID command queue, TinyUSB keyboard/mouse output, structured UART logs, and safety state transitions.
- Add a Kotlin Android architecture with a pure-ish `sleepwalker-core` protocol/BLE layer and an ADB-driven `sleepwalker-app` command surface backed by a service-owned BLE connection.
- Add a shared protocol contract for framed commands, opcodes, ACK/status notifications, symbolic keyboard usages, CRC/error handling, and structured event correlation.
- Add an agent-operated HIL workflow where the coding agent builds, flashes, installs, drives ADB commands, reads ESP UART logs, controls the remote HID observer over SSH, invokes `noti` for explicit human gates, and verifies physical keyboard events.
- Scope the first physical end-to-end scenario to keyboard-only injection: ADB command → Android BLE frame → ESP32-S3 queue/TinyUSB → remote NixOS evdev `KEY_SPACE` down/up observation.

## Capabilities

### New Capabilities
- `shared-hid-protocol`: Defines the command frame, opcodes, status/ACK model, symbolic HID usage mapping, and cross-layer event correlation rules.
- `esp32-s3-hid-firmware`: Defines ESP32-S3 firmware behavior for BLE RX, queue-mediated HID dispatch, TinyUSB keyboard output, safety state, and structured UART diagnostics.
- `android-ble-companion`: Defines Android library/app behavior for ADB command intake, BLE connection/session management, command framing, ACK handling, permissions, and structured logcat diagnostics.
- `agent-operated-hil`: Defines the coding-agent-operated hardware loop, collision-resistant flake app surfaces, bench configuration, artifacts, human `noti` gates, and first keyboard smoke scenario.
- `hid-observer-nixos-iso`: Defines the sacrificial NixOS HID observer ISO, SSH access, input-device permissions, HID observer helper behavior, and JSONL evdev reporting.

### Modified Capabilities

None. No existing OpenSpec capabilities are present.

## Impact

- Adds the initial project layout for firmware, Android, protocol, `sleepwalker-hil/`, Nix support, and hardware documentation.
- Introduces ESP-IDF, TinyUSB, NimBLE, Android Gradle/Kotlin, Android SDK/ADB, and NixOS ISO build dependencies through the flake.
- Establishes physical bench expectations: harness host with Android over USB/ADB and ESP UART over USB-to-TTL, sacrificial NixOS HID observer host over SSH, ESP32-S3 native USB connected to the observer host, and optional human commissioning through `noti`.
- Establishes machine-readable diagnostics as a project contract: ESP UART JSONL, Android logcat JSONL, HID observer JSONL, and summary artifacts.
- Does not implement mouse E2E verification in the first slice; mouse support remains architecturally reserved after keyboard E2E is stable.
