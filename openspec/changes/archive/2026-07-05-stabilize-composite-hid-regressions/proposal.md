## Why

Relative mouse support exists, but the latest evidence shows the regression loop is not yet trustworthy for the composite keyboard+mouse device: mouse smoke passes with raw-code caveats while the latest keyboard smoke fails to observe `KEY_SPACE`. Before expanding text/keymap behavior, the project needs one autonomous composite HID regression proving keyboard and mouse events together with stable symbolic observer evidence.

## What Changes

- Update HID observer behavior so the Sleepwalker composite keyboard+mouse device is discovered by stable descriptor/capability evidence rather than stale keyboard-only assumptions.
- Update evdev decoding so observer JSONL reports symbolic names for keyboard keys, mouse buttons, and relative axes (`KEY_SPACE`, `BTN_LEFT`, `REL_X`, `REL_Y`, `REL_WHEEL`).
- Add a combined composite HID smoke scenario that verifies keyboard and relative mouse events against the same firmware/app build and observer session.
- Tighten smoke artifact summaries so keyboard evidence, mouse evidence, observer device identity, and sequence correlation are reported separately and machine-readably.
- Preserve autonomous operation: the stabilized regression must not require new human gates when the bench is already commissioned.

## Non-goals

- No new library API surface.
- No high-level text/keymap expansion.
- No absolute mouse implementation.
- No virtual serial/CDC implementation.
- No protocol frame or opcode changes.
- No new hardware topology or manual commissioning step.
- No changes to the existing product goal of keeping firmware layout-unaware.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `hid-observer-nixos-iso`: Add composite HID discovery and symbolic evdev decoding requirements for keyboard, mouse buttons, and relative axes.
- `agent-operated-hil`: Add combined keyboard+mouse smoke behavior, structured composite smoke artifacts, and autonomous regression requirements.
- `esp32-s3-hid-firmware`: Clarify that the final composite HID descriptor/report behavior must preserve keyboard output while relative mouse support is active.

## Impact

- Affects the observer helper source and NixOS observer ISO/package contents.
- Affects HIL smoke orchestration and artifact parsing under `sleepwalker-hil/`.
- May affect firmware HID descriptor/report behavior if the keyboard failure is caused by the composite descriptor rather than observer matching.
- Does not affect public library API design, protocol frame layout, or future feature reservations.
