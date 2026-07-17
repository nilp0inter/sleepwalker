## Why

The experimental Lua Editor ABI fixes reconciliation policy in Kotlin through a single LCP/LCS replacement, exposes Readline-shaped state, and lets Lua mutate a host plan builder. Because this interface has not reached production, it can be replaced cleanly with a target-neutral ABI in which pure Lua owns all editor intelligence while Kotlin retains only validation, layout-aware costing, execution, and transactional commit responsibilities.

## What Changes

- **BREAKING**: Replace the experimental seven-argument Lua planning function and side-effecting `host.*` plan builder with host ABI version 1 defined as a pure data transformation over current rendered text, desired rendered text, and opaque target-package state.
- **BREAKING**: Remove Kotlin-owned LCP/LCS decomposition, `oldMid`/`newMid`, host-derived caret prediction, `ReadlineProgramState`, and target-specific state validation from the generic Editor path.
- Make Lua return inert symbolic keyboard actions plus opaque predicted state; Kotlin validates and compiles the symbolic actions only after Lua returns.
- Round-trip constrained opaque Lua state transactionally: commit returned state only after complete execution, retain prior state on pre-execution failure, and enter `Unknown` after possible partial execution.
- Add host-bundled pure Lua shared modules for reusable diffing, state transformation, action construction, plan sequencing, candidate choice, and deterministic cost aggregation.
- Expose a single read-only, deterministic Kotlin capability to Lua: layout-aware text cost for a supplied string under the configured keyboard layout. The query emits no actions and mutates no Editor or planner state.
- Let Lua compose target- and mode-specific planners, including state-only intermediate transformations for carets, selections, multiple cursors, and modal editors, without exposing those concepts to Kotlin or public callers.
- Preserve `setText(completeDesiredText)` as the only public Editor mutation; caret, mode, selection, and cursor state remain internal to opaque Lua state.
- Add deterministic pure initialization of opaque target state from the known initial rendered text.
- Make F24 reservation an execution-policy flag: production Editor plans may emit F24, while Readline conformance explicitly reserves and rejects it so the separately injected physical synchronization barrier remains authoritative.
- Update retained plans and HIL artifacts to record opaque input/output state, symbolic actions, compiled operations, package/ABI identity, keyboard-layout identity, and text-cost metric identity instead of host-selected replacement fields.

## Capabilities

### New Capabilities
- `lua-editor-planning-abi`: Pure Lua host ABI version 1, opaque transactional target state, symbolic action results, shared Lua planning modules, and the deterministic layout-aware text-cost query.

### Modified Capabilities
- `sleepwalker-core`: Replace Kotlin-owned Editor differencing and Readline-shaped state with pure Lua reconciliation, opaque state round-tripping, symbolic-action compilation, and policy-controlled reserved usages.
- `agent-operated-hil`: Configure F24 reservation explicitly for Editor conformance and preserve replay evidence for opaque state, symbolic plans, layout identity, and compiled operations.
- `shared-hid-protocol`: Change F24 from an unconditional Editor-plan prohibition to a conditionally reserved usage while preserving its canonical USB usage and HIL barrier role.

## Impact

- `android/sleepwalker-core`: Editor state model and invocation flow, Lua runtime adapter, target package loader/module system, symbolic action decoder/compiler, text-cost oracle, retained-plan schema, failure classification, and focused tests.
- Bundled Lua assets: shared `sleepwalker.*` modules, pure Readline target program, deterministic initial-state function, package manifest/version, and package-local modules.
- `android/sleepwalker-app`: Editor construction/configuration for production versus conformance F24 policy and diagnostics exposing the active policy.
- `nix/smoke-editor-conformance.py` and related HIL artifacts: explicit F24 reservation assertion, opaque-state replay fields, symbolic actions, layout/cost identity, and removal of LCP/LCS-specific evidence.
- OpenSpec contracts from `add-lua-editor-mode`: supersede the experimental fixed replacement ABI and target-specific generic-state assumptions without compatibility shims because the interface is not production-released.
- Firmware and BLE wire protocol remain unchanged; symbolic actions still compile to the existing low-level keyboard operations and F24 still uses the generic raw HID key path.
