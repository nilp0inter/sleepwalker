## Why

Batch-mode text input was bottlenecked by conservative timing constants: the HID endpoint polled every 10 ms and tap scripts held each state for 15 ms, capping throughput at ~33 taps/s. The Android app added 500 ms of dead time between batches, causing visible stutter. With the firmware now proven stable at faster timing, the constants should be tightened and the inter-batch gap matched to the firmware drain rate so text streams without stutter or dropped frames.

## What Changes

- Reduce HID endpoint `bInterval` from 10 ms to 4 ms, letting the host poll 2.5× more often.
- Reduce `SW_TAP_SCRIPT_PRESS_MS` and `SW_TAP_SCRIPT_GAP_MS` from 15 ms to 6 ms (each state held 2 ms over the poll interval), cutting per-tap time from 30 ms to 12 ms.
- Replace the 500 ms inter-batch `Thread.sleep` in the Android app (both ADB and UI paths) with 390 ms — matched to the firmware drain time for a 32-tap batch (32 × 12 ms = 384 ms) — so the next batch arrives just as the current one finishes, keeping the 16-deep `hid_bridge` queue at ~1 item without overflow.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `esp32-s3-hid-firmware`: Tightens the tap-script timing requirement — the HID endpoint polling interval and per-state hold times are reduced to increase batch throughput while preserving the invariant that each state is held longer than the poll interval.
- `android-ble-companion`: Adds a requirement for inter-batch pacing matched to firmware drain rate, replacing the conservative fixed 500 ms delay that caused stutter with a drain-rate-matched delay that prevents queue overflow on long texts.