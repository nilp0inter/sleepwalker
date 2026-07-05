## Context

The bootstrap change established the physical autonomous loop: ADB command intake, Android BLE central behavior, ESP32-S3 BLE peripheral firmware, TinyUSB keyboard output, and remote NixOS evdev observation. The first E2E smoke proves `USB_KEY_SPACE` reaches the observer as `KEY_SPACE` down/up with correlated Android, ESP UART, and HID observer evidence.

The next product boundary is different from the bootstrap boundary. The reusable artifact should be the Kotlin library plus simple firmware, with the Android app acting as a reference/demo app and the HIL harness acting as autonomous evidence. The firmware should remain a minimal BLE-to-USB actuator; text rendering, keymap selection, mouse gesture state, and host-profile semantics belong in the Kotlin library.

Current code already has `android/sleepwalker-core` and `android/sleepwalker-app`, but the existing specs place library behavior inside `android-ble-companion`. The app-side code also contains bootstrap-era BLE duplication between the service path and ADB receiver path. This change makes the library boundary explicit before expanding high-level behavior.

## Goals / Non-Goals

**Goals:**

- Establish `sleepwalker-core` as the public FOSS-facing Kotlin library capability.
- Define a two-level library API: low-level HID primitives plus high-level text planning over host keymaps.
- Add raw relative mouse support end-to-end through protocol, firmware, library/app command path, and HIL evidence.
- Keep protocol frame v1 unchanged while defining opcode namespaces for keyboard, relative mouse, future absolute pointer, future virtual serial, and future capabilities/configuration.
- Keep firmware layout-unaware and text-unaware.
- Preserve autonomous implementation and verification: no new physical human commissioning steps.

**Non-Goals:**

- No absolute pointer implementation.
- No USB CDC / virtual serial implementation.
- No complete global keymap corpus ingestion.
- No cryptographic command signing.
- No host-side agent, clipboard bridge, Android emulator BLE path, macro language, or public package release automation.
- No broad rewrite of the working keyboard E2E path beyond what is needed for library boundary cleanup.

## Decisions

### Decision: `sleepwalker-core` is the product boundary

The public Kotlin API belongs in a new `sleepwalker-core` capability. The Android app remains a reference app and HIL command surface. Protocol encoding, keymap rendering, mouse state/chunking, and status interpretation should live in reusable library code rather than app-only classes.

Alternatives considered:

- Keep all Android behavior under `android-ble-companion`: faster, but hides the library contract inside demo-app requirements.
- Split into many Gradle modules immediately: clearer long-term, but extra churn before the public API stabilizes.

Chosen shape: define the `sleepwalker-core` capability and organize public packages/API boundaries first. Physical module splitting can happen later if the implementation shows a real need.

### Decision: Preserve frame v1 and extend by opcode namespace

The existing frame layout has version, sequence ID, opcode, payload length, payload, and CRC-32. It is sufficient for relative mouse, future absolute pointer, and future serial writes. Changing it now would add risk without enabling a required behavior.

Opcode ranges become the extension mechanism:

- `0x0001-0x000F`: safety/control
- `0x0010-0x00FF`: keyboard HID
- `0x0100-0x01FF`: relative mouse HID
- `0x0200-0x02FF`: future absolute pointer HID
- `0x0300-0x03FF`: future virtual serial / USB CDC
- `0x0400-0x04FF`: future capabilities/configuration
- `0xF000-0xFFFF`: private/test/invalid fixtures

### Decision: Add relative mouse as one raw report opcode

Relative mouse is represented by one raw report payload: `buttons:u8`, `dx:i8`, `dy:i8`, `wheel:i8`, `pan:i8`. The library owns button-mask state, movement chunking, clicks, drags, and scrolling abstractions. Firmware validates the payload and emits the corresponding TinyUSB report.

Alternatives considered:

- Semantic opcodes such as `MOUSE_BUTTON_DOWN`, `MOUSE_MOVE`, and `MOUSE_SCROLL`: friendlier at the wire level, but pushes button state and drag semantics into firmware.
- Wider `i16` relative axes: fewer frames for large moves, but less boot-mouse-like and unnecessary because the library can chunk movement.

Chosen shape: raw `i8` relative report for firmware simplicity and HID compatibility.

### Decision: Keep keyboard semantic opcodes for this change

The existing `KEY_TAP`, `KEY_DOWN`, and `KEY_UP` path is proven end-to-end and sufficient for text planning. The change reserves room for raw keyboard reports but does not migrate keyboard now.

Rationale: migrating a working keyboard path while adding mouse would increase scope without unblocking keymaps, mouse, absolute pointer, or serial.

### Decision: Use future-compatible HID report identity

When adding mouse, the USB HID descriptor strategy must preserve identity for keyboard and relative mouse reports and leave room for a later absolute pointer report. The preferred implementation is report IDs in one HID interface:

- Report ID 1: keyboard
- Report ID 2: relative mouse
- Report ID 3: future absolute pointer

Multiple HID interfaces are acceptable if they provide equivalent forward compatibility and simpler TinyUSB integration.

### Decision: Define bundled keymap architecture, not full corpus ingestion

The library should be designed around a bundled keymap database that can eventually contain all supported OS/layout/variant mappings. This change defines host profile, layout lookup, text planning, seed/conformance data, and structured failures. It does not attempt to ingest and validate every global keymap source in one autonomous slice.

Rationale: the full keymap corpus has source normalization, dead-key/compose behavior, validation, and provenance complexity. Making the API and failure model correct first reduces the risk of locking users into a bad data shape.

### Decision: HIL verifies product paths, not private shortcuts

The mouse smoke should drive a public app/library command path and verify physical evdev events. Protocol/unit tests cover byte-level parity. HIL remains evidence machinery, not a separate product API.

## Risks / Trade-offs

- [Risk] Adding HID report IDs may alter the existing keyboard report descriptor shape. → Mitigation: keep keyboard smoke as a required regression and preserve observer evidence for `KEY_SPACE`.
- [Risk] Relative mouse events may enumerate under a different evdev device than keyboard. → Mitigation: update HIL matching to observe the matching composite device and assert specific `BTN_*`/`REL_*` events.
- [Risk] The keymap seed could be mistaken for complete keymap support. → Mitigation: public API and docs/specs must distinguish the bundled database contract from complete corpus ingestion.
- [Risk] App/service cleanup can break the working ADB path. → Mitigation: keep ADB command names stable and require both keyboard and mouse smoke through ADB after cleanup.
- [Risk] Future serial may need bidirectional streaming beyond status notifications. → Mitigation: reserve opcode namespace now, preserve opaque status context, and leave GATT characteristic expansion for a future serial-specific design.

## Migration Plan

1. Add protocol constants, payload helpers, and parity fixtures while preserving frame v1.
2. Add library low-level mouse APIs and text-planning/keymap boundary APIs behind tests.
3. Add firmware HID report identity and relative mouse report emission while keeping keyboard smoke passing.
4. Route reference app/ADB commands through reusable library/session behavior.
5. Add HIL mouse smoke and rerun keyboard smoke as regression.

Rollback strategy: revert the new mouse/library-boundary change while retaining the archived bootstrap keyboard behavior. Because frame v1 remains unchanged and future opcodes are additive, rollback does not require migration of existing keyboard frames.

## Open Questions

- Virtual serial directionality is intentionally unresolved: one-way BLE-to-USB serial writes may fit the existing command path; bidirectional serial streaming may require a dedicated BLE characteristic or a future data-notification shape.
- The complete keymap corpus source mix is intentionally unresolved. This change defines the API/data contract; a later data-ingestion change can choose exact sources and generation machinery.
