## Why

Sleepwalker can currently type an append-only stream, but it cannot reconcile a previously rendered target field with a new complete text snapshot. A stateful Editor mode is needed to turn repeated `setText` calls into target-specific HID edits while preserving an exact, testable model of the remote text surface.

## What Changes

- Add a stateful Editor API whose only public text mutation is `setText(completeDesiredText)`.
- Track the assumed target document, caret, selection, target-program state, revision, and synchronization lifecycle internally; callers do not manage cursor mechanics.
- Compute internal text transitions between successive complete snapshots, validate candidate reconciliations, and execute them through a serialized executor boundary while retaining BLE ownership outside `sleepwalker-core`.
- Add bundled, trusted Lua 5.4 target packages with a versioned, constrained host ABI; target programs produce inspectable plans and cannot access BLE, Android services, ambient I/O, time, or transport pacing.
- Add the first Lua target package for a pinned GNU Readline Emacs-mode, single-line ASCII environment.
- Add a versioned target-fixture control contract providing target description, deterministic reset, physical-input barrier, authoritative snapshot, health, and shutdown operations.
- Build the real GNU Readline fixture into the observer environment and expose its authoritative line buffer and hidden editing state without implementing a shadow editor.
- Add a Hypothesis-driven physical conformance scenario that generates related complete-text snapshot sequences, calls `setText` repeatedly, and compares requested and predicted state with the fixture after every synchronized call.
- Add a reserved symbolic HID synchronization key with Python/Kotlin parity so target fixtures can prove that all preceding physical input was consumed without relying on fixed sleeps.
- Preserve structured artifacts and classify semantic mismatches separately from planning, fixture, synchronization, transport, environment, and non-reproducible hardware failures.

## Capabilities

### New Capabilities

- `lua-editor-mode`: Stateful complete-snapshot text reconciliation, constrained bundled Lua target programs, internal target-state prediction, serialized execution, and structured Editor results.
- `editor-target-conformance`: Generic instrumented target-fixture contract and model-based physical property testing for target behavior packages.

### Modified Capabilities

- `sleepwalker-core`: Extend the public high-level library behavior with stateful Editor reconciliation and an executor abstraction while preserving inspectable low-level plans.
- `android-ble-companion`: Exercise the Editor through the shared BLE session, serialized status-aware execution, structured diagnostics, and an encoded complete-text command path.
- `shared-hid-protocol`: Add a cross-language symbolic keyboard usage reserved for target-fixture synchronization.
- `hid-observer-nixos-iso`: Include reproducible target fixtures and their isolated control surface in the sacrificial observer environment.
- `agent-operated-hil`: Add generated Editor snapshot-sequence validation, target-state comparison, replay evidence, and Editor-specific failure classification.

## Impact

- Affected Kotlin areas: `sleepwalker-core` public API, text transition/model code, Lua runtime adapter, target package loading, and tests.
- Affected Android areas: BLE execution/status correlation, ADB command handling, diagnostics, and reference integration.
- Affected protocol areas: Python and Kotlin symbolic usage registries and parity fixtures; firmware frame layout and opcodes remain unchanged.
- Affected HIL areas: observer ISO packages, GNU Readline fixture, fixture-control adapters, synchronization marker handling, Hypothesis runner, artifacts, and composite verification.
- New dependencies: a bundled Android-compatible Lua 5.4 runtime and GNU Readline for the observer-only fixture.
- Initial scope is limited to trusted app-bundled packages and a pinned ASCII, single-line GNU Readline Emacs-mode target. Dynamic packages, untrusted sandboxing, Unicode/graphemes, active selections, Vi mode, history, completion, submission, wordwise optimization, concurrent execution, call coalescing, and generic recovery after unknown target state are excluded; concurrently submitted calls are queued and executed serially.
