## 1. Firmware HID endpoint timing

- [x] 1.1 Reduce `bInterval` in `TUD_HID_DESCRIPTOR` from 10 to 4 in `firmware/components/usb_hid/src/usb_hid.c`
- [x] 1.2 Reduce `SW_TAP_SCRIPT_PRESS_MS` from 15 to 6 in `firmware/main/src/main.c`
- [x] 1.3 Reduce `SW_TAP_SCRIPT_GAP_MS` from 15 to 6 in `firmware/main/src/main.c`
- [x] 1.4 Update the timing comment above the constants to reflect 4 ms poll interval and 6 ms hold

## 2. Android inter-batch pacing

- [x] 2.1 Change `Thread.sleep(500)` to `Thread.sleep(390)` in `AdbCommandReceiver.kt` type-text path
- [x] 2.2 Change `Thread.sleep(500)` to `Thread.sleep(390)` in `MainActivity.kt` streamText path

## 3. No-hardware verification

- [x] 3.1 Run `sleepwalker-protocol-check` — verify protocol parity unchanged
- [x] 3.2 Run `sleepwalker-fw-build` — verify firmware compiles with new timing
- [x] 3.3 Run `sleepwalker-apk-build` — verify APK compiles with new pacing

## 4. End-to-end validation

- [x] 4.1 Flash firmware to ESP32-S3 via `sleepwalker-fw-flash`
- [x] 4.2 Install APK via `sleepwalker-apk-install`
- [x] 4.3 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml`
- [x] 4.4 Inspect `artifacts/run_composite_<timestamp>/summary.json` for pass evidence
- [x] 4.5 Verify long-text streaming completes without dropped frames (no `STATUS_QUEUE_FULL` in `esp_uart.jsonl`)