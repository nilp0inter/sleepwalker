# AGENTS.md — Autonomous Agent Guide

## What You're Building

Sleepwalker is an open-source hardware HID injector. It impersonates physical
input devices at the USB HID level, enabling automation of any interface that
lives below the OS API layer. The stack:

- **ESP32-S3 firmware** (C/ESP-IDF) — receives BLE commands, emits USB HID
  reports. Intentionally dumb.
- **sleepwalker-core** (Kotlin library) — the product. Protocol, BLE
  transport, low-level HID primitives + high-level text/keymap resolution.
- **sleepwalker-app** (Android) — reference app demonstrating the library.
  ADB-driven for automation.

You are the autonomous brain. The repo provides deterministic limbs and
senses. Your job is to iterate on the codebase without human intervention.

## Skills

Domain knowledge is split into skills that load on demand:

- **sleepwalker-bench** — bench topology, wiring, commissioning, bench.toml
- **sleepwalker-commands** — all `sleepwalker-*` command reference
- **sleepwalker-verification** — verification loop, artifacts, diagnostics, invariants

## Project Structure

```
.
├── firmware/               # ESP32-S3 firmware (ESP-IDF, C)
│   ├── main/               # Entry point, HID worker task
│   └── components/         # usb_hid, hid_bridge, ble_uart, protocol, safety, sw_log
├── android/
│   ├── sleepwalker-core/   # Kotlin library: protocol, hid, text, keymap, ble
│   └── sleepwalker-app/    # Android app: ADB receiver, BLE service, UI
├── protocol/               # Python protocol package (single source of truth)
│   └── src/sleepwalker_protocol/  # frame, opcodes, usages, mouse, status, fixtures, generator
├── nix/                    # Harness adapters (sleepwalker-* commands)
│   ├── adb-ops.nix         # 11 ADB command adapters
│   ├── smoke-*.nix         # Smoke orchestrators (keyboard, text, mouse, composite)
│   ├── hid-observe.nix     # SSH wrapper for observer
│   ├── fw-*.nix            # Firmware build/flash/uart/reset
│   ├── apk-*.nix           # Android build/install
│   └── observer-helper-src/  # C binary for evdev JSONL observation
├── sleepwalker-hil/
│   └── bench.toml          # Live bench configuration
├── hardware/
│   └── COMMISSIONING.md    # Human commissioning guide
└── openspec/
    ├── specs/              # Living specs (evolving with the project)
    └── changes/            # Active and archived changes
```

## OmniKeymap Integration

The project uses the [OmniKeymap](https://github.com/nilp0inter/OmniKeymap) database as a Nix flake input to generate Kotlin keyboard layout classes for `sleepwalker-core`. This ensures hermetic builds without external network dependencies.

**Key components:**
- `omni-keymap` flake input in `flake.nix` (pinned to a specific commit)
- `nix/keymap-gen.nix` derivation that runs `protocol/src/sleepwalker_protocol/generator.py`
- `nix/apk-build.nix` copies generated `.kt` files into `android/sleepwalker-core/src/main/kotlin/` before Gradle build

**Local development with custom OmniKeymap:**
```bash
nix build .#sleepwalker-keymap-gen --override-input omni-keymap path:/path/to/local/OmniKeymap
```

This allows testing layout changes without pushing to the OmniKeymap repository.

## OpenSpec Lifecycle

The project is spec-driven. You drive the OpenSpec lifecycle with the user:
propose changes, create artifacts (proposal, design, tasks), implement, and
sync specs as the system evolves. Specs are living documents — they grow and
change as the project grows. Never enumerate them here; read them from
`openspec/specs/<name>/spec.md` when needed.

## CRITICAL: End-to-End Validation

**No spec or change is complete without end-to-end validation on the physical bench.**

The physical setup (harness host → Android device → ESP32-S3 → observer host)
is the source of truth. A change that compiles, passes unit tests, or even
passes no-hardware checks is NOT done until it has been validated end-to-end
on the commissioned bench:

1. Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml`
2. Inspect `artifacts/run_composite_<timestamp>/summary.json`
3. Verify all affected components show passing evidence

If the bench is not commissioned, the change is blocked — do not mark it
complete. If hardware is unreachable, ask the human.


## When in Doubt

1. **Read the spec** — `openspec/specs/<name>/spec.md` defines the contract
2. **Run the no-hardware checks** — `sleepwalker-protocol-check`, `sleepwalker-fw-build`, `sleepwalker-apk-build`
3. **Run the composite smoke** — `sleepwalker-smoke-composite sleepwalker-hil/bench.toml`
4. **Inspect the artifacts** — `artifacts/run_composite_<timestamp>/` has all the evidence
5. **Ask the human** — only if hardware is unreachable or a physical intervention is needed ring the human using noti
