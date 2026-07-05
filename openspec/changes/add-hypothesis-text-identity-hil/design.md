## Context

Sleepwalker currently has fixed HIL smokes for keyboard, text, mouse, and composite behavior. The high-level text smoke sends `aA1` and verifies the expected evdev key sequence on the observer host. That catches protocol, timing, and event-order regressions for one example, but it does not prove the stronger product invariant: generated supported text sent through the real app/library/BLE/firmware/USB path renders as the same text on the target computer.

The commissioned bench already has the required physical loop: harness host drives Android over ADB, Android sends BLE frames to ESP32-S3, ESP32-S3 emits native USB HID to the observer host, and the observer host is SSH-reachable for evidence collection. The observer ISO is headless (`services.xserver.enable = false`) and autologs into a console user, making a Linux console text sink the most reliable first target-output oracle.

The first identity backend is intentionally narrow: Linux console keymap `us`, printable seed-US text only, direct ADB text path only, and bounded Hypothesis examples suitable for end-of-iteration verification.

## Goals / Non-Goals

**Goals:**

- Add an end-to-end property-based text identity HIL scenario: generated supported input text equals captured target output text.
- Use Hypothesis as the generator/shrinker for bounded examples over the implemented `linux:us` printable text domain.
- Use a Linux console raw-mode capture sink on the observer host as the first true target-output oracle.
- Keep evdev observation available during identity runs as a non-exclusive diagnostic side channel, not as the primary assertion.
- Add a lossless encoded ADB text payload path so generated punctuation and shell metacharacters reach Android unchanged.
- Produce artifacts that classify failures by layer: input corruption, planning/rejection, transport/firmware, target render mismatch, timeout, or infrastructure failure.
- Provide quick and deep execution profiles, with the quick profile suitable for the end of each implementation iteration.

**Non-Goals:**

- No GUI, X11, Wayland, terminal-emulator, or XKB desktop identity backend in this change.
- No all-layout sweep beyond the current `linux:us` seed profile.
- No Unicode, dead keys, compose sequences, AltGr, IME behavior, or control-key identity semantics.
- No UI text-field property testing; the property runner uses the direct ADB command path.
- No protocol frame layout change and no firmware text/layout awareness.
- No replacement of existing fixed keyboard, text, mouse, or composite smokes.
- No per-example ESP reset or BLE reconnect; setup is amortized across the property run.

## Decisions

### Decision: Use a Linux console raw-mode text sink as the first identity oracle

The observer host will run a small raw-mode text sink on the active virtual console. The sink records the bytes rendered by the Linux console input path after the ESP32-S3 emits USB HID keyboard reports. The harness resets and reads the sink over SSH.

Alternatives considered:

1. **Evdev-to-text decoding:** Faster and close to existing observer code, but it is not true target output and risks testing a model against itself.
2. **GUI/XKB text field:** Closer to desktop user behavior, but it adds focus, display server, and window management flake to the first property loop.
3. **Linux console raw-mode sink:** Headless, deterministic enough for the existing observer ISO, and validates real target-side text rendering for the first Linux profile.

Chosen shape: Linux console sink first; keep evdev logs as diagnostics only.

### Decision: Do not use exclusive evdev grab during identity tests

Existing smokes use `--grab` so HID events do not affect the observer session. Identity tests must allow the Linux console to receive the HID input. The identity runner will run any evdev observer without `--grab`, while the raw-mode console sink is the primary target-output capture.

### Decision: Add a lossless encoded ADB text input path

The current text command path accepts a string extra. Property-generated printable ASCII includes spaces, quotes, backslashes, pipes, semicolons, dollar signs, and other shell-sensitive characters. The identity runner needs to transport generated text as bytes rather than shell syntax.

The ADB command path will accept an encoded text extra, such as base64url or hex UTF-8 bytes, decode it in `AdbCommandReceiver`, log both command identity and decoded text metadata, and then pass the decoded string into the existing `sleepwalker-core` text path.

The old plain text extra can remain for compatibility with existing smoke commands, but the Hypothesis identity runner must use the encoded path.

### Decision: Scope the first generated alphabet to printable seed-US text

The first property domain is printable ASCII supported by the current seed US QWERTY keymap. ESC and other control keys are excluded because they are key actions, not stable text-output identity characters. Newline is deferred until raw-mode console byte behavior is characterized.

This keeps the first property focused on text identity rather than terminal control semantics.

### Decision: Amortize bench setup across examples

The runner will validate the bench, reset ESP32-S3, start logs, start the sink, connect BLE, and arm once per run. Each Hypothesis example will reset the sink, send one generated string, wait for stable output, compare, and collect per-example evidence. This avoids making full smoke orchestration the inner Hypothesis loop.

### Decision: Replay failing hardware examples before reporting a counterexample

Physical E2E tests can fail transiently. On a mismatch, timeout, or missing output, the runner will replay the same generated string before presenting it as a product counterexample. Reproducible failures are reported as property failures. Non-reproducible failures are classified as infrastructure/timing instability with preserved artifacts.

### Decision: Keep quick and deep profiles separate

The quick profile is bounded for end-of-iteration use, initially targeting a small number of examples and short strings. The deep profile increases examples and lengths for manual or longer-running validation. Both profiles write the same artifact schema so failures are replayable.

## Risks / Trade-offs

- [Risk] The Linux console keymap is not identical to desktop XKB behavior. -> Mitigation: declare the first backend as `linux-console` and do not claim desktop-layout coverage until an XKB backend exists.
- [Risk] The active observer shell could receive generated text as commands. -> Mitigation: run the raw-mode sink on the active VT before any identity input and verify sink readiness before sending text.
- [Risk] Terminal line discipline transforms input. -> Mitigation: set raw mode, disable echo/canonical/signals, and initially exclude newline/control characters.
- [Risk] Shell quoting corrupts generated input before Android receives it. -> Mitigation: use encoded text extras for the property runner and record Android-received text metadata.
- [Risk] Non-exclusive evdev observation lets injected text reach userspace. -> Mitigation: use only the sacrificial observer host and the dedicated raw sink; existing fixed smokes can continue using exclusive grab.
- [Risk] Hypothesis shrinking interacts poorly with flaky hardware timing. -> Mitigation: replay failing examples and classify non-reproducible failures separately from deterministic counterexamples.
- [Risk] The quick profile becomes too slow for end-of-iteration use. -> Mitigation: use one setup per run, bounded example counts, bounded string length, and output-length polling instead of fixed sleeps.

## Migration Plan

1. Add observer text sink tooling and package it into the observer ISO environment.
2. Add SSH control helpers for starting/resetting/reading the sink and setting the Linux console keymap to `us`.
3. Add encoded text support to the ADB command receiver and Nix ADB adapter while preserving existing plain text behavior.
4. Add the Hypothesis HIL identity runner with quick/deep settings, session-scoped setup, per-example sink reset, target-output comparison, replay-on-failure, and artifact writing.
5. Keep existing text smoke and composite smoke unchanged as fixed regression scenarios.
6. Verify with a commissioned bench using the quick profile, then run fixed text/composite smokes as regression evidence.

Rollback is straightforward: existing smoke commands remain available; disabling the new identity runner does not affect protocol, firmware, app runtime behavior, or fixed smokes.

## Open Questions

- Whether newline should enter the first text identity alphabet after raw-mode sink byte behavior is measured.
- Whether the encoded payload should use base64url or hex; both are acceptable if they avoid shell-sensitive characters and preserve UTF-8 bytes exactly.
- Whether the quick profile default should start at 20, 25, or another bounded example count after measuring actual bench runtime.
