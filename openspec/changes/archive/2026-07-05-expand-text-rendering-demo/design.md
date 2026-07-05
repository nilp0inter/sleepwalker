## Context

The project now has a stable physical low-level loop: keyboard, relative mouse, and composite HID regression all have structured evidence. `sleepwalker-core` also has the beginnings of a high-level text surface: host profiles, a seed keymap database, structured rendering failures, and an inspectable `TextPlanner`. The current planner is intentionally shallow: keymap entries can carry modifier metadata, but planning currently emits one key tap per character and does not yet use modifiers.

The Android reference app currently has an empty `MainActivity`; it is usable through ADB/HIL but does not yet give a human a morale-building product demo. This change turns the seed text path into something visible: type valid characters into the Android app and the target host receives physical HID keystrokes through the same library path.

## Goals / Non-Goals

**Goals:**

- Implement modifier-aware text planning for a complete seed US QWERTY printable subset.
- Expand symbolic keyboard usage coverage needed by the seed text planner.
- Preserve inspectable text plans and structured all-or-nothing rendering failures.
- Add a minimal Android reference UI with connect, arm, kill/status affordances and a text input that streams inserted valid characters.
- Verify high-level text through HIL by asserting HID key event sequences on the observer host.

**Non-Goals:**

- No full global keymap corpus ingestion.
- No layout selector or persisted profile settings.
- No polished UI, Compose migration, XML layout system, navigation, or broad app architecture rewrite.
- No deletion/backspace mirroring from Android text edits to the target host.
- No dead keys, AltGr, compose sequences, IME handling, or Unicode fallback.
 - No absolute mouse, virtual serial, or protocol frame changes.
- No Maven/public release automation.

## Decisions

### Decision: Complete the seed engine before corpus ingestion

The seed US QWERTY profile should prove the keymap model before a full corpus is ingested. The planner must demonstrate direct keys, shifted keys, punctuation, controls, and structured failures against a small but complete data model.

Alternatives considered:

- Start global keymap ingestion now: higher long-term value, but risks locking the public API to an unproven data shape.
- Keep the seed planner single-tap-only: easy, but the high-level API remains too fake to demonstrate real text input.

Chosen shape: complete US QWERTY printable ASCII plus selected controls first; defer corpus generation.

### Decision: Use modifier key operations in plans

Keymap entries already contain modifier masks. The planner should translate those masks into explicit low-level operations around taps:

- no modifiers: `KEY_TAP(usage)`;
- Shift modifier: `KEY_DOWN(LEFT_SHIFT)`, `KEY_TAP(usage)`, `KEY_UP(LEFT_SHIFT)`.

This keeps the firmware dumb and makes the plan inspectable. The same pattern can later extend to AltGr, dead keys, and compose sequences.

### Decision: Track active modifiers in firmware

Because the protocol `KEY_TAP` / `KEY_DOWN` carries only a single `usage` byte and the standard USB HID keyboard report is stateful, the firmware's report generation was updated to track active modifiers in `s_active_modifiers` and write them to `report[0]`. This allows modifier states (like Shift) to persist across report updates, ensuring shifted keys are typed correctly in uppercase.

Alternatives considered:
- Set modifiers dynamically in companion app: requires multi-key protocol frames or a protocol payload layout redesign, increasing complexity.
- Keep single-key reports: uppercase letters are received as lowercase on the host.

Chosen shape: track active modifier state in firmware.

### Decision: Stream inserted characters, not text-field state

The reference UI is a keystroke streamer, not a remote text editor. It should watch the text input, compute inserted deltas, validate/plans those inserted characters, and send only those characters.

Deletion behavior is intentionally local-only for this change. Mirroring deletions would make selecting all/clearing the Android field emit destructive backspace bursts on the target host.

### Decision: Fixed host profile in the first UI

The minimal UI should use one visible fixed host profile label such as `US QWERTY seed`. There is no selector yet. The profile choice is explicit to users and tests, but not configurable in this slice.

### Decision: Minimal platform UI, no UI framework expansion

Use ordinary Android views created programmatically from `MainActivity` or the smallest existing AppCompat-compatible approach. Do not introduce Compose, navigation, layout XML, or new UI dependencies solely for this demo.

### Decision: Share the same app/library command path

The UI must not build protocol frames by hand or own independent BLE semantics. It should use `sleepwalker-core` text planning and the same app session/transport path as existing ADB commands. If necessary, factor a small app-local controller so `AdbCommandReceiver` and `MainActivity` both call the same send functions.

### Decision: HIL verifies HID sequence, not rendered host text

The observer uses exclusive grab, so text smoke should assert the physical key events that would produce the text on the target layout. It should not rely on a shell or text field receiving rendered characters.

## Risks / Trade-offs

- [Risk] Android `input text` escaping makes punctuation hard to drive in UI HIL. → Mitigation: use a small UI smoke string such as `aA1` and cover punctuation in core unit tests.
- [Risk] Factoring shared app session code could break existing ADB smoke commands. → Mitigation: keep existing command names and run composite smoke regression after the refactor.
- [Risk] UI text watcher can resend existing text after rotation or programmatic updates. → Mitigation: track previous text, only send inserted deltas, and avoid automatic resend on initial render.
- [Risk] Invalid characters remain visible in the Android text field although not sent. → Mitigation: show an explicit last-error message; do not silently approximate or send partial operations.
- [Risk] Shift down/up planning can leave modifiers stuck if a later operation fails mid-send. → Mitigation: build all-or-nothing plans before sending; keep existing kill/release controls visible in the UI.

## Migration Plan

1. Expand symbolic usage registries and seed keymap entries.
2. Update `TextPlanner` to emit modifier-aware low-level operations and atomic failures.
3. Add unit tests for direct, shifted, punctuation, missing-layout, and unrepresentable-glyph cases.
4. Add a minimal app command/session boundary usable by both ADB and `MainActivity` if current code cannot be shared directly.
5. Add the programmatic UI and insertion-only text streaming behavior.
6. Add HIL text smoke through the public app/library path and preserve composite smoke regression.

Rollback strategy: revert UI/text planner expansion while keeping low-level keyboard/mouse and composite HID behavior unchanged. No frame, firmware, or hardware migration is required.

## Open Questions

None blocking. The exact UI layout can remain intentionally plain as long as it exposes connect, arm, kill/status, fixed host profile, text input, and last sent/error feedback.
