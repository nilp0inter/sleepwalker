## 1. Ordered UI Editor Command Lane

- [x] 1.1 Add a service-owned single-thread FIFO for UI Editor snapshot and reset requests with monotonic UI change identifiers
- [x] 1.2 Execute queued snapshots through the existing `editorLock`, `Editor.setText`, and `BleEditorExecutor` path without constructing or sending operations from UI code
- [x] 1.3 Deliver immutable request/result pairs back to the main thread and support lifecycle generations that suppress stale presentation callbacks without cancelling admitted Editor work
- [x] 1.4 Keep reset ordered after prior snapshots, clear Editor verification state, and preserve the explicit empty-target precondition

## 2. Android Demo Mode UI

- [x] 2.1 Add `APPEND_ONLY` and `READLINE_EDITOR` mode selection to `MainActivity`, retaining append-only as the default
- [x] 2.2 Show the fixed `readline-emacs-ascii` target identity, printable-ASCII single-line constraints, and empty-target reset guidance in Readline editor mode
- [x] 2.3 Route append-only insertions through the existing planner path and route every Readline text change as the complete current snapshot through the ordered UI Editor lane
- [x] 2.4 Lock mode selection after the first target-mutating operation and add an explicit session reset that clears local session state only after the target-empty acknowledgement
- [x] 2.5 Enable Readline input only while BLE is connected and safety state is `ARMED`, while retaining executor-side connection and safety checks for races
- [x] 2.6 Map `Synced`, recoverable planning failure, transport failure, and terminal `Unknown` outcomes to feedback, input availability, and reset availability
- [x] 2.7 Add a stable activity launch extra that selects Readline editor mode for HIL without bypassing the real mode selector or `TextWatcher`

## 3. Initial Behavioral Smoke

- [x] 3.1 Build the debug APK with `sleepwalker-apk-build android` and resolve compilation or packaging failures
- [x] 3.2 Install the APK and exercise insertion, deletion, replacement, paste, clearing, invalid input, and unknown/reset presentation through the actual activity before adding regression coverage
- [x] 3.3 Run `sleepwalker-smoke-text sleepwalker-hil/bench.toml` and inspect its summary to prove the default append-only UI path remains functional

## 4. Regression Coverage

- [x] 4.1 Add focused tests for FIFO admission order, no coalescing, main-thread result delivery, ordered reset, and stale lifecycle callback suppression
- [x] 4.2 Add Robolectric tests for mode routing, complete snapshots for insertion/deletion/replacement/paste/clear, mode isolation, connection/safety gating, result feedback, and unknown-state reset behavior
- [x] 4.3 Extend Editor conformance HIL with a fixed UI scenario that launches Readline mode, drives text changes through Android input, waits for Editor diagnostics, emits the F24 barrier, and compares each authoritative fixture snapshot
- [x] 4.4 Extend the no-hardware Editor conformance runner tests for UI scenario sequencing, timeout/failure classification, and structured summary evidence

## 5. Final Verification

- [x] 5.1 Run the modified app unit/Robolectric tests and `sleepwalker-editor-conformance-check`
- [x] 5.2 Re-run `sleepwalker-apk-build android` after regression coverage is complete
- [x] 5.3 Run `sleepwalker-smoke-editor-conformance sleepwalker-hil/bench.toml --profile quick` and inspect its summary and per-step Editor/fixture evidence
- [x] 5.4 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml` and inspect `artifacts/run_composite_<timestamp>/summary.json` for passing keyboard, mouse, observer, and correlation evidence
- [x] 5.5 Record the exact passing artifact directories in the change task notes and leave every completed checkbox synchronized with observed evidence

## Verification Evidence

- `artifacts/run_text_1784280096/`: append-only text smoke passed both `direct_text` and `ui_text` scenarios.
- `artifacts/run_editor_conformance_1784283680/`: quick Editor conformance passed 37 generated steps and all 6 fixed UI steps with zero failures.
- `artifacts/run_composite_1784283798/`: composite smoke passed keyboard, mouse, observer, and cross-layer sequence-correlation evidence.
