## 1. Repository and Nix Foundation

- [x] 1.1 Create the monorepo directories for `firmware/`, `android/`, `protocol/`, `sleepwalker-hil/`, `hardware/`, and `nix/`
- [ ] 1.2 Add a root flake with pinned inputs for nixpkgs, ESP-IDF tooling, Android SDK/JDK tooling, and project helper packages
- [ ] 1.3 Define collision-resistant flake app/package names using the `sleepwalker-*` prefix
- [ ] 1.4 Add a default development shell containing ESP-IDF build tools, Android build tools, ADB, serial tooling, SSH tooling, and project helper binaries
- [ ] 1.5 Add `sleepwalker-hil/bench.example.toml` with fields for Android ADB serial, ESP UART/flash port, HID observer SSH target, HID match criteria, and artifact directory

## 2. Shared Protocol Contract

- [ ] 2.1 Define the versioned command frame layout, sequence identifier, opcode field, payload length, payload, and CRC-32 field under `protocol/`
- [ ] 2.2 Define symbolic HID usages and the canonical `USB_KEY_SPACE` mapping to USB usage `0x2c` and Linux evdev `KEY_SPACE`
- [ ] 2.3 Define ACK/status values for received, queued, sent-to-USB, malformed frame, bad CRC, disarmed, queue full, USB not mounted, and unsupported opcode
- [ ] 2.4 Add protocol golden-frame fixtures for valid `USB_KEY_SPACE`, bad CRC, unsupported opcode, arm, disarm, kill, and release-all cases
- [ ] 2.5 Add protocol tests or fixture checks that can be run without hardware

## 3. ESP32-S3 Firmware Skeleton

- [ ] 3.1 Create the ESP-IDF project skeleton with `firmware/CMakeLists.txt`, `sdkconfig.defaults`, `main/`, and component directories
- [ ] 3.2 Implement the `hid_bridge` component with fixed-size queue items, sequence identifiers, command types, and queue-full reporting
- [ ] 3.3 Implement the `safety` component with DISARMED, ARMED, KILLED, timeout, BLE disconnect, and release-all transitions
- [ ] 3.4 Implement the `ble_uart` component with BLE-only custom UART GATT RX/TX characteristics and bounded RX handling
- [ ] 3.5 Wire BLE RX handling to protocol validation, structured status notification, and `hid_bridge` enqueue without TinyUSB calls from the BLE handler
- [ ] 3.6 Implement the `usb_hid` component with TinyUSB keyboard descriptor/report support for `USB_KEY_SPACE` key-down and release reports
- [ ] 3.7 Implement the HID worker task that owns TinyUSB report emission, applies safety checks, and reports command lifecycle status
- [ ] 3.8 Emit structured auxiliary UART diagnostics for boot, BLE, safety, bridge, USB HID, and command lifecycle events
- [ ] 3.9 Add firmware build checks through the flake without requiring hardware

## 4. Android Companion and Core Library

- [ ] 4.1 Create the Android Gradle workspace with `sleepwalker-core` and `sleepwalker-app` modules
- [ ] 4.2 Implement `sleepwalker-core` frame encoding, CRC insertion, symbolic HID mapping, opcode/status constants, and fixture compatibility with `protocol/`
- [ ] 4.3 Implement `sleepwalker-core` BLE UUID constants, MTU-aware write sizing, and status notification parsing
- [ ] 4.4 Add Android BLE permissions and manifest entries required for the physical Android test device
- [ ] 4.5 Implement an explicit ADB command intake surface for connect, status, arm, inject `USB_KEY_SPACE`, release-all, kill, and disconnect
- [ ] 4.6 Delegate command work from the ADB-facing receiver/shell surface to a service-owned BLE connection/session
- [ ] 4.7 Emit structured logcat diagnostics for ADB command intake, BLE state, frame writes, ACK/status notifications, and failures
- [ ] 4.8 Add Android unit/build checks through the flake without requiring physical BLE hardware

## 5. Sacrificial NixOS HID Observer ISO

- [ ] 5.1 Add a NixOS configuration for the sacrificial `sleepwalker-hid-observer` host
- [ ] 5.2 Expose a flake output that builds the bootable `sleepwalker-hid-observer-iso`
- [ ] 5.3 Configure the ISO with SSH enabled, a dedicated observer user, noninteractive key-based access, and a collision-resistant hostname
- [ ] 5.4 Add udev/input permissions for stable discovery of the ESP32-S3 HID keyboard by descriptor information rather than `/dev/input/eventX`
- [ ] 5.5 Implement and package the `sleepwalker-hid-observer` helper that emits JSONL evdev events with device identity and timestamps
- [ ] 5.6 Add exclusive input grab support to the HID observer helper for active smoke tests
- [ ] 5.7 Add an observer ISO build check through the flake

## 6. Agent-Operated HIL Primitives

- [ ] 6.1 Implement bench configuration parsing and validation with structured missing-field errors
- [ ] 6.2 Add `sleepwalker-fw-build`, `sleepwalker-fw-flash`, and `sleepwalker-fw-uart` operations
- [ ] 6.3 Add `sleepwalker-apk-build`, `sleepwalker-apk-install`, and Android log capture operations
- [ ] 6.4 Add `sleepwalker-adb-status`, `sleepwalker-adb-connect`, `sleepwalker-adb-arm`, `sleepwalker-adb-inject-key`, `sleepwalker-adb-release-all`, and `sleepwalker-adb-kill` operations
- [ ] 6.5 Add `sleepwalker-hid-observe` to start the remote observer helper over SSH and collect JSONL HID events
- [ ] 6.6 Add `sleepwalker-human-gate` to call `noti` with exact physical instructions and wait for observable commissioning/recovery conditions
- [ ] 6.7 Add artifact collection for bench config, command logs, Android logcat JSONL, ESP UART JSONL, HID observer JSONL, and summary JSON
- [ ] 6.8 Add `sleepwalker-smoke-keyboard` as a composed keyboard-only smoke operation using the smaller primitives

## 7. Verification and Commissioning Evidence

- [ ] 7.1 Verify protocol fixture checks pass without hardware
- [ ] 7.2 Verify firmware builds through the flake
- [ ] 7.3 Verify Android APK builds through the flake
- [ ] 7.4 Verify the HID observer ISO builds through the flake
- [ ] 7.5 Verify bench config validation rejects incomplete configs before touching hardware
- [ ] 7.6 Verify commissioning-mode human gates call `noti` and wait for observable state rather than chat confirmation
- [ ] 7.7 Verify the first physical keyboard smoke scenario observes correlated Android, ESP UART, and remote HID `KEY_SPACE` down/up evidence
