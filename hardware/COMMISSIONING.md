# Sleepwalker HIL Commissioning Guide

This guide takes the project from **zero hardware** to **ready to run
task 7.7** (the physical keyboard smoke scenario).

Two collaborators participate:

- **HUMAN** — You. Performs physical tasks that cannot be automated:
  purchasing, wiring, plugging, pressing buttons, accepting prompts.
- **AGENT** — The coding agent running on the harness host. Performs
  every software operation: building, flashing, installing, ADB
  commands, SSH, artifact analysis, smoke orchestration.

Each step is tagged `[HUMAN]`, `[AGENT]`, or `[HANDOFF]` (where control
changes). When you reach an `[AGENT]` step, hand control to the agent
and wait for it to report completion or ring you via `noti`.

---

## Phase 1: Hardware Procurement `[HUMAN]`

> The agent cannot purchase or receive physical goods.

### 1.1 ESP32-S3 Development Board

Any ESP32-S3 board with:
- Native USB (USB-OTG, exposed on USB-C or micro-USB)
- ≥ 4 MB flash
- UART pins broken out (GPIO43/GPIO44 or a labelled UART header)

Recommended: Espressif ESP32-S3-DevKitC-1 (N8R2 or N16R8).

Do **not** buy a non-S3 ESP32 board.

### 1.2 USB-to-TTL Serial Adapter (3.3V)

| Requirement | Spec |
|-------------|------|
| Logic level | **3.3 V** (5 V will damage the ESP32-S3) |
| Chipset | CP2102, CH340, or FT232RL |
| Control pins | **DTR + RTS** broken out (required for autonomous flash) |
| Data pins | TX, RX, GND, 3.3 V |

If the adapter lacks DTR/RTS, every flash cycle requires a manual
BOOT+RESET button press. The agent will ring you via `noti` each time.

### 1.3 Android Device

- Android 8.0 (API 26) or later
- BLE support
- USB cable for ADB
- Developer Options → USB Debugging enabled

Use a dedicated/sacrificial device, not your daily phone.

### 1.4 Sacrificial Observer Host

A separate x86_64 machine (any old laptop/NUC from 2015+) that will:
- Boot from the sleepwalker observer ISO (USB)
- Receive the ESP32-S3 native USB cable (HID keyboard)
- Be reachable over SSH from the harness host (same LAN)

> This host is sacrificial. Injected keyboard events reach its input
> layer. The observer helper grabs the device exclusively during tests,
> but never use this host for anything important.

### 1.5 USB Flash Drive + Cables

- 1× USB drive ≥ 2 GB (for the observer ISO)
- 1× USB data cable: ESP32-S3 native USB → observer host
- 1× USB data cable: Android → harness host
- 1× USB data cable: USB-to-TTL adapter → harness host
- Dupont jumper wires (female-to-female): USB-to-TTL ↔ ESP32-S3

---

`[HANDOFF]` — When all hardware has arrived, tell the agent:

> "Hardware is on the bench. Proceed with bench assembly guidance and software preparation."

The agent will then run Phase 3 (all software builds) while you do
Phase 2 (physical wiring) in parallel.

---

## Phase 2: Physical Bench Assembly `[HUMAN]`

> The agent cannot touch cables or wires. It will provide the wiring
> table; you execute it.

### 2.1 Topology

```
┌──────────────┐   USB/ADB     ┌──────────────┐
│  Harness     │◄─────────────►│  Android     │
│  Host (NixOS)│               │  Device      │
│  (AGENT runs │               └──────┬───────┘
│   here)      │                      │ BLE (wireless)
│              │  USB-to-TTL   ┌──────▼───────┐
│              │◄───UART──────►│  ESP32-S3    │
│              │  (logs+flash) │  (firmware)  │
│              │               └──────┬───────┘
│              │                      │ Native USB (HID)
│              │   SSH (LAN)   ┌──────▼───────┐
│              │◄─────────────►│  Observer    │
└──────────────┘               │  Host (ISO)  │
                               └──────────────┘
```

### 2.2 Wire the USB-to-TTL to the ESP32-S3

Power off everything. Connect with Dupont wires:

| USB-to-TTL | ESP32-S3 | Purpose |
|------------|----------|---------|
| GND        | GND      | Ground |
| 3.3 V      | 3V3      | Power (or skip if USB-powered) |
| TX         | GPIO44 (RX0) | Data: adapter → ESP |
| RX         | GPIO43 (TX0) | Data: ESP → adapter |
| DTR        | GPIO0    | Boot mode (autoflash) |
| RTS        | EN (RST) | Reset (autoflash) |

> ⚠️ **GPIO0/EN pad availability varies by board.** The ESP32-S3-DevKitC
> breaks these out as castellated pads. The **Waveshare ESP32-S3-Zero**
> and similar minimal carriers expose GPIO0/EN only on the tactile BOOT
> and RESET buttons — not as pads. On those boards you must either:
> 1. Solder fine wires (30 AWG) to the non-GND leg of the BOOT button
>    (GPIO0) and the non-GND leg of the RESET button (EN), or
> 2. Accept manual BOOT+RESET per flash (the agent will ring you via
>    `noti` each time).
>
> Use a multimeter in continuity mode to identify the correct leg: the
> leg that beeps to a known GND pad is GND; solder to the **other** leg.
> Add strain relief (hot-glue/Kapton) — castellated pads lift easily.

- TX↔RX crossover is correct.
- Verify 3.3 V. If unsure, power the ESP32-S3 via its USB port and
  leave the adapter VCC disconnected.

### 2.3 Plug the ESP32-S3 native USB into the observer host

Use a **separate cable** on the ESP32-S3's native USB port (labelled
"USB", not "UART"). Route it to the **observer host**, not the harness.

### 2.4 Plug the Android device into the harness host (USB)

### 2.5 Plug the USB-to-TTL adapter into the harness host (USB)

---

`[HANDOFF]` — When wiring is done, tell the agent:

> "Bench is wired. Proceed with software preparation and flashing."

---

## Phase 3: Software Preparation `[AGENT]`

> The agent performs all of these autonomously on the harness host.
> You can watch, but no action is required from you.

### What the agent delivers

| Step | Agent action | Deliverable |
|------|-------------|-------------|
| 3.1 | Enter the Nix dev shell | Toolchain available |
| 3.2 | Generate an SSH keypair for the observer | `~/.ssh/sleepwalker_observer_ed25519` + `.pub` |
| 3.3 | Write the public key into `nix/observer-authorized_keys` **and `git add` it** (Nix flakes only see git-tracked files — untracked = invisible to the build) | Key baked into the ISO |
| 3.4 | Build the observer ISO | `.iso` store path |
| 3.5 | Build the firmware | `firmware/build/sleepwalker-firmware.bin` |
| 3.6 | Build the APK | `android/.../sleepwalker-app-debug.apk` |
| 3.7 | Detect the UART port (`/dev/ttyUSB0`) | Port noted for bench.toml |
| 3.8 | Detect the Android ADB serial | Serial noted for bench.toml |

The agent reports each result as structured JSON on stdout.

---

`[HANDOFF]` — The agent has a built ISO, built firmware, and built APK.
It cannot write the ISO to a USB drive or boot the observer host.

Tell the agent:

> "ISO is built. I will flash the USB drive and boot the observer."

You now do Phase 4 while the agent waits.

---

## Phase 4: Observer Host Boot `[HUMAN]`

> The agent cannot insert USB drives, enter BIOS, or boot a separate
> physical machine.

### 4.1 Write the ISO to the USB drive

The agent gives you the ISO store path (from step 3.4). Write it:

```sh
# [HUMAN] on the harness host:
ISO_PATH=$(nix build .#sleepwalker-hid-observer-iso --no-link --print-out-paths)
# Find your USB drive:
lsblk
# Write (WARNING: erases the drive):
sudo dd if="$ISO_PATH" of=/dev/sdX bs=4M conv=fsync status=progress
sync
```

### 4.2 Boot the observer host

1. Insert the USB drive into the observer host.
2. Power on, enter BIOS/UEFI boot menu, select USB.
3. Wait for the NixOS login prompt.

### 4.3 Note the observer host's IP

Check your router's DHCP table, or look at the observer host's console
for a DHCP lease line. Tell the agent the IP.

---

`[HANDOFF]` — The observer host is booted and on the network. Tell the
agent:

> "Observer is booted at IP <OBSERVER_IP>. Proceed with SSH verification,
> firmware flash, APK install, bench config, and pre-flight checks."

The agent now takes over for Phases 5–8.

---

## Phase 5: Firmware Flash `[AGENT]`

> The agent flashes the ESP32-S3 over UART. If DTR/RTS are wired, this
> is fully autonomous. If not, the agent rings you via `noti`.

### What the agent does

| Step | Agent action | Deliverable |
|------|-------------|-------------|
| 5.1 | Flash firmware via `sleepwalker-fw-flash` | ESP32-S3 running sleepwalker firmware |
| 5.2 | Capture UART logs for 5 s | Verify JSONL boot events (`boot`, `ble_init`, `usb_init`, `ready`) |
> ⚠️ **The boot events fire within ~300 ms of reset.** A naive "reset,
> then open the port" loses the early events to the port-release race.
> The capture must own the port across the reset: open `/dev/ttyUSB0`
> first, pulse DTR/RTS to reset the chip into normal boot (DTR
> deasserted → GPIO0 high; RTS asserted then deasserted → EN pulse),
> then read lines — all in the same process. The `sleepwalker-fw-uart`
> app captures but does not reset; for a clean boot trace, use a
> pyserial script that resets + captures in one session, or extend
> `sleepwalker-fw-uart` to pulse DTR/RTS at start.

| 5.3 | SSH to observer, check HID symlink | `/dev/input/by-id/sleepwalker-hid-keyboard` exists |

### If DTR/RTS are NOT wired `[HANDOFF]` → `[HUMAN]` → `[HANDOFF]`

The agent detects the missing auto-reset and invokes
`sleepwalker-human-gate`:

1. `[AGENT]` rings `noti`: "Hold BOOT, tap RESET, release BOOT on the ESP32-S3."
2. `[HUMAN]` performs the physical button press on the ESP32-S3 board.
3. `[AGENT]` detects the bootloader and flashes automatically.

This handoff happens only during flashing. After the firmware is
flashed once, it persists across reboots.

---

`[HANDOFF]` — Firmware is running, HID device visible on observer. The
agent proceeds autonomously to Phase 6.

---

## Phase 6: Android APK Install + Permissions `[AGENT]` + `[HUMAN]`

### What the agent does

| Step | Agent action | Deliverable |
|------|-------------|-------------|
| 6.1 | Install APK via `sleepwalker-apk-install` | App installed on Android |
| 6.2 | Check ADB device is authorised | Device reachable over ADB |

### What the human does `[HANDOFF]` → `[HUMAN]`

If the Android device shows an "Allow USB debugging?" dialog, tap
**Allow** on the device screen. This is a one-time authorization.

If BLE runtime permissions need granting:
1. `[AGENT]` rings `noti`: "Grant Nearby Devices and Location permissions to Sleepwalker in Android Settings."
2. `[HUMAN]` navigates Settings → Apps → Sleepwalker → Permissions, grants them.
3. `[AGENT]` polls ADB to confirm the app is installed and permissions are granted.

---

`[HANDOFF]` — APK installed, permissions granted. Agent proceeds to
Phase 7.

---

## Phase 7: BLE Pairing (One-Time Commissioning) `[AGENT]` + `[HUMAN]`

> This is the only step in normal operation that requires a human. It
> happens exactly once; the bond persists afterward.

### What the agent does

| Step | Agent action | Deliverable |
|------|-------------|-------------|
| 7.1 | Send `sleepwalker-adb-connect` | Companion app starts BLE scan |
| 7.2 | Poll Android logcat for `ble.connect` events | Detect connection attempt |

### What the human does `[HANDOFF]` → `[HUMAN]`

The Android device displays a **Bluetooth Pairing Request** dialog.

1. `[AGENT]` rings `noti`: "Accept the BLE pairing prompt on the Android device."
2. `[HUMAN]` taps **Pair** on the Android screen.
3. `[AGENT]` polls logcat for `ble.services_discovered` + `ble.mtu`, confirming the bond.

> If no prompt appears within 30 s: power-cycle the ESP32-S3, ensure
> Android Bluetooth is ON, clear the app's data and retry.

---

`[HANDOFF]` — BLE bond established. The agent now has end-to-end
control. It proceeds to Phase 8 (config) and Phase 9 (pre-flight)
autonomously.

---

## Phase 8: Bench Configuration `[AGENT]`

> Fully automated. The agent creates the config from detected values.

### What the agent does

| Step | Agent action | Deliverable |
|------|-------------|-------------|
| 8.1 | Write `sleepwalker-hil/bench.toml` with detected ADB serial, UART port, observer SSH target | Complete config file |
| 8.2 | Run `sleepwalker-bench-validate` | Confirm all fields present |

The agent fills:
```toml
[android]
adb_serial = "<detected>"
[esp]
uart_port = "/dev/ttyUSB0"
flash_port = "/dev/ttyUSB0"
[hid_observer]
ssh_target = "observer@<OBSERVER_IP>"
identity_file = "~/.ssh/sleepwalker_observer_ed25519"
[hid_match]
vid = "303a"
pid = "4001"
[artifacts]
dir = "./artifacts"
```

---

## Phase 9: Pre-Flight Checks `[AGENT]`

> Fully automated. The agent verifies every link before running smoke.

### What the agent checks

| Check | Method | Pass criteria |
|-------|--------|---------------|
| SSH to observer | `ssh observer@<IP> echo ok` | Returns `ok` |
| HID device visible | `ssh ... ls /dev/input/by-id/sleepwalker-hid-keyboard` | Symlink exists |
| UART logs flowing | `sleepwalker-fw-uart` for 5 s | JSONL lines with `component`/`event` |
| Android logcat | `sleepwalker-adb-logcat` for 5 s | JSONL from `sleepwalker` tag |
| ADB commands | `sleepwalker-adb-status` | Structured status returned |
| noti delivery | `noti "preflight"` | Human receives notification |

If any check fails, the agent reports which layer failed and does not
proceed to the smoke test.

---

`[HANDOFF]` — All pre-flight checks pass. The agent is ready to run
the smoke scenario. It may proceed immediately or wait for your go
signal, depending on how you configured the workflow.

---

## Phase 10: Smoke Test `[AGENT]`

> Fully automated once the human gives the go signal.

### What the agent runs

```sh
nix run .#sleepwalker-smoke-keyboard -- sleepwalker-hil/bench.toml
```

This single command orchestrates:
1. Validate bench config (reject if incomplete)
2. Start ESP UART capture (30 s background)
3. Start Android logcat capture (30 s background)
4. Start HID observer over SSH with `--grab` (30 s background)
5. ADB: connect → arm (seq 1) → inject USB_KEY_SPACE (seq 2) → release-all (seq 3) → kill (seq 4)
6. Wait for captures to finish
7. Write artifacts to `./artifacts/run_<timestamp>/`

### What the agent delivers

```
artifacts/run_<timestamp>/
├── bench.toml              ← config snapshot
├── esp_uart.jsonl          ← firmware JSONL logs
├── android_logcat.jsonl    ← companion JSONL logs
├── hid_observer.jsonl      ← evdev JSONL events
├── adb_connect.log         ← ADB command outputs
├── adb_arm.log
├── adb_inject.log
├── adb_release.log
├── adb_kill.log
└── summary.json            ← machine-readable result
```

The agent then cross-correlates sequence ID `2` (the KEY_SPACE tap)
across all three JSONL layers and reports whether the E2E path
succeeded.

### Success criteria

The `hid_observer.jsonl` must contain:
```json
{"type":"EV_KEY","code":"KEY_SPACE","value":1,"code_code":57}
{"type":"EV_SYN","code":"SYN_REPORT","value":0}
{"type":"EV_KEY","code":"KEY_SPACE","value":0,"code_code":57}
{"type":"EV_SYN","code":"SYN_REPORT","value":0}
```

And `esp_uart.jsonl` must contain `"event":"sent_to_usb"` with `"seq":2`.

---

## Summary: Human vs Agent Responsibility Matrix

| Task | Owner | Frequency |
|------|-------|-----------|
| Purchase hardware | `[HUMAN]` | Once |
| Wire the bench | `[HUMAN]` | Once |
| Build ISO / firmware / APK | `[AGENT]` | On demand |
| Generate SSH keys | `[AGENT]` | Once |
| Write ISO to USB drive | `[HUMAN]` | Once |
| Boot observer host | `[HUMAN]` | On power loss |
| Flash firmware | `[AGENT]` (autonomous if DTR/RTS) | On firmware change |
| Flash firmware (no DTR/RTS) | `[HANDOFF]` — `noti` → `[HUMAN]` presses BOOT/RESET | On firmware change |
| Install APK | `[AGENT]` | On APK change |
| Grant Android permissions | `[HUMAN]` (one-time, via `noti` prompt) | Once |
| Authorize ADB debugging | `[HUMAN]` (one-time dialog) | Once |
| BLE pairing | `[HANDOFF]` — `[AGENT]` triggers → `[HUMAN]` taps Pair → `[AGENT]` confirms | Once |
| Create bench.toml | `[AGENT]` | Once |
| Validate bench.toml | `[AGENT]` | Each smoke run |
| Pre-flight checks | `[AGENT]` | Each smoke run |
| Run smoke scenario | `[AGENT]` | Each regression |
| Analyze artifacts | `[AGENT]` | Each smoke run |
| Physical recovery (replug, power-cycle) | `[HUMAN]` (via `noti` gate) | On failure |

### Human touchpoints in normal regression: ZERO

After commissioning, the agent builds, flashes, installs, drives ADB,
captures UART/logcat/HID, analyzes artifacts, and reports — all
autonomously. The human is contacted via `noti` only on failure or
commissioning/recovery.

---

## Troubleshooting

### ESP32-S3 won't flash
- `[HUMAN]` Verify DTR/RTS wiring (or do manual BOOT+RESET)
- `[AGENT]` Verify `/dev/ttyUSB0` is the correct port and no process holds it

### BLE pairing dialog doesn't appear
- `[HUMAN]` Power-cycle the ESP32-S3
- `[HUMAN]` Ensure Android Bluetooth is ON
- `[AGENT]` Clear app data via ADB and retry scan

### Observer host can't find HID device
- `[HUMAN]` Verify the ESP32-S3 native USB cable goes to the **observer host**
- `[AGENT]` SSH to observer and check `lsusb` for VID 303A
- `[AGENT]` Check udev rule: `udevadm info /dev/input/by-id/sleepwalker-hid-keyboard`

### SSH to observer fails
- `[HUMAN]` Verify the observer host booted and has network (check console)
- `[HUMAN]` Set a static DHCP reservation so the IP doesn't change
- `[AGENT]` Verify the SSH key was baked into the ISO

### noti doesn't ring
- `[HUMAN]` Run `noti --help` and configure the notification backend
- The human gate uses `noti` for commissioning/recovery only
