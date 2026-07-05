# Sleepwalker HIL Commissioning — Detours & Modifications Log

This is the field record of the **first real commissioning run**
(2026-07-04) against the idealized flow in [`COMMISSIONING.md`](./COMMISSIONING.md).
Every deviation, workaround, and firmware defect we hit is captured here so
future runs can pre-empt them instead of rediscovering them.

The headline: the doc's 10-phase flow is sound, but the **specific hardware**
(a Waveshare ESP32-S3-Zero, not the DevKitC the doc assumes) plus **four latent
firmware bugs** and **one Nix flake gotcha** forced real detours. All were
resolved; the bench now flashes, boots clean, advertises BLE, enumerates as
HID `303a:4001`, and emits structured JSONL.

---

## Hardware actually used (deviates from Phase 1.1)

| Role | Doc recommends | Actually used | Matters because |
|---|---|---|---|
| ESP32-S3 board | ESP32-S3-DevKitC-1 (N8R2/N16R8) | **Waveshare ESP32-S3-Zero** (ESP-S3-Zero, ESP32-S3FH4R2, 4 MB flash / 2 MB PSRAM) | See §1 — GPIO0/EN not on pads; single USB-C only |
| USB-to-TTL | CP2102/CH340/FT232RL, DTR+RTS | **FTDI FT232R** (`0403:6001`) on `/dev/ttyUSB0` | Has DTR+RTS — autonomous flash possible |
| Android | API ≥ 26, BLE | **Pixel 6a** (`bluejay`), ADB serial `29211JEGR12028` | Already ADB-authorized |
| Observer host | any x86_64 | NixOS minimal ISO at `10.4.1.129` | SSH key baked in (after fix, §5) |

ESP32-S3 MAC: `1c:db:d4:84:d4:b4`. Chip: ESP32-S3 QFN56 rev v0.2.

---

## 1. ESP32-S3-Zero: GPIO0 and EN are NOT exposed pads (Phase 2.2 detour)

### What the doc assumed
Phase 2.2 wires `DTR→GPIO0` and `RTS→EN(RST)` to castellated pads, enabling
autonomous esptool auto-reset.

### What the ESP-S3-Zero actually exposes
Verified against the official Waveshare wiki (`https://www.waveshare.com/wiki/ESP32-S3-Zero`)
and confirmed by visual inspection:
- Pads present: `TX` (GPIO43), `RX` (GPIO44), `5V`, `GND`, `3V3`, numbered
  `GP1`–`GP13`, plus rear pads `GP17/18/38–42/45`.
- **`GPIO0` and `EN` are on the on-board BOOT and RESET tactile buttons only —
  NOT broken out as pads.**
- The board has **one USB-C connector = native USB only** (no on-board
  USB-to-UART bridge). `GPIO33–37` are internal (Octal PSRAM).

### Decision taken: Option B — solder taps
Three options were weighed (manual buttons / solder taps / topology change to
USB-CDC flash via observer). Chosen: **solder fine wires to the GPIO0 side of
the BOOT button and the EN side of the RESET button**, then wire
`DTR→GPIO0-tap`, `RTS→EN-tap`. One-time human commissioning (acceptable per
project policy).

### Soldering procedure that worked
1. **Find the right pad (power off, multimeter continuity):** each tactile
   switch has 4 legs — two form the GPIO0/EN net, the opposite two are GND.
   Probe each leg against a known GND pad; the leg that beeps to GND is the
   GND side. **Solder to the OTHER leg** (GPIO0 for BOOT, EN for RESET).
2. 30 AWG wire, fine conical tip ~320 °C, 1–2 s tack, flux the pad.
3. **Strain relief:** hot-glue/Kapton over each wire-to-pad junction —
   castellated pads lift easily.
4. **Verify before powering (multimeter, power off):**
   - `GPIO0-tap ↔ GND` = **OPEN**
   - `EN-tap ↔ GND` = **OPEN**
   - A beep here means you bridged the button → chip sits in reset/boot-loop.
5. The whole flash path was then verified in one read-only command:
   `esptool --port /dev/ttyUSB0 --before default-reset --after hard-reset chip-id`
   returned the chip ID + MAC → TX/RX crossover, GND, DTR→GPIO0, RTS→EN all
   confirmed correct.

> **Pre-empt next time:** if the board is an ESP-S3-Zero (or any minimal
> carrier without a USB-UART bridge), budget for soldering GPIO0/EN taps, OR
> accept manual BOOT+RESET per flash. Do **not** assume the DevKitC padout.

### Direct DTR/RTS wiring works with esptool `default-reset`
With direct `DTR→GPIO0` / `RTS→EN` (no transistor matrix), esptool's
`default-reset` sequence still asserts GPIO0-low across the EN rising edge →
download mode. Verified: `sleepwalker-fw-flash` uses `idf.py flash` → esptool
`default-reset`, and the flash succeeded autonomously.

---

## 2. USB-to-TTL adapter pin-label disambiguation (Phase 2.2)

The FT232R adapter board silk-screened **three** TX/RX label groups:
- `RXL` / `TXL` → **LED** test points (RX-LED / TX-LED). **Never wire these** —
  they load the line and carry no data.
- `RXD`/`TXD` and `RX`/`TX` → the real data pins; continuity test confirmed
  they are the **same net** (`RXD↔RX`, `TXD↔TX`). Use either.

Final data wiring (crossover, unchanged from the doc):

| USB-to-TTL | ESP-S3-Zero pad |
|---|---|
| GND | GND |
| TXD | RX (GPIO44) |
| RXD | TX (GPIO43) |
| DTR | GPIO0 tap (BOOT button, non-GND leg) |
| RTS | EN tap (RESET button, non-GND leg) |
| 3V3 | **left disconnected** — board is USB-powered via the observer |

GND must stay connected (signal common) even with 3V3 omitted.

---

## 3. Topology note: single USB-C, two independent paths

The ESP-S3-Zero has one USB-C (native USB). The bench runs **two independent
connections to the ESP32 simultaneously**:
- **Native USB-C → observer host** (TinyUSB HID keyboard, `303a:4001`).
- **FT232R → harness host `/dev/ttyUSB0`** (flash + auxiliary UART JSONL logs,
  via the GPIO43/44/0/EN pad wires).

These coexist: the USB-C is the HID peripheral; the FT232R is a separate UART
on the GPIO pads. Flashing over the FT232R does not touch the USB-C path.

---

## 4. Firmware USB PID bug — silent commissioning killer (Phase 5 blocker)

### Symptom that would have appeared
If flashed as-built, the ESP32 would enumerate as `303a:4004`, the observer
udev rule (`ATTRS{idProduct}=="4001"`) would never match, and
`/dev/input/by-id/sleepwalker-hid-keyboard` would never be created → Phases
5.3, 9, 10 fail with no obvious cause.

### Root cause (source-verified)
`firmware/managed_components/espressif__esp_tinyusb/usb_descriptors.c` computes
the auto-PID as a bitmap:
```c
#define USB_TUSB_PID (0x4000 | _PID_MAP(CDC,0) | _PID_MAP(MSC,1) | _PID_MAP(HID,2) | ...)
```
`firmware/sdkconfig.defaults` set only `CONFIG_TINYUSB_HID_COUNT=1` and
`CONFIG_TINYUSB_DESC_USE_DEFAULT_PID=y`, so `idProduct = 0x4000 | (1<<2) =
0x4004` — **not** the intended `0x4001` the project documents as "the
sleepwalker keyboard product id" (`nix/observer-host.nix`, `bench.toml`).

### Fix (firmware-side, one source of truth)
Added to `firmware/sdkconfig.defaults`:
```
# Claim the deliberate sleepwalker HID keyboard product ID (0x4001) instead
# of the TinyUSB generic HID auto-default (0x4004). Must match the observer
# host udev rule (nix/observer-host.nix) and bench.toml [hid_match] pid.
CONFIG_TINYUSB_DESC_USE_DEFAULT_PID=n
CONFIG_TINYUSB_DESC_CUSTOM_PID=0x4001
```
VID untouched (`CONFIG_TINYUSB_DESC_USE_ESPRESSIF_VID=y` → `0x303a`).

### ESP-IDF gotcha hit while applying it
`sdkconfig.defaults` do **not** override an already-generated
`firmware/sdkconfig`. To force the change deterministically, the stale
`firmware/sdkconfig` (and `firmware/build/config/sdkconfig`, if present) were
deleted so the next `idf.py build` regenerated them from defaults.

### Verification
After flash, the device descriptor dump on the UART confirmed
`idVendor 0x303a / idProduct 0x4001`, and the observer showed
`/dev/input/by-id/sleepwalker-hid-keyboard → ../eventN` with udev
`ID_MODEL_ID=4001`.

> **Pre-empt next time:** the firmware MUST explicitly claim its product ID.
> Do not rely on TinyUSB's auto-PID — it is `0x4000 | <class bitmap>` and
> changes whenever a USB class is added/removed.

---

## 5. Observer SSH key not baked into the ISO (Nix flake git-filter gotcha)

### Symptom
`ssh ... observer@10.4.1.129` → `Permission denied (publickey)`. Host
reachable, SSH daemon up, host-key accepted — but my key rejected.

### Root cause
Step 3.3 wrote the pubkey to `nix/observer-authorized_keys`, but the file was
**untracked by git** (`??` in `git status`). Nix flakes filter the flake source
to git-tracked files only, so `builtins.pathExists ./observer-authorized_keys`
in `nix/observer-host.nix` returned **false** at evaluation → the observer
user was built with **zero** authorized keys. The ISO still built cleanly
(silent skip). Classic flake gotcha; the doc's step 3.3 never mentioned
`git add`.

### Fix
1. **Immediate (ephemeral):** at the observer's **physical console**, log in
   as `root` (NixOS ISO allows passwordless root on the console), then:
   ```sh
   mkdir -p /home/observer/.ssh
   echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBRQ+rl0PG5IPcO2I92Z3GB670q1WiBMjwvQ2WJ7/9CQ sleepwalker-observer' > /home/observer/.ssh/authorized_keys
   chown -R observer:observer /home/observer/.ssh
   chmod 700 /home/observer/.ssh && chmod 600 /home/observer/.ssh/authorized_keys
   ```
   This gets SSH working for the session but is **lost on observer reboot**.
2. **Durable:** `git add nix/observer-authorized_keys`, then rebuild the ISO.
   The rebuild log then shows a new `observer-authorized_keys.drv` (absent
   from the first build), proving the key is now baked in. Durable ISO:
   `/nix/store/s85pcnviidx4ps6grxjybqp5rps373fp-nixos-minimal-25.05.20260102.ac62194-x86_64-linux.iso`
   — re-flash the USB with this to make the key survive reboots.

> **Pre-empt next time:** `git add nix/observer-authorized_keys` is a
> mandatory part of step 3.3, not optional. Any file the flake reads via a
> relative path must be git-tracked or it is invisible to Nix. Consider
> inlining the key as a string in `observer-host.nix`
> (`openssh.authorizedKeys.keys = [ "..." ]`) to eliminate the file-vs-git
> hazard entirely.

---

## 6. Tooling sharp edges on NixOS

These aren't design detours, but the exact invocations that worked are
recorded so the next run doesn't repeat the dead-ends.

- **Writing the ISO (`sudo dd`)**: `sudo -n` fails on this host (password
  required). The agent cannot enter the password; the human ran `dd`
  themselves. ISO path is a *directory* in the Nix store — the actual file is
  `<store-path>/iso/nixos-minimal-...x86_64-linux.iso`.
- **`lsusb`** is not installed on the harness host; enumerated USB devices
  from `/sys/bus/usb/devices/*/{idVendor,idProduct,product}` instead.
- **`dmesg`** needs root; avoided.
- **esptool (nixpkgs) is v5.3.0** with a restructured CLI:
  - binary is `esptool`, **not** `esptool.py`
  - `--chip` removed from globals; auto-detect works
  - `--before default-reset` / `--after hard-reset` (dashed, not
    `default_reset`/`hard_reset`)
  - `chip_id` is deprecated → `chip-id` (still works)
  - `nix shell nixpkgs#esptool -c esptool -- <args>` parsed wrong (click
    mis-split the options); wrap in `sh -c`:
    `nix shell nixpkgs#esptool -c sh -c 'esptool --port /dev/ttyUSB0 --before default-reset --after hard-reset chip-id'`
- **pyserial for UART capture**: `nix shell nixpkgs#python312Packages.pyserial
  -c python3` does **not** expose pyserial to the `python3` it runs (system
  python shadows it). Use the classic form:
  `nix-shell -p python312Packages.pyserial --run 'python3 ...'`
  (this sets PYTHONPATH correctly). `nixpkgs#python312.withPackages(ps:
  [ps.pyserial])` was rejected because the local flake's `nixpkgs` input
  shadowed the registry alias.

---

## 7. UART boot-trace capture (Phase 5.2)

The structured JSONL events (`boot`/`ble_init`/`usb_init`/`ready`) are emitted
**once at boot**. They pass within ~300 ms of reset, so a naive "reset with
esptool, then open the port and capture" loses the early events to the
port-release race.

### What worked: reset + capture in one pyserial session
A small script (`/tmp/sw-uart-capture.py`) opens `/dev/ttyUSB0`, pulses the
FT232R DTR/RTS lines to reset the chip into **normal boot** (DTR deasserted →
GPIO0 high; RTS asserted then deasserted → EN low then high), then reads
lines for ~10 s — all in the same process, so no port race. The polarity
matters: with `DTR→GPIO0`, `RTS→EN`, pyserial `s.dtr=False` keeps GPIO0 high
(normal boot, not download), `s.rts=True` then `s.rts=False` pulses EN.

Run with:
```sh
nix-shell -p python312Packages.pyserial --run 'python3 /tmp/sw-uart-capture.py /dev/ttyUSB0 115200 10 /tmp/sw-uart.jsonl'
```

> **Pre-empt next time:** the doc's "capture UART logs for 5 s" (Phase 5.2)
> is only meaningful if the capture owns the port across the reset. The
> `sleepwalker-fw-uart` app captures but does **not** reset and does not
> bundle pyserial; for a clean boot trace use the pyserial reset+capture
> script, or extend `sleepwalker-fw-uart` to pulse DTR/RTS at start.

---

## 8. Three firmware bugs found via the UART trace (Phase 5.2)

Commissioning did its job: the boot trace surfaced three independent defects,
all in firmware (none were hardware/wiring). All fixed by a build-flash-capture
loop (subagent `CurrentMinnow`, first iteration clean).

### Bug 1 — `sw_log` never installed the UART0 driver
**File:** `firmware/components/sw_log/src/sw_log.c`
**Evidence:** `E uart: uart_write_bytes(1629): uart driver error` on every emit.
**Cause:** `sw_log_init()` called `uart_param_config()` but never
`uart_driver_install()`. The IDF console uses UART0 via the ROM path, not the
`driver/uart` driver, so `uart_write_bytes(UART_NUM_0,...)` returned
`ESP_ERR_INVALID_STATE` and every JSONL line was silently dropped — breaking
the Phase 10 ESP-layer correlation.
**Fix:** after `uart_param_config`, add
`uart_driver_install(SW_LOG_UART_NUM, 256, 0, 0, NULL, 0)` tolerating
`ESP_ERR_INVALID_STATE`. IDF logging coexists on UART0.

### Bug 2 — `nvs_flash_init` never called
**File:** `firmware/main/src/main.c` (+ `firmware/main/CMakeLists.txt`)
**Evidence:** `E phy_init: esp_phy_load_cal_data_from_nvs: NVS has not been
initialized. Call nvs_flash_init before starting WiFi/BT.`
**Cause:** `app_main` initialized components in order sw_log → hid_bridge →
safety → usb_hid → ble_uart → worker, with no `nvs_flash_init()`. NimBLE
bonding/key storage and RF calibration persistence depend on NVS.
**Fix:** make `nvs_flash_init()` (with erase-and-retry on
`ESP_ERR_NVS_NO_FREE_PAGES`/`ESP_ERR_NVS_NEW_VERSION_FOUND`) the first
statement of `app_main`; add `#include "nvs_flash.h"` and `nvs_flash` to
main's CMakeLists `REQUIRES`.

### Bug 3 — `ble_gatts_count_cfg` returned BLE_HS_EINVAL (3); BLE never advertised
**File:** `firmware/components/ble_uart/src/ble_uart.c`
**Evidence:** `E sw.ble: count_cfg rc=3`; the init returns early, so
`nimble_port_freertos_init(sw_host_task)` never runs → no advertising → BLE
dead → blocks Phase 7 and the whole command path.
**Root cause (two defects, both required):**
1. **`init_uuid()` parsed hyphenated UUID strings without skipping hyphens.**
   The constants `SW_BLE_*_UUID` are canonical with hyphens
   (`0f1e2d3c-4b5a-6987-8765-4321fedcba98`), but the parser read `p[2*i]`
   directly; the comment falsely claimed "hyphens removed by caller". Bytes
   8–15 came out garbage → service/RX/TX UUIDs collided → `count_cfg`
   EINVAL.
2. **TX characteristic had `.access_cb = NULL`.** NimBLE requires a non-NULL
   `access_cb` for every characteristic, even notify-only ones.

**Fix:**
- Rewrote `init_uuid()` to skip `-` and accept upper+lower hex; still writes
  `u->value[15-i]` (NimBLE little-endian).
- Added `sw_tx_access_cb()` returning `BLE_ATT_ERR_READ_NOT_PERMITTED`
  (NimBLE's constant is `READ_NOT_PERMITTED`, not `READ_UNPERMITTED`; needed
  `#include "host/ble_att.h"`) and set `.access_cb = sw_tx_access_cb` on the
  TX entry.

### Post-fix clean trace (acceptance)
```
{"ts_ms":40,"component":"boot","event":"boot","seq":0}
{"ts_ms":211,"component":"boot","event":"usb_init","seq":0}
{"ts_ms":307,"component":"boot","event":"ble_init","seq":0}
{"ts_ms":317,"component":"cmd","event":"worker_start","seq":0}
{"ts_ms":317,"component":"boot","event":"ready","seq":0}
I sw.main: sleepwalker firmware ready
```
- BLE: `GAP procedure initiated: advertise; disc_mode=2` (general discoverable).
- Benign: `Failed to restore IRKs from store; status=8` = `BLE_HS_ENOENT`
  (empty store on fresh flash) — advertising proceeds normally.
- Observer HID: still `303a:4001` + symlink intact.

> **Pre-empt next time:** a clean UART boot trace is the cheapest
> firmware-health check. Run it after every firmware flash, not just at
> commissioning. The presence of the four `component=boot` JSONL events AND
> the absence of `uart driver error` / `NVS has not been initialized` /
> `count_cfg rc=` is a strong invariant.

---

## Artifacts produced / referenced this run

| Artifact | Path |
|---|---|
| Observer SSH keypair | `~/.ssh/sleepwalker_observer_ed25519{,.pub}` |
| Observer authorized_keys (now git-tracked) | `nix/observer-authorized_keys` |
| Durable observer ISO (key baked in) | `/nix/store/s85pcnviidx4ps6grxjybqp5rps373fp-nixos-minimal-25.05.20260102.ac62194-x86_64-linux.iso` |
| Firmware binary (clean, PID 0x4001 + 3 bug fixes) | `firmware/build/sleepwalker-firmware.bin` |
| APK | `android/sleepwalker-app/build/outputs/apk/debug/sleepwalker-app-debug.apk` |
| UART capture script | `/tmp/sw-uart-capture.py` |
| Observer host | `10.4.1.129` (`observer@…`, key-only SSH) |
| ESP32 UART node | `/dev/ttyUSB0` (FT232R) |
| Android ADB serial | `29211JEGR12028` (Pixel 6a) |

---

## Remaining loose ends (not blocking, but outstanding)

1. **Durable observer re-flash:** the running observer uses the
   console-added key (ephemeral, lost on reboot). Re-flash the USB with the
   durable ISO (`/nix/store/s85pcnv…iso`) so the key survives reboots. Needs
   `sudo dd` (human, password required).
2. **Doc gap:** `COMMISSIONING.md` step 3.3 must instruct `git add
   nix/observer-authorized_keys` (see §5).
3. **Doc gap:** `COMMISSIONING.md` Phase 2.2 assumes GPIO0/EN are pads; for
   ESP-S3-Zero they are not (see §1).
4. **Hardening option:** inline the observer pubkey string in
   `nix/observer-host.nix` (`authorizedKeys.keys = [ "…" ]`) instead of a
   file, to eliminate the flake git-filter hazard permanently.
5. **Firmware regression guard:** consider a build-time assertion in
   `observer-host.nix` that fails loudly if the authorized_keys file is
   absent, rather than silently skipping (the current `builtins.pathExists`
   pattern is the root hazard).

---

## Lessons

- **The HIL loop earned its keep.** Four real firmware bugs (PID, UART
  driver, NVS, GATT table) and one build-system gotcha (flake git-filter)
  were invisible until the device was actually flashed and observed. None
  would have been caught by static review alone.
- **No-hardware investigation is high-leverage.** The PID mismatch and the
  three firmware bugs were all found by reading source + capturing one UART
  trace — before touching the BLE/Android path.
- **One read-only command verifies the whole flash path.**
  `esptool ... chip-id` over `/dev/ttyUSB0` proves GND + TX/RX + DTR/RTS
  wiring in a single shot. Run it immediately after wiring, before any flash.
- **Bake keys, don't file them.** A separate authorized_keys file that must
  be git-tracked is a fragile design; an inlined key string is robust.