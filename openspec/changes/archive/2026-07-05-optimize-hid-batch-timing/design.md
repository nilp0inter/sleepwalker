## Context

The firmware HID endpoint uses a 10 ms `bInterval` and tap scripts hold each press/release state for 15 ms, giving 30 ms per tap. The Android app sleeps 500 ms between tap-script batches. This was chosen conservatively when batching was first implemented (commit `952d907`). E2E testing on the commissioned bench confirmed the firmware is stable at 4 ms `bInterval` with 6 ms per-state holds, and that 390 ms inter-batch pacing keeps the 16-deep `hid_bridge` queue from overflowing on long texts.

## Goals / Non-Goals

**Goals:**
- Increase batch-mode text throughput by reducing per-tap time from 30 ms to 12 ms.
- Eliminate inter-batch stutter by matching the Android sleep to the firmware drain rate.
- Preserve the invariant that each HID report state is held longer than the endpoint poll interval.

**Non-Goals:**
- Changing the BLE transport, frame format, or protocol opcodes.
- Adding flow-control feedback from firmware to app (e.g., app waits for `SENT_TO_USB` before sending the next batch). The 390 ms fixed pacing is sufficient for the current queue depth and batch size.
- Changing the `hid_bridge` queue depth (16) or `maxBatchSize` (32).
- Optimizing non-batch opcodes (`KEY_TAP`, `KEY_DOWN`, mouse commands).

## Decisions

### Decision 1: Reduce `bInterval` from 10 ms to 4 ms

The HID endpoint `bInterval` in `TUD_HID_DESCRIPTOR` controls how often the USB host polls the device. A lower interval lets the host observe report state changes faster, which is the prerequisite for shortening per-tap hold times.

**Why 4 ms:** USB 2.0 full-speed endpoints allow `bInterval` values from 1–255 in 1 ms units. 4 ms is the lowest power-of-two value that stays stable across Linux, macOS, and Windows HID stacks. 1 ms works on Linux but risks enumeration issues on some macOS/Windows hosts.

**Alternative considered:** Keep `bInterval` at 10 ms and only reduce hold times. Rejected — the hold time must exceed the poll interval, so with 10 ms polling the minimum safe hold is ~12 ms, giving 24 ms/tap instead of 12 ms/tap.

### Decision 2: Reduce per-state hold from 15 ms to 6 ms

`SW_TAP_SCRIPT_PRESS_MS` and `SW_TAP_SCRIPT_GAP_MS` control how long the firmware holds each press and release report before the next state change. With `bInterval` now 4 ms, holding each state for 6 ms gives a 2 ms safety margin — the host is guaranteed to poll at least once during each state.

**Why 6 ms not 5 ms:** FreeRTOS tick is 1 ms (`CONFIG_FREERTOS_HZ=1000`), so `pdMS_TO_TICKS(5)` = 5 ticks. But `vTaskDelay` rounds down the actual delay by up to 1 tick depending on where in the tick window the call lands. 6 ms guarantees ≥ 5 ms of actual hold time, which is > 4 ms poll interval.

### Decision 3: Inter-batch pacing of 390 ms

The Android app sends compiled tap-script batches in a loop with `Thread.sleep` between them. The firmware drains a 32-tap batch in 32 × 12 ms = 384 ms. With a 16-deep queue, sending faster than the drain rate eventually fills the queue and triggers `STATUS_QUEUE_FULL`, dropping frames.

**Why 390 ms:** 384 ms drain time + 6 ms margin. The next batch arrives just as the current one finishes, keeping the queue at ~1 item. This was validated E2E: 20 ms caused dropped frames on long texts; 390 ms streams without loss.

**Alternative considered:** Flow-control via `SENT_TO_USB` status notifications. The firmware already sends `SW_STATUS_SENT_TO_USB` after each batch completes. The app could wait for this notification before sending the next batch. This would be optimal but requires restructuring the send loop from fire-and-forget to request-response, adding complexity for marginal gain over the fixed 390 ms pacing.

## Risks / Trade-offs

- **Risk: 4 ms `bInterval` may fail on some hosts.** Mitigated: validated E2E on the Linux observer host. If a future target host (macOS/Windows) fails to enumerate, `bInterval` can be raised to 8 ms with proportional hold-time adjustment.
- **Risk: 390 ms fixed pacing is brittle if batch size or per-tap timing changes.** If `maxBatchSize` or `SW_TAP_SCRIPT_*_MS` constants change, the 390 ms value must be recomputed as `maxBatchSize × (PRESS_MS + GAP_MS) + margin`. This coupling is documented in code comments but not enforced programmatically.
- **Trade-off: Fixed pacing underutilizes the queue for short texts.** For a 2-batch text, the second batch could be sent immediately (queue depth 16 > 2). The fixed 390 ms adds one unnecessary gap. Acceptable: the common case is longer text where pacing matters.