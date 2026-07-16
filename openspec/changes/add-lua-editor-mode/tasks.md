## 1. Protocol F24 Synchronization Parity

- [x] 1.1 Add `USB_KEY_F24` (`usbUsage = 0x73`, `evdevCode = 194`) to the Kotlin `Usages` registry in `sleepwalker-core` as a reserved symbolic keyboard usage distinct from all text-rendering usages
- [x] 1.2 Add the matching `USB_KEY_F24` symbolic name and USB HID usage value `0x73` to the Python `usages.py` registry so Python and Kotlin resolve the same canonical value
- [x] 1.3 Do not add a firmware symbolic F24 constant; the existing firmware accepts raw `KEY_TAP` usage `0x73` generically with no implementation change
- [x] 1.4 Add the `valid_usb_key_f24.bin` golden frame fixture under `protocol/src/sleepwalker_protocol/fixtures` so cross-language parity is enforced
- [x] 1.5 Verify `sleepwalker-protocol-check` passes, confirms Python/Kotlin F24 symbolic parity, and validates the raw usage `0x73` golden frame; leave physical firmware emission proof to the end-to-end HIL observation
- [x] 1.6 Confirm `USB_KEY_F24` is never emitted by `TextPlanner` or any Editor/Lua plan path (reserved for fixture synchronization only)

## 2. LuaJava Dependency and Package Constraints

- [x] 2.1 Add the maintained LuaJava Lua 5.4 binding to the `sleepwalker-core` Gradle module dependencies from Maven Central
- [x] 2.2 Package only the required Android native ABIs (`arm64-v8a` for the reference device, `x86_64` for emulator/CI) in the Gradle native packaging configuration
- [x] 2.3 Pin the LuaJava coordinates in the Android dependency closure and use nixpkgs' pinned GNU Readline 8.2 package in the observer fixture derivation; do not introduce unnecessary flake inputs
- [x] 2.4 Verify `sleepwalker-apk-build` succeeds with the LuaJava native artifacts packaged for the selected ABIs
- [x] 2.5 Confirm no project-local JNI bridge is introduced (LuaJava maintained binding is the sole native Lua integration)

## 3. Constrained Host ABI and Loader

- [x] 3.1 Implement Lua runtime initialization that opens only the allowlisted Lua libraries (selected base, table, string, integer-safe math) via LuaJava
- [x] 3.2 Remove `io`, `os`, `debug`, coroutine scheduling, dynamic native loading, and `load`/`dofile`/`loadfile` base functions from the initialized runtime
- [x] 3.3 Never install the LuaJava general `java` bridge module so target packages cannot reflect into JVM or Android APIs
- [x] 3.4 Replace `require` with a loader that resolves only package-declared modules from the same trusted app bundle (no network or filesystem runtime fetch)
- [x] 3.5 Implement the `host` ABI global table with exactly `text_plan(text)`, allowed `key_tap(name)`/`key_down(name)`/`key_up(name)`, `ctrl(name)`, `describe()`, and `abi_version()`; reject reserved synchronization usages including F24 from all target-package key functions
- [x] 3.6 Ensure the ABI exposes no time (`os.time`/`os.clock` removed), no I/O, no random source, no coroutine-based scheduling, and no transport/pacing control
- [x] 3.7 Implement `TargetPackageLoader` that loads app-bundled read-only resources from `android/sleepwalker-core/src/main/assets/targets/<id>/` and exposes a fixed registry
- [x] 3.8 Implement manifest parsing (`id`, `version`, `host_abi`, `target`, `target_pin`, `mode`, `charset`, `line_model`, `describe`) and refuse packages whose `host_abi` mismatches `host.abi_version()` with a structured `AbiMismatch` failure
- [x] 3.9 Verify with a unit harness that a bundled package loads, a non-app-bundled package is rejected, and a version-mismatched package is refused before any plan executes

## 4. Explicit Transactional Target-Program State

- [x] 4.1 Define the host-owned explicit program-state value for the Readline target (`buffer`, `point`, `revision`) passed into each planning invocation
- [x] 4.2 Implement deep-copy of the Editor's committed program state into an invocation-local Lua table per planning call (no retained mutable Lua VM state between invocations)
- [x] 4.3 Ensure each ABI primitive the program emits mutates the invocation-local state in lockstep: `ctrl("A")` sets `point = 0`; `ctrl("F")` increments `point` (clamped); `ctrl("D")` removes the char at `point`; `ctrl("H")` removes the char before `point` and decrements `point`; `host.text_plan(s)` inserts `s` at `point` and advances `point` by `#s`
- [x] 4.4 Require the target program to assert `state.buffer == desired` after producing a plan and signal `InconsistentPrediction` on mismatch so the Editor rejects the plan
- [x] 4.5 Make the target return both the plan and `nextProgramState` as explicit outputs; the Editor commits `nextProgramState` only after complete execution
- [x] 4.6 Discard `nextProgramState` on pre-execution planning/validation failure (retain previously committed state); do not commit on partial execution (move to `Unknown`)
- [x] 4.7 Verify with a unit harness that repeated identical inputs produce identical plans and identical next state regardless of prior Lua runtime use, and that unexecuted predictions are discarded

## 5. Editor State Machine, LCP/LCS Planning, and Structured Results

- [x] 5.1 Implement `Editor(target, executor, hid)` with the single public text-mutating method `setText(text): EditorResult` and read-only `state(): EditorState`
- [x] 5.2 Implement the `EditorState` enum exposing only state names (`Uninitialized`, `Synced`, `Planning`, `Executing`, `Failed`, `Unknown`) with no caret, selection, document, or program-state fields
- [x] 5.3 Implement the state machine transitions: `Uninitialized → Planning → Executing → Synced` on full ack; `Planning → Failed` on plan/ABI failure (recoverable); `Executing → Unknown` on partial ack/infra fault (terminal)
- [x] 5.4 Make `Unknown` terminal: reject all further `setText` calls until an authoritative fixture snapshot confirms state or the Editor is explicitly reset to empty known state
- [x] 5.5 Initialize every Editor session with an empty target document, empty assumed program state, neutral caret at start, no active selection, and zero revision
- [x] 5.6 Implement the LCP/LCS snapshot differencing yielding exactly one contiguous replacement region (`lcp`, `lcs` capped so `lcp + lcs <= min(len current, len desired)`, `oldMid`, `newMid`)
- [x] 5.7 Pass `(current, desired, lcp, oldMid, newMid, predictedPoint)` to the Lua target and receive the ordered `LowLevelOp` plan composed of Emacs navigation, deletion, and `TextPlanner` typing ops
- [x] 5.8 Validate the candidate plan before execution: `newMid`/`desired` representability via `TextPlanner` (surface `TextRenderingFailure`); predicted post-state equals `desired` with predicted caret at `lcp + newMid.length` (reject `InconsistentPrediction`)
- [x] 5.9 Implement `EditorResult` as a sealed type carrying the requested complete snapshot, inspectable plan evidence, and structured outcome; never expose caret, selection, revision, assumed document, or target-program state
- [x] 5.10 Implement structured `EditorFailure` classification separating semantic failures (unsupported behavior, unrepresentable content, impossible transition) from infrastructure failures (fixture, sync, transport, environment, non-reproducible)
- [x] 5.11 Implement plan retention including host ABI version, target package identity, assumed prior state, desired snapshot, predicted resulting state, and ordered low-level operations, available to verification artifacts
- [x] 5.12 Handle the no-op case: `setText(current)` yields an empty plan and `Synced` with unchanged assumed state
- [x] 5.13 Mark post-USB state as assumed (not observed) after execution; mark observed only on independent authoritative fixture confirmation
- [x] 5.14 Preserve the existing `TextPlanner` append-only streaming API unchanged alongside the Editor surface
- [x] 5.15 Verify with a `RecordingEditorExecutor` unit harness: `setText("abc")` from empty produces insert-only; `setText("hello")` then `setText("help")` reconciles via one contiguous replacement; pre-execution `Failed` and terminal `Unknown` paths are exercised; no-op returns `Synced`

## 6. Readline Emacs ASCII Target Package

- [x] 6.1 Author the `readline-emacs-ascii` package manifest (`id`, `version`, `host_abi = 1`, `target = "gnu-readline"`, `target_pin = "8.2"`, `mode = "emacs"`, `charset = "ascii-printable"`, `line_model = "single-line"`)
- [x] 6.2 Implement the Lua target program modeling the Readline line buffer, point, and Emacs-mode editing behavior for ASCII-printable characters only
- [x] 6.3 Compose plans using only `C-a`, `C-e`, `C-b`, `C-f`, `C-d`, backward-delete-char (`C-h`), and `host.text_plan` typing — no new navigation usages beyond these
- [x] 6.4 Pin the deterministic navigation strategy as `C-a` then forward `C-f` to the replacement offset (home-then-forward; shorter-route selection is a non-goal)
- [x] 6.5 Use `C-h` (not a dedicated Backspace usage) for backward-delete to keep F24 the sole new symbolic usage
- [x] 6.6 Reject out-of-scope behaviors (Vi mode, history, completion, submission/Enter, Unicode/graphemes, active selections, wordwise optimization) with a structured out-of-scope failure rather than approximation
- [x] 6.7 Verify with the unit harness that identical `(current, desired, assumedPoint)` inputs produce identical plans and predicted state across repeated invocations

## 7. Serialized Executor and Android BLE Integration

- [x] 7.1 Implement the `EditorExecutor` interface (`execute(plan): ExecutionOutcome`) returning `Delivered(statuses)` or `Partial(delivered, reason)` in `sleepwalker-core`
- [x] 7.2 Implement serialization in the Editor: a single ordered command lane; concurrent `setText` calls wait and enter in arrival order; no coalescing; no interleaving of plan computation or execution
- [x] 7.3 Implement per-batch `seqId` allocation via the existing `LowLevelHid`/`SleepwalkerCommands` sequence allocator and `SessionStatus` correlation via the existing `SessionStatusParser`
- [x] 7.4 Define a batch as delivered only when its terminal status is `SENT_TO_USB`; treat `DISARMED`/`QUEUE_FULL`/`USB_NOT_MOUNTED`/`KILLED`/timeout/missing-status as `Partial` → Editor `Unknown`
- [x] 7.5 Implement `BleEditorExecutor` in `sleepwalker-app` backed by the existing single `SleepwalkerBleService` session (same scan/connect/write/MTU path, same 390 ms inter-batch drain pacing) — no duplicated BLE logic
- [x] 7.6 Detect armed/disarmed/kill states and do not emit Editor HID operations while the firmware is disarmed or killed; report a structured safety-state failure
- [x] 7.7 Add the ADB `set-text` command path accepting an encoded complete-text payload, decoded exactly once and passed to the `sleepwalker-core` Editor `setText` (mirroring the existing lossless encoded `type-text` path)
- [x] 7.8 Preserve the existing plain and encoded append-only `type-text` command paths and UI unchanged alongside the new Editor command path
- [x] 7.9 Emit structured Editor diagnostics: command identity, command sequence, decoded snapshot length, target package identity, host ABI version, plan operation count, and per-operation status correlation
- [x] 7.10 Reject invalid encoded Editor payloads or invalid UTF-8 with a structured command failure and no HID emission
- [x] 7.11 Verify with a `RecordingEditorExecutor` that concurrent `setText` calls serialize in arrival order without coalescing or interleaving; verify `BleEditorExecutor` delegates to the shared session without independent scan/connect/write

## 8. Readline Fixture, Control Socket, Reset, Health, F24 Binding, and Snapshot

- [x] 8.1 Build the GNU Readline fixture as a small C program linking against pinned libreadline 8.2, built into the observer ISO by the flake alongside `text-sink` and `observer-helper`
- [x] 8.2 Run the fixture as the foreground application on the active Linux virtual terminal in Readline Emacs single-line mode so ESP32-S3 USB HID input reaches its standard input through the real console path; keep the unix control socket separate
- [x] 8.3 Feed bytes to `rl_callback_read_char()` in a loop and read the authoritative `rl_line_buffer`, `rl_point`, and selected hidden state directly from the live Readline process (no shadow editor)
- [x] 8.4 Bind the Linux VT's F24 terminal key sequence with `rl_bind_keyseq` to a handler that captures the snapshot and publishes it over the control channel without inserting bytes into `rl_line_buffer`
- [x] 8.5 Expose the versioned `TargetFixture` control contract over a unix socket on the observer host, reachable over the existing SSH control connection (no interactive login prompts)
- [x] 8.6 Implement `describe()` returning target/mode/charset/line-model identity and control-ABI version (matched against the package manifest at HIL setup)
- [x] 8.7 Implement `reset()` deterministically restarting the Readline process, clearing the line buffer, and homing the point to a known empty/neutral state across repeated calls
- [x] 8.8 Implement `await_barrier()` blocking until the fixture consumes an F24 from its own input stream (not inferred from evdev)
- [x] 8.9 Implement `snapshot()` returning the authoritative `rl_line_buffer`, `rl_point`, and hidden editing state read from the real Readline process
- [x] 8.10 Implement `health()` checking process liveness on the active VT, pinned console keymap and terminal identity, exact F24 key-sequence binding, and zero line-buffer mutation from a barrier
- [x] 8.11 Implement `shutdown()` for clean teardown with no fixture target leaks across runs
- [x] 8.12 Verify the observer ISO build contains the reproducible Readline fixture and the control surface responds to `describe`/`reset`/`await_barrier`/`snapshot`/`health`/`shutdown` over SSH with contract versions recorded

## 9. Non-Grabbing evdev Diagnostics for Conformance

- [x] 9.1 Run the HID observer without `--grab` during Editor conformance so injected HID events are delivered to the Linux VT and Readline fixture
- [x] 9.2 Mark all non-grabbing evdev JSONL output as `diagnostics_only` in the artifact so no consumer treats it as authoritative for synchronization or state read-back
- [x] 9.3 Use non-grabbing evdev only for wire-level diagnostics (confirming F24 and editing chords were emitted, cross-correlating sequence ids)
- [x] 9.4 Preserve existing exclusive-grab smoke scenarios (keyboard/mouse/composite/text-identity) unchanged
- [x] 9.5 Verify the observer helper still supports exclusive grab for existing smokes and non-grabbing for conformance

## 10. Observer ISO Packaging

- [x] 10.1 Add the Readline fixture binary and pinned GNU Readline 8.2 runtime closure to the observer ISO image via the flake
- [x] 10.2 Add the fixture control-surface helper to the observer ISO reachable over SSH by HIL automation
- [x] 10.3 Ensure the observer ISO build is reproducible and includes the fixture alongside the existing `text-sink` and `observer-helper`
- [x] 10.4 Verify the observer ISO build succeeds reproducibly with the fixture packaged

## 11. HIL Hypothesis Generator and Editor Conformance Scenario

- [x] 11.1 Implement the Hypothesis snapshot-sequence generator producing related complete-text sequences from LCP/LCS-friendly transforms (append, insert-middle, replace-middle, delete prefix/suffix/middle, truncate) over the ASCII-printable seed alphabet (excluding ESC and unstable controls)
- [x] 11.2 Bound sequence length and per-step size by the existing `quick`/`deep` Hypothesis profiles with session-scoped setup
- [x] 11.3 For each step: send `setText(desired)` through ADB → app → Editor → BLE → firmware → USB path and wait for transport result
- [x] 11.4 After each Editor plan completes, send a separate low-level F24 tap through the HIL key-injection path (never inside an Editor plan or Lua host API)
- [x] 11.5 Wait for the fixture `await_barrier()` confirmation that F24 was consumed before snapshotting (no fixed sleeps, no evdev-inference)
- [x] 11.6 Read `fixture.snapshot()` and compare the Editor predicted state and `desired` against the observed snapshot per step (not only at sequence end)
- [x] 11.7 On mismatch, shrink the generated sequence and record a full artifact
- [x] 11.8 Implement session-scoped execution: bench validate, ESP reset, captures, fixture `reset()` + `health()`, BLE connect + arm once; per-example isolation is fixture `reset()` + per-step plan/snapshot
- [x] 11.9 Reset the Readline fixture before each generated sequence before sending the first `setText`
- [x] 11.10 Preserve the existing text-identity HIL scenario unchanged alongside the new Editor conformance scenario
- [x] 11.11 Verify the scenario exercises the full path (ADB, Android, `sleepwalker-core`, BLE, ESP32-S3, USB HID, Readline fixture) and compares state per step

## 12. HIL Separate F24 Barrier, Non-Grabbing Diagnostics, Replay, and Failure Classification

- [x] 12.1 Emit F24 strictly as a separate HIL test operation after the Editor plan completes; never include F24 in an Editor reconciliation plan or expose it through the Lua host ABI
- [x] 12.2 Classify the F24 barrier-not-consumed-within-window case as `sync` (not `semantic`), distinct from transport health
- [x] 12.3 Classify failures into the mutually exclusive taxonomy: `semantic`, `planning`, `fixture`, `sync`, `transport`, `environment`, `non_reproducible`
- [x] 12.4 Define `semantic` as plan delivered and barrier consumed but `observed != predicted` or `observed != desired` (the property's counterexample)
- [x] 12.5 Define `planning` as pre-execution Editor rejection (unrepresentable glyph, inconsistent prediction, ABI mismatch) with no HID emitted
- [x] 12.6 Define `transport` as BLE/firmware fault (DISARMED/QUEUE_FULL/USB_NOT_MOUNTED/KILLED, BLE timeout, missing status) mapping to Editor `Unknown`
- [x] 12.7 Define `fixture` as fixture misbehavior (health fail, reset fail, snapshot read fail, identity/control-ABI mismatch)
- [x] 12.8 Define `environment` as console keymap wrong, device not enumerated, SSH/observer unreachable
- [x] 12.9 Define `non_reproducible` as a step failing once but succeeding on same-input replay (flaky hardware/timing)
- [x] 12.10 Record per-failing-step replay data: `desired`, `lcp`/`old_mid`/`new_mid`, full `plan_ops`, predicted state, and Hypothesis shrunk-seed/representation sufficient for standalone single-step replay without re-running the whole property
- [x] 12.11 Verify classification logic with seeded examples covering each class and confirm `non_reproducible` is separated from deterministic `semantic` counterexamples

## 13. HIL Artifacts and Per-Step Comparison

- [x] 13.1 Write structured artifacts under `artifacts/run_editor_conformance_<ts>/` extending the existing artifact schema with an Editor-specific section
- [x] 13.2 Populate `summary.json` with `scenario`, `status`, `profile`, `target_package` (`id`/`version`/`host_abi`/`target_pin`/`mode`/`charset`), `fixture` (`identity`/`control_abi_version`/`health`), `hypothesis` (`settings`/`examples`/`seed`/`duration`), per-step records, and `classification_totals`
- [x] 13.3 Per step record: `seq`, `desired`, `lcp`, `old_mid`, `new_mid`, `plan_ops`, `predicted` (`buffer`/`point`/`revision`), `observed` (`buffer`/`point`), `barrier_consumed`, `match`, `classification`
- [x] 13.4 Write per-run JSONL: `android_logcat.jsonl`, `esp_uart.jsonl`, `hid_observer.jsonl` (non-grabbing diagnostics), and the fixture snapshot log
- [x] 13.5 Ensure artifacts identify the exact failing step index and preserve requested, predicted, and observed states for that step
- [x] 13.6 Verify artifact schema with a dry-run or seeded-failure inspection confirming all fields are present and sufficient for standalone replay

## 14. Composite Integration

- [x] 14.1 Wire the end-to-end path: ADB `set-text` → Android command receiver → `sleepwalker-core` Editor → `BleEditorExecutor` → shared BLE session → ESP32-S3 firmware → USB HID → observer VT → Readline fixture
- [x] 14.2 Confirm the Editor uses the low-level keyboard API exclusively (no direct protocol frame construction in core)
- [x] 14.3 Confirm the app delegates BLE I/O to the shared service/session owner for Editor commands (no duplicated session logic)
- [x] 14.4 Confirm the existing append-only `type-text`/UI path and existing smokes (keyboard/mouse/composite/text-identity) continue to pass unchanged
- [x] 14.5 Confirm F24 is emitted only by the HIL runner after an Editor plan and never by production Editor or Lua host paths

## 15. Behavior-Focused Automated Tests

- [x] 15.1 Unit-test the LCP/LCS differencing for pure append, prefix change, suffix change, in-place middle edit, identical no-op, and empty-to-non-empty transition
- [x] 15.2 Unit-test the Editor state machine: `Uninitialized → Synced`, `Planning → Failed` (recoverable), `Executing → Unknown` (terminal, rejects further calls), and `Unknown` recovery via explicit reset
- [x] 15.3 Unit-test explicit transactional program state: commit after complete execution, discard on pre-execution failure, no commit on partial execution
- [x] 15.4 Unit-test the constrained host ABI: forbidden capabilities (`io`/`os`/`debug`/`java`/time/random/I/O) are unavailable and attempts fail without side effects
- [x] 15.5 Unit-test `TargetPackageLoader`: bundled package loads, non-bundled rejected, ABI mismatch refused, manifest fields parsed
- [x] 15.6 Unit-test the `readline-emacs-ascii` target: plans use only the pinned Emacs chords + `TextPlanner`; out-of-scope inputs rejected; determinism across repeated invocations
- [x] 15.7 Unit-test serialized executor: concurrent `setText` calls serialize in arrival order, no coalescing, no interleaving
- [x] 15.8 Unit-test `BleEditorExecutor` delegation: no independent scan/connect/write; disarmed/kill blocks emission with structured safety-state failure
- [x] 15.9 Unit-test the ADB `set-text` encoded path: payload decoded exactly once, shell-sensitive characters preserved, invalid encoding/UTF-8 rejected with no HID
- [x] 15.10 Unit-test structured diagnostics include command identity, sequence, decoded snapshot length, target package identity, host ABI version, and plan operation count
- [x] 15.11 Unit-test structured result types never expose caret, selection, or program-state fields
- [x] 15.12 Unit-test plan retention includes ABI version, package identity, assumed prior state, desired snapshot, predicted state, and ordered ops

## 16. No-Hardware Checks

- [x] 16.1 Run `sleepwalker-protocol-check` confirming Python/Kotlin F24 symbolic parity and the canonical raw `0x73` golden frame while firmware remains symbolically unaware
- [x] 16.2 Run `sleepwalker-fw-build` confirming the firmware builds unchanged (no F24 constant added; raw `KEY_TAP` usage `0x73` is accepted generically)
- [x] 16.3 Run `sleepwalker-apk-build` confirming the APK builds with LuaJava native artifacts and the bundled `readline-emacs-ascii` package
- [x] 16.4 Run the observer ISO build confirming the Readline fixture and control surface are packaged
- [x] 16.5 Run the full no-hardware unit suite covering sections 15.1–15.12 with the `RecordingEditorExecutor` (no BLE, no fixture, no hardware)

## 17. Physical-Bench Composite Validation and Artifact Inspection

- [x] 17.1 Validate the already commissioned bench topology and connectivity from `sleepwalker-hil/bench.toml`; require human intervention only if the physical bench is unreachable or no longer commissioned
- [x] 17.2 Run the Editor conformance scenario in the `quick` profile on the commissioned bench and confirm per-step `predicted == observed` and `desired == observed.buffer`
- [x] 17.3 Run the Editor conformance scenario in the `deep` profile and confirm stability across the fuller example budget
- [x] 17.4 Inspect `artifacts/run_editor_conformance_<ts>/summary.json` for per-step records, `classification_totals`, target package, fixture, and Hypothesis metadata
- [x] 17.5 Inspect per-run JSONL (`android_logcat`, `esp_uart`, `hid_observer` non-grabbing diagnostics, fixture snapshot log) for cross-layer sequence correlation
- [x] 17.6 Replay a seeded failing step standalone (single `setText` + F24 barrier + snapshot) and confirm reproduction without re-running Hypothesis generation
- [x] 17.7 Confirm the existing text-identity HIL scenario still passes unchanged alongside Editor conformance
- [x] 17.8 Confirm existing grab-based smokes (keyboard/mouse/composite) continue to pass with exclusive grab unaffected
- [x] 17.9 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml`, inspect its `artifacts/run_composite_<timestamp>/summary.json`, and require passing evidence for every affected component before marking the change complete
