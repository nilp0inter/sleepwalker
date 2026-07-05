---
name: sleepwalker-bench
description: Sleepwalker hardware bench topology, wiring, data flow, commissioning, and bench.toml configuration. Use when the agent needs to understand the physical setup, configure hardware addresses, wire cables, commission the bench, or troubleshoot connectivity between the harness host, Android device, ESP32-S3, and observer host.
---

# The Autonomous Bench

Once commissioned, the bench is a closed loop you operate entirely through
software. No human is needed unless hardware fails physically.

## Topology

```
┌──────────────┐   USB/ADB     ┌──────────────┐
│  Harness     │◄─────────────►│  Android     │
│  Host (NixOS)│               │  Device      │
│  (YOU run    │               └──────┬───────┘
│   here)      │                      │ BLE (wireless)
│              │  USB-to-TTL   ┌──────▼───────┐
│              │◄───UART──────►│  ESP32-S3    │
│              │  (logs+flash) │  (firmware)  │
│              │               └──────┬───────┘
│              │                      │ Native USB (HID)
│              │   SSH (LAN)   ┌──────▼───────┐
│              │◄─────────────►│  Observer    │
└──────────────┘               │  Host (NixOS)│
                               └──────────────┘
```

## Three machines, three roles

| Machine | Role | How you reach it |
|---------|------|-----------------|
| **Harness host** | Where you run. NixOS workstation with the Nix flake, dev shell, all `sleepwalker-*` commands. | Local — you execute here. |
| **Android device** | BLE central + ADB target. Runs `sleepwalker-app`. Receives your commands over ADB broadcasts, forwards them to the ESP32-S3 over BLE. | USB cable → harness host. ADB over USB. |
| **ESP32-S3 board** | BLE peripheral + USB HID device. Receives framed commands over BLE, emits USB keyboard/mouse reports. | USB-to-TTL UART → harness host (flash + logs). Native USB → observer host (HID output). |
| **Observer host** | Sacrificial NixOS machine. Receives the ESP32-S3's USB HID, reads evdev events, reports them back to you over SSH as JSONL. | SSH over LAN. Boots from a Nix-built ISO. |

## Data flow for a single HID command

1. You invoke an ADB adapter (e.g. `sleepwalker-adb-inject-key`) on the harness host.
2. ADB broadcast → `sleepwalker-app` on the Android device.
3. The app uses `sleepwalker-core` to encode the command into a protocol frame.
4. Frame sent over BLE (NUS GATT service) to the ESP32-S3.
5. ESP32-S3 firmware decodes the frame, applies safety state, emits a USB HID report.
6. The USB HID report arrives at the observer host via the native USB cable.
7. The observer helper (C binary on the NixOS ISO) reads evdev events and writes JSONL.
8. You SSH to the observer, collect the JSONL, and compare expected vs. observed events.

## What is wired where

| Cable | From | To | Purpose |
|-------|------|----|---------|
| USB data cable | Android device | Harness host | ADB control |
| USB-to-TTL adapter (3.3V, DTR+RTS) | ESP32-S3 UART pins (GPIO43/GPIO44) + GPIO0 + EN | Harness host | Firmware flash + auxiliary UART logs |
| USB data cable | ESP32-S3 native USB port | Observer host | USB HID keyboard/mouse input |
| Ethernet / WiFi | Observer host | Harness host | SSH for HID event collection |

## What makes autonomous operation possible

- **DTR/RTS wiring** on the USB-to-TTL adapter lets you flash the ESP32-S3 and reset it without a human pressing buttons.
- **ADB broadcasts** let you drive the Android app headlessly — no UI interaction needed.
- **SSH to the observer** lets you collect HID events remotely.
- **Structured JSON output** from every command lets you parse results programmatically.
- **Sequence IDs (`seq`)** on every command let you correlate events across all three machines.
- **Nix-deterministic builds** mean the same source always produces the same firmware and APK.

## Commissioning (one-time, human-assisted)

The bench requires a one-time human commissioning phase documented in
`hardware/COMMISSIONING.md`: purchasing hardware, wiring cables, flashing
the observer ISO to a USB drive, booting the observer, accepting the BLE
pairing prompt on the Android device, and writing `bench.toml`.

Once commissioned, you never need the human again unless:
- A cable is unplugged or hardware fails
- The observer host needs the ISO reflashed
- The BLE bond is lost (rare — the bond persists across reboots)

The commissioning guide tags every step as `[HUMAN]`, `[AGENT]`, or
`[HANDOFF]` so you know exactly where your involvement starts and ends.

## Bench Configuration

The file `sleepwalker-hil/bench.toml` is the single source of truth for all
hardware addresses. Validate it with `sleepwalker-bench-validate bench.toml`
before any hardware operation.

**Required fields:**

```toml
[android]
adb_serial = "XXXXXXXX"  # Find with `adb devices`

[esp]
uart_port = "/dev/ttyUSB0"  # UART port for auxiliary logs
flash_port = "/dev/ttyUSB0"  # Port for autonomous flashing
uart_baud = 115200

[hid_observer]
ssh_target = "observer@sleepwalker-hid-observer"  # SSH target for observer host
identity_file = "~/.ssh/sleepwalker_observer_ed25519"
known_hosts = "/etc/ssh/ssh_known_hosts_sleepwalker"

[hid_match]
vid = "303A"  # USB Vendor ID (hex, no 0x prefix)
pid = "4001"  # USB Product ID (hex, no 0x prefix)
product = "Sleepwalker HID Keyboard"
manufacturer = "Sleepwalker"

[artifacts]
dir = "./artifacts"  # Where smoke operations write structured artifacts
```
