## Context

The repository currently contains OpenSpec scaffolding but no firmware, Android, protocol, Nix, or HIL implementation trees. The target system is a from-scratch monorepo for agent-operated autonomous iteration against physical hardware:

- The coding agent running on the harness Linux host is the orchestration harness.
- The harness host builds with Nix, controls a physical Android device over USB/ADB, and reaches the ESP32-S3 auxiliary UART through USB-to-TTL.
- The Android device is the BLE central and drives the ESP32-S3 over a BLE-only custom UART GATT service.
- The ESP32-S3 is the BLE peripheral and native USB keyboard/mouse HID device.
- A sacrificial NixOS host observes real USB HID events from the ESP32-S3 over native USB and is controlled by SSH.
- The flake must also build the sacrificial observer host ISO.
- One-time human commissioning is acceptable, but normal regression should be autonomous. Unavoidable human gates must use the already-configured `noti` command and wait for observable state.

The design optimizes for deterministic, small, agent-callable operations with structured evidence rather than one opaque integration runner.

## Goals / Non-Goals

**Goals:**

- Establish a monorepo layout for firmware, Android, shared protocol, Nix, hardware notes, and collision-resistant `sleepwalker-hil/` tooling.
- Provide Nix flake outputs for toolchains, firmware/APK builds, agent-callable HIL operations, and a bootable sacrificial NixOS HID observer ISO.
- Implement an ESP32-S3 firmware architecture with BLE RX callbacks decoupled from TinyUSB HID writes through a thread-safe queue.
- Implement an Android architecture where ADB commands enter a companion app, a service owns BLE connection state, and a core library handles framing and BLE transport.
- Define a shared protocol with symbolic USB HID usages, sequence IDs, CRC/error handling, ACK/status notifications, and structured logs across Android, ESP UART, and HID observer output.
- Make the first physical end-to-end scenario keyboard-only: inject `USB_KEY_SPACE`, observe Linux `KEY_SPACE` down/up on the sacrificial host, and correlate the command through Android, ESP, and HID observer logs.
- Treat human work as explicit commissioning/recovery gates using `noti`, not as hidden test-run assumptions.

**Non-Goals:**

- No mouse E2E verification in the first implementation slice.
- No Android emulator BLE path for physical HIL; the physical Android device is required for E2E.
- No claim that flashing, ADB, SSH, or HIL operations are pure Nix checks; they are side-effectful flake apps/commands using deterministic toolchains.
- No broad macro/text injection before the keyboard smoke path is stable.
- No app-level cryptographic command signing in the first slice unless explicitly added later; BLE bonding plus safety state is the initial safety boundary, while CRC remains corruption detection only.
- No global or imperative tool installation; ad-hoc tools must come from the flake/dev shell or Nix.

## Decisions

### Decision: The coding agent is the harness; `sleepwalker-hil/` contains operational adapters

The autonomous control loop lives in the coding agent. Repository-side HIL code exposes deterministic primitives: build, flash, monitor UART, install APK, issue ADB commands, start remote HID observation, invoke human gates, and run narrow smoke scenarios.

Alternatives considered:

- **Monolithic integration runner**: simpler single command, but hides failure localization and makes agent debugging harder.
- **Only ad-hoc shell commands**: flexible, but not reproducible or self-documenting.

Chosen shape: prefixed flake apps and helper binaries under `sleepwalker-hil/`, plus optional composed smoke commands.

### Decision: Use collision-resistant names

Top-level operational tooling uses `sleepwalker-hil/`. Flake apps/packages use `sleepwalker-*` names such as `sleepwalker-fw-flash`, `sleepwalker-adb-inject-key`, and `sleepwalker-hid-observer-iso`.

Rationale: generic names like `harness`, `flash`, or `hid-observe` are ambiguous in an agent/tooling environment.

### Decision: Build a sacrificial NixOS HID observer ISO from the flake

The remote Linux host is a dedicated NixOS machine booted specifically to observe ESP32-S3 USB HID events. The flake exposes a NixOS configuration and ISO image that enables SSH, installs the HID observer helper, configures input-device permissions/udev rules, and emits JSONL evdev observations.

Alternatives considered:

- **Use the remote host's mutable OS state**: faster initially, but violates reproducibility.
- **Require Nix installed on the remote host**: useful, but the bootable ISO provides a stronger known-good state.

### Decision: Verify physical HID with evdev on the observer host

UART logs and Android ACKs are necessary but insufficient. Passing E2E requires physical evdev observations from the sacrificial host. The HID observer helper should support exclusive device grab during a test to prevent injected keys from reaching a console/session.

### Decision: Keep BLE callbacks and TinyUSB writes separated by `hid_bridge`

NimBLE GATT write handlers must perform bounded work, validate/copy frames, and enqueue commands. A dedicated HID worker task owns TinyUSB report emission. This prevents BLE stack stalls, TinyUSB reentrancy bugs, and unsafe cross-context HID writes.

### Decision: Use symbolic HID usages at external boundaries

The first ADB command should use a symbolic key such as `USB_KEY_SPACE`, not an Android/Linux numeric keycode. Numeric keycodes differ between Android `KeyEvent`, Linux evdev, USB HID usage IDs, and firmware internals.

### Decision: Use structured JSONL diagnostics across layers

ESP UART, Android logcat, HID observer, and smoke summaries emit line-oriented structured logs with sequence IDs and stable event names. This lets the agent correlate a command across ADB, BLE, firmware queueing, USB HID emission, and Linux evdev observation.

### Decision: Human gates are explicit and observable

Commissioning/recovery may call `noti` with exact physical instructions, then poll an observable condition such as SSH reachability, Android bond state, ESP bootloader availability, or HID device appearance. Normal regression mode should not require human action.

## Risks / Trade-offs

- **ESP flashing may require manual BOOT/RESET** → Prefer USB-to-TTL DTR/RTS wired to ESP32-S3 EN/GPIO0 or an equivalent relay/control path. Until then, use `noti` only in commissioning/recovery mode.
- **Android BLE pairing can require UI interaction** → Treat first pairing as commissioning unless full automation is later required; use `noti` and poll Android bond state.
- **Remote HID observer input permissions can block tests** → Configure the ISO with a dedicated user/group, udev rules, and installed observer helper instead of relying on mutable host setup.
- **SSH host-key prompts can block automation** → Use a dedicated known-hosts file and noninteractive policy for the sacrificial observer host; avoid polluting user SSH state.
- **Native USB HID events can affect the observer host** → Run the observer helper with exclusive evdev grab during tests and keep the host sacrificial/minimal.
- **Gradle/Android reproducibility is harder than ESP firmware reproducibility** → Start with Nix-pinned JDK/Android SDK and Gradle locking; harden dependency vendoring if required.
- **BLE MTU varies by Android device** → Treat MTU as runtime negotiated state and frame/chunk based on negotiated MTU minus ATT overhead.
- **CRC-32 may be mistaken for authorization** → Document CRC as corruption detection only; initial authorization boundary is BLE bonding plus explicit firmware safety state.

## Migration Plan

This is a new project foundation with no existing implementation to migrate. Implementation should create the planned directory structure and add capabilities incrementally: protocol constants/golden frames, firmware skeleton, Android skeleton, Nix flake/tooling, observer ISO/helper, then the first keyboard smoke path.

Rollback is deleting or reverting the new project files/artifacts; no existing runtime behavior is modified.

## Open Questions

- Exact ESP32-S3 board model and whether USB-to-TTL DTR/RTS can be wired to EN/GPIO0 for autonomous flashing/reset.
- Sacrificial observer host architecture, assumed initially to be `x86_64-linux` unless specified otherwise.
- Harness host SSH public key path to embed or reference for the observer ISO.
- Final USB VID/PID/product string for stable observer matching.
