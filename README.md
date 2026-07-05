# sleepwalker

An open-source hardware HID injector built to be embedded into larger automation
projects and extended by the community.

## Why

Real-world automation eventually hits the wall that headless APIs cannot
cross: any interface that lives below the OS API layer. The only honest way
to drive those surfaces is to impersonate a physical keyboard and mouse at
the USB HID level. `sleepwalker` exists to make that primitive open, safe,
and usable from code.

## What it is

A small, focused FOSS stack:

- **A tiny ESP32-S3 firmware** that exposes a BLE-only control surface and
  emits USB HID reports. The firmware stays intentionally dumb — it receives
  framed commands and produces HID output, nothing more.
- **A Kotlin library** (`sleepwalker-core`) that is the real product. It owns
  the protocol, the BLE transport, and a two-tier API:
  - a **low-level surface** that mirrors raw HID primitives (key down/up,
    mouse button, motion, scroll), and
  - a **high-level surface** that hides the keymap problem — the caller says
    "the target host runs this OS with this layout, type this text," and the
    library resolves glyphs, modifiers, and dead keys into the right HID
    sequence.
- **A reference Android app** (`sleepwalker-app`) that demonstrates the
  library and serves as a working example for integrators.

The split is deliberate: the firmware remains minimal and auditable; the
library carries the intelligence and is the surface other projects build
against.

## Goals

- Provide a minimal, auditable primitive for impersonating physical input
  devices at the USB HID level.
- Keep the firmware small enough to audit by hand and stable enough to forget.
- Make the Kotlin library the friendliest possible on-ramp — a consumer
  should be able to type a string or click a button in a handful of lines.
- Treat safety as a first-class concern: explicit arm/disarm, kill,
  release-all, and human gates, never silent injection.
- Reproduce the entire stack — firmware, library, app, and the host used to
  verify HID output — from source with Nix, so anyone can build on it.

## Non-goals

- Not a stealth input device, not a Red Team tool, not an HID attack
  framework. The threat model assumes the operator owns or is authorized to
  drive the target host.
- Not a general-purpose BLE HID stack — the surface is scoped to what an
  automation agent actually needs.
- Not a closed appliance. The reference app exists to demonstrate the
  library; the library is the product.

## Status

Early. The keyboard path is proven end-to-end against physical hardware; the
mouse surface, the high-level API, and the keymap-resolution layer are the
active design frontier.

## License

MIT. See [`LICENSE`](LICENSE).

## Development

This project is designed for autonomous iteration by a coding agent. The
repository provides deterministic build, flash, and verification commands
(all prefixed `sleepwalker-*`) that an agent can compose into a closed loop:
build firmware and APK, flash devices, inject HID commands, observe results
on a sacrificial NixOS host, and produce structured artifacts for pass/fail
diagnosis. See `AGENTS.md` for the full agent-facing interface.
