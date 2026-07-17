## Context

The Android reference app currently exposes one `EditText`. Its `TextWatcher` sends only the inserted substring through `TextPlanner` and `TapScriptCompiler`; deletion and clearing remain local by design. The app now also has a service-owned `Editor` configured with the bundled `readline-emacs-ascii` target, a `BleEditorExecutor`, an app-wide `editorLock`, structured tracing, and ADB `set-text`/`reset-editor` paths, but the UI does not exercise them.

`Editor.setText` is synchronous and accepts complete snapshots. It serializes plans, supports printable ASCII on a single line, commits its assumed target state only after complete delivery, leaves planning failures recoverable, and enters terminal `Unknown` after partial execution. The Android UI must not block its main thread, reorder rapid text changes, duplicate BLE transport, or imply that `Editor.reset()` physically clears an unknown target.

## Goals / Non-Goals

**Goals:**

- Add an explicit choice between the existing append-only stream and a GNU Readline editor mode.
- Submit every accepted UI text change as an immutable complete snapshot in original callback order.
- Reuse the service-owned Editor, target package, serialized BLE executor, safety state, and status feedback.
- Keep append-only behavior backward-compatible.
- Make Editor constraints, state, failure, and reset semantics visible enough for a user to operate the demo safely.
- Provide deterministic unit and physical-bench coverage of the actual UI path.

**Non-Goals:**

- Changes to `sleepwalker-core`, the Lua target package, firmware, protocol framing, or BLE behavior.
- Unicode, multiline editing, Vi mode, history, completion, submission, selection semantics, or wordwise optimization.
- Coalescing or debouncing intermediate snapshots; every UI change remains observable by the Editor.
- Inferring or reading the target buffer from the Android app.
- Automatically recovering an unknown target or claiming that `Editor.reset()` clears the physical Readline buffer.
- Generalizing the UI into a target-package browser or production text editor.

## Decisions

### 1. Keep two explicit text semantics behind one mode selector

Introduce an internal `DemoTextMode` with `APPEND_ONLY` and `READLINE_EDITOR`. Keep append-only as the default so existing manual and smoke workflows retain their current behavior. The existing `TextWatcher` routes inserted substrings to `streamText` only in append-only mode; in Readline editor mode it routes `s.toString()` after every change to the Editor path, including deletions, replacement, paste, and clearing.

The UI shows the active semantics and the fixed Editor target identity (`readline-emacs-ascii`, GNU Readline 8.2, Emacs, printable ASCII, single-line). Once a mode has emitted a target-mutating operation, the selector is locked for that session. An explicit session reset clears local UI state and unlocks selection only after the user has independently restored the target to an empty Readline buffer.

This prevents an append-only write from silently invalidating the Editor's assumed document. Allowing unrestricted switching was rejected because the app has no read-back channel and cannot reconstruct target state after append-only operations. Replacing append-only behavior was rejected because it remains a distinct supported demo and smoke path.

### 2. Use one service-owned FIFO for asynchronous UI Editor calls

Add a service-owned single-thread command lane for UI Editor submissions. A submission captures the complete immutable string on the main thread, assigns a monotonically increasing UI change id, and executes `Editor.setText` under the existing `editorLock`. The lane returns the result to the main thread through a callback carrying the change id and requested snapshot. Calls are not coalesced.

A single FIFO is required because starting one thread per `TextWatcher` callback plus a lock does not guarantee callback acquisition order. Running `setText` on the main thread was rejected because BLE acknowledgement waits can block for multiple operations. Activity callbacks use a lifecycle generation token so a destroyed or mode-reset activity ignores stale presentation updates; execution already admitted to the service lane is allowed to finish rather than being interrupted mid-plan.

### 3. Gate editing on connection and safety without duplicating transport

Readline editor input is enabled only while the shared service reports a connected GATT session and `SafetyState.ARMED`. The UI listens to the existing connection/status callbacks and updates input availability. A snapshot is executed only through `SleepwalkerBleService.editor` and its existing `BleEditorExecutor`; the activity constructs no protocol frame and sends no Editor plan operation directly.

The guard avoids turning an ordinary pre-connect or pre-arm keystroke into a partial execution and terminal `Unknown`. The executor remains authoritative and rechecks connection/safety immediately before emission to cover races. Append-only mode retains its current connection/error behavior.

### 4. Map Editor outcomes to explicit UI state

`EditorResult.Synced` updates feedback with the synchronized snapshot/change id and leaves input enabled. A pre-execution `EditorFailure` reports its classification and leaves input enabled because the prior assumed target remains valid. A transport or partial-execution failure that leaves `Editor.state() == Unknown` reports that the target state is unknown, disables further editor input, and enables an explicit reset action.

Reset is enqueued on the same FIFO after prior submissions. It invokes `Editor.reset()`, clears verification/UI session state, and re-enables mode selection. The UI states that reset establishes an empty assumption only and requires the physical Readline target to have been restored to empty first. Automatic retry, rollback, or full retyping was rejected because any of them can compound target corruption when delivery was partial.

### 5. Keep constraints in the Editor and make them visible

Configure the field as single-line and label the mode as printable ASCII, but do not duplicate the Editor's validation rules in activity code. The complete snapshot always reaches `Editor.setText`; unsupported text produces the existing structured planning failure and no HID operations. This preserves one source of truth and allows the demo to expose validation behavior.

### 6. Verify the UI path through a test seam and the Readline fixture

Keep mode routing and FIFO result handling behind small internal functions or an internal controller with injected Editor operations and main-thread delivery. Robolectric/unit tests cover mode routing, complete snapshots for all edit shapes, strict FIFO ordering, no coalescing, outcome presentation, mode isolation, and reset ordering without BLE hardware.

Add a stable launch intent extra selecting Readline editor mode for HIL; it selects the same UI mode and does not bypass `TextWatcher`. Extend the existing Editor conformance smoke infrastructure with a fixed UI scenario that resets the real Readline fixture, launches `MainActivity` in editor mode, performs insertion/deletion/replacement/clear operations through Android input, waits for Editor completion diagnostics, emits the existing F24 barrier, and compares the fixture snapshot after each step. The existing ADB property scenario remains unchanged. Final validation also runs the required composite smoke to prove unaffected keyboard/mouse/session behavior.

## Risks / Trade-offs

- [Rapid typing can build a FIFO longer than USB can drain] → Show pending/synchronized feedback and preserve every snapshot as required; do not hide backlog through coalescing.
- [Mode mixing can desynchronize assumed state] → Lock mode after the first target mutation and require an explicit empty-target session reset.
- [Activity recreation can receive obsolete callbacks] → Tag submissions with a lifecycle generation and ignore stale UI delivery while allowing admitted Editor work to complete.
- [Connection or safety can change after the UI enables input] → Recheck in `BleEditorExecutor`; surface `Unknown` and stop further input after partial execution.
- [A local reset can be mistaken for a remote clear] → Label the empty-target precondition and never claim the app observed or cleared the target.
- [UI HIL automation can become coordinate-dependent] → Select mode through a stable launch extra and verify behavior through Editor diagnostics plus authoritative fixture snapshots.

## Migration Plan

1. Add the mode/session UI and ordered service submission path with append-only mode as the default.
2. Add unit/Robolectric coverage, then extend Editor conformance HIL with the fixed UI scenario.
3. Build and install the updated APK; reset the real fixture before the first UI Editor session.
4. Run no-hardware checks, Editor conformance smoke, and composite smoke; inspect their structured summaries.
5. Roll back by reverting the UI mode, service FIFO, and its HIL scenario; append-only behavior and all lower-layer contracts remain compatible.

## Open Questions

None.
