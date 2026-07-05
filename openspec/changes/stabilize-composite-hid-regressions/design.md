## Context

The previous change expanded the system from keyboard-only E2E to a composite keyboard+relative-mouse surface. The current evidence shows that mouse can be observed physically, but the regression loop is not yet clean:

- `artifacts/run_mouse_1783219831/summary.json` reports `mouse_smoke` passing with left-button and relative-X evidence.
- The same summary notes the observer is using stale evdev naming: `BTN_LEFT` appears as `KEY_UNKNOWN` and `REL_X` as `CODE_UNKNOWN`; raw codes are used as fallback evidence.
- `artifacts/run_1783219445/summary.json` reports the latest `keyboard_smoke` failing to observe `KEY_SPACE` down/up or correlation.

This change stabilizes the composite HID regression surface before the project expands high-level text/keymap behavior. The goal is not new product functionality; it is trustworthy autonomous evidence for the keyboard+mouse device shape now present in the firmware and app.

## Goals / Non-Goals

**Goals:**

- Make the observer helper discover Sleepwalker keyboard and mouse evdev devices when the ESP32-S3 enumerates as a composite HID device.
- Decode keyboard keys, mouse buttons, and relative axes into stable symbolic names in observer JSONL.
- Provide one combined autonomous smoke that verifies keyboard and mouse output against the same firmware/app build and observer session.
- Emit a machine-readable composite smoke summary with separate keyboard, mouse, observer-device, and correlation evidence.
- Preserve existing bench autonomy: no new human gates for a commissioned bench.

**Non-Goals:**

- No new Kotlin library API behavior.
- No text/keymap expansion.
- No absolute pointer implementation.
- No virtual serial/CDC implementation.
- No protocol frame/opcode changes.
- No new hardware topology.
- No attempt to make the observer consume injected input outside the exclusive-grab test window.

## Decisions

### Decision: Stabilize observation before adding features

The latest artifacts show a passed mouse smoke with symbolic-name caveats and a failed keyboard smoke. That means the next work should target evidence reliability, not new capabilities. High-level text rendering would amplify keyboard traffic and produce noisy failures if the composite observer path remains ambiguous.

Alternatives considered:

- Proceed directly to text/keymap expansion: higher product value, but depends on keyboard smoke correctness.
- Implement absolute pointer or serial next: premature because the existing composite HID proof is not stable.

Chosen shape: one stabilization change focused on observer discovery, decoding, combined HIL smoke, and artifact schema.

### Decision: Discover by descriptor/capability evidence, not event numbers

Composite HID devices can expose multiple evdev nodes. The observer must not assume one `/dev/input/eventX` or a keyboard-only product shape. It should discover matching Sleepwalker devices by stable metadata and event capabilities, then classify nodes by observed capabilities:

- keyboard node: supports `EV_KEY KEY_SPACE` or keyboard usage-derived keys;
- mouse node: supports `EV_KEY BTN_LEFT` and/or `EV_REL REL_X`/`REL_Y`.

If both classes are exposed on one node, the helper should accept that too and report the same device identity for both roles.

### Decision: Fix symbolic evdev naming in the observer helper

Raw-code fallback was enough to diagnose the first mouse pass, but it is not stable evidence. Observer JSONL should report the symbolic names the specs assert: `KEY_SPACE`, `BTN_LEFT`, `REL_X`, `REL_Y`, and `REL_WHEEL`.

Alternatives considered:

- Keep raw code evidence in summaries: less work, but leaks Linux numeric codes into tests and obscures future failures.
- Parse symbolic names only in HIL after capture: workable, but duplicates Linux input decoding outside the observer helper.

Chosen shape: observer helper emits symbolic names directly and can still include raw numeric code fields for debugging.

### Decision: Add a combined smoke, not just separate retries

Separate keyboard and mouse smokes can pass independently while a composite descriptor/device-selection bug remains hidden. The HIL should run keyboard and mouse commands under one scenario, observer session, and artifact directory.

Required evidence:

- `EV_KEY KEY_SPACE 1`
- `EV_KEY KEY_SPACE 0`
- `EV_KEY BTN_LEFT 1`
- `EV_KEY BTN_LEFT 0`
- `EV_REL REL_X` or `EV_REL REL_Y`
- command sequence correlation across Android, ESP UART, HID observer, and summary

### Decision: Firmware changes are conditional

The first implementation pass should diagnose whether the keyboard failure is an observer/HIL matching problem or a real firmware descriptor/report regression. Firmware should only change if the composite descriptor or report IDs are the source of the failure. Regardless, the final accepted state must prove keyboard and mouse output from the same build.

## Risks / Trade-offs

- [Risk] Keyboard failure is caused by firmware report descriptor behavior, not observer matching. → Mitigation: inspect observer device discovery and firmware UART evidence first; only adjust firmware if the HID report stream is actually wrong.
- [Risk] The observer host may expose keyboard and mouse as separate evdev nodes or one combined node depending on descriptor shape. → Mitigation: classify by capabilities and allow either topology.
- [Risk] Updating the observer helper does not update the running observer ISO. → Mitigation: ensure the HIL invokes the project-provided helper path or rebuilt ISO/package, and record helper version/path in artifacts.
- [Risk] Combined smoke can obscure which half failed. → Mitigation: summary separates keyboard, mouse, observer-device, and correlation sections.
- [Risk] Exclusive grab on the wrong node can allow injected input to reach the observer session. → Mitigation: grab every matched Sleepwalker evdev node used for the scenario.

## Migration Plan

1. Add/repair observer helper evdev symbolic-name tables for keyboard, button, relative-axis, and sync events.
2. Update observer discovery/classification to identify Sleepwalker keyboard and relative mouse nodes from descriptor/capability evidence.
3. Add composite smoke orchestration that starts one observer session, sends keyboard and mouse commands, and parses both event classes.
4. Tighten artifact summary schema and preserve raw numeric fields for debugging.
5. Diagnose whether the keyboard failure is observer-only or firmware descriptor/report behavior; patch firmware only if needed.
6. Accept the change only when combined smoke passes and the existing standalone keyboard and mouse smokes remain understandable.

Rollback strategy: revert HIL/observer stabilization while retaining existing keyboard and mouse implementation. Because the protocol and public library API are unchanged, rollback does not affect device command compatibility.

## Open Questions

None blocking. The implementation should determine whether the failed keyboard artifact is due to observer matching or firmware HID descriptor behavior.

## Implementation Determination (Phase 4)

The keyboard failure in `artifacts/run_1783219445` was diagnosed as an **observer-matching + stale-helper** issue, NOT a firmware HID descriptor/report regression:

- Android logcat for the failed run shows seq 2 `inject USB_KEY_SPACE` → `frame encode` (opcode `key_tap`) → `ack received` → `ack queued` → `ack sent_to_usb` (status 3). The firmware accepted the BLE command and emitted the keyboard report over USB.
- The earlier keyboard smoke `artifacts/run_1783211668` observed `KEY_SPACE` down/up cleanly when `/dev/input/by-id/sleepwalker-hid-keyboard` resolved to the actual keyboard node (`Sleepwalker Sleepwalker HID Keyboard`).
- In the failed run, the same symlink resolved to the **mouse** node (`Sleepwalker Sleepwalker HID Composite Mouse`); only `EV_MSC` events appeared. The composite descriptor splits keyboard and mouse into separate evdev nodes, and the observer was watching the wrong one.
- The mouse smoke `artifacts/run_mouse_1783219831` observed `BTN_LEFT` (code 272) and `REL_X` (code 0) as `KEY_UNKNOWN`/`CODE_UNKNOWN`, proving the deployed observer binary predates the symbolic-decoding cases already present in `nix/observer-helper-src/observer-helper.c`.

Decision: firmware behavior is unchanged. The fix is entirely on the observer/HIL side (capability-based discovery, multi-node observation, rebuilt helper baked into the ISO, structured device-found events recording helper version/path). The composite HID descriptor (`TUD_HID_REPORT_DESC_KEYBOARD` report ID 1 + `TUD_HID_REPORT_DESC_MOUSE` report ID 2) preserves keyboard output while relative mouse support is active, satisfying the spec scenario.
