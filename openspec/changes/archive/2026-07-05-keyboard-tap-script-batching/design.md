## Context

The current Sleepwalker text streaming mechanism sends keyboard operations one at a time over BLE. For every character, the Android companion app performs up to 3 BLE writes (mod down, key tap, mod up) and sleeps 100 ms between writes to prevent queue overruns. This results in very poor text injection performance (under 10 chars/sec for lowercase, and under 3 chars/sec for uppercase/punctuation).

To improve performance without introducing layout awareness or text decoding to the firmware, we need a compact, layout-resolved keyboard tap script format. This design batch-sends multiple keyboard tap records (modifier mask + USB usage) in a single BLE command frame. The firmware executes the key taps locally using deterministic timing, bypassing app-side network and scheduling jitter.

## Goals / Non-Goals

**Goals:**
- Accelerate text injection speed by at least 10x.
- Keep the ESP32-S3 firmware layout-unaware (it only processes modifier masks and USB usages).
- Maintain simple firmware parsing and queue semantics.
- Ensure safety transitions (disarm, kill, BLE disconnect) abort active scripts immediately.

**Non-Goals:**
- No mouse operation batching.
- No general-purpose macro sequencing (no branching, conditional loops, or nested operations).
- No multi-packet frame reassembly in the firmware (keep frames within single-write BLE MTU limits).

## Decisions

### Decision: Keyboard-specific tap script opcode (`KEYBOARD_TAP_SCRIPT`)
We choose to implement a specialized, compact keyboard tap script opcode instead of a generic event batching protocol.
- *Alternatives considered:*
  1. *Generic event batching:* Emits raw nested operations. This requires a complex nested command parser in the firmware, increases overhead per event, and introduces risks of stuck modifier keys if an operation fails mid-batch.
  2. *Firmware-side text rendering:* Sends raw UTF-8 strings. This violates the architectural boundary that the firmware remains layout-unaware, bloating the firmware with keymaps and host layout mapping.
- *Rationale:* Tap scripts are highly compact (2 bytes per character) and represent atomic press-and-release actions. This guarantees modifiers are released and avoids stuck key states on errors.

### Decision: Bounded batch size fitting single BLE write (no reassembly)
We limit the maximum number of taps in a single script frame so that the entire frame fits within a single BLE ATT MTU write (233 bytes payload for a negotiated MTU of 247).
- *Alternatives considered:*
  1. *Implement multi-write frame reassembly:* Allows arbitrarily large frames but increases firmware RX memory buffer complexity and state tracking.
- *Rationale:* A single write can carry up to 110+ characters. Long text can be easily split into consecutive script frames by the companion app, eliminating the need for complex reassembly code in the firmware.

### Decision: Fixed firmware-local press and gap timing
The firmware will execute each tap using fixed timing constants (15 ms press dwell, 15 ms inter-character release gap) instead of sending delay parameters in the payload.
- *Alternatives considered:*
  1. *Dynamic timing parameters in payload:* Increases record size and adds parsing complexity.
- *Rationale:* HIL on the observer host showed the HID endpoint interval is 10 ms. A 2 ms release gap lets Linux miss release states and coalesce/drop repeated-key taps (for example `aA1` lost the shifted `A`). Holding both press and release states for 15 ms keeps each state observable while still yielding ~33 characters per second.

### Decision: App-side chunking and pacing
For text strings longer than the batch limit, the companion app splits the plan into multiple script frames and paces frames with a conservative 500 ms inter-batch delay. The app no longer sleeps between individual characters.
- *Rationale:* This avoids firmware queue overruns with the existing send path while keeping the first batching slice simple. Status-driven pacing on `SENT_TO_USB` remains a future refinement once the app has a command-await primitive.

## Risks / Trade-offs

- [Risk] stuck modifiers if a script is aborted.
  - *Mitigation:* The firmware's safety disarm, kill, and BLE disconnect callbacks must explicitly perform a keyboard release report immediately, aborting any active script loop.
- [Risk] Host OS dropping keys due to fast timing.
  - *Mitigation:* HIL verified that 2 ms release gaps are unsafe with the 10 ms HID endpoint interval. The firmware now holds press and release states for 15 ms each.
- [Risk] Status flood.
  - *Mitigation:* The firmware will notify status at the batch level (RECEIVED, QUEUED, SENT_TO_USB) using the batch sequence ID, preserving context bytes to log the execution count.

## Migration Plan

1. **Protocol:** Define `SW_OPCODE_KEYBOARD_TAP_SCRIPT` (0x0014) in C, Kotlin, and Python helpers.
2. **Firmware:** Update `sw_rx_write_cb` to parse the new opcode and enqueue it. Update `sw_hid_worker_task` to execute the tap loop.
3. **Core:** Implement `KeyboardTapScript` serializer and chunker in `sleepwalker-core`.
4. **App:** Refactor `MainActivity` and `AdbCommandReceiver` to compile text plans into scripts and transmit them.
5. **HIL:** Update the text smoke test to verify correct event sequences and timing.

## Open Questions

- Resolved by HIL: the inter-character release gap must exceed the 10 ms HID endpoint interval; 15 ms is the current safe value.
