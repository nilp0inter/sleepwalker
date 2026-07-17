## 1. ABI Value and Result Model

- [ ] 1.1 Define the canonical opaque ABI value tree for null, booleans, signed 64-bit integers, UTF-8 strings, arrays, and string-keyed objects
- [ ] 1.2 Define and enforce ABI limits for depth, nodes, collection sizes, string bytes, and total encoded size
- [ ] 1.3 Implement Lua-table decoding that rejects cycles, metatables, functions, userdata, threads, fractional numbers, unsupported integers, sparse arrays, mixed table shapes, and duplicate object keys
- [ ] 1.4 Implement canonical Kotlin-to-Lua encoding so committed opaque state round-trips without field interpretation
- [ ] 1.5 Define the ABI-v1 package table, pure initializer result, planning input, successful planning result, and structured rejection schemas
- [ ] 1.6 Define closed inert symbolic action values for tap, down, up, and text actions
- [ ] 1.7 Define retained representations for symbolic actions, opaque input/output state, layout identity, cost-metric identity, policy identity, and transaction outcome

## 2. Pure Lua Runtime and Module Loading

- [ ] 2.1 Replace global callback registration with a package main chunk that returns the host-ABI-v1 table
- [ ] 2.2 Invoke every initializer and plan in a fresh constrained Lua VM and discard the VM after decoding the result
- [ ] 2.3 Preserve the deterministic Lua library allowlist and remove I/O, OS, time, randomness, coroutine, debug, Java bridge, and arbitrary loading capabilities
- [ ] 2.4 Add a host-owned immutable shared-module registry under the reserved `sleepwalker.*` namespace
- [ ] 2.5 Load package-declared local Lua modules from bundled assets and prevent them from shadowing shared modules
- [ ] 2.6 Replace the single `__modules` table with separate loader, cache, and loading-state tables that cache every export type and reject cycles deterministically
- [ ] 2.7 Make module discovery reject undeclared names, duplicate identities, resolver escapes, filesystem access, and network access
- [ ] 2.8 Record package source/module identity so retained plans and replay distinguish the exact bundled Lua program

## 3. Layout-Aware Text-Cost Capability

- [ ] 3.1 Define a named and versioned scalar text-cost metric based on the configured layout's compiled low-level operation count
- [ ] 3.2 Expose `sleepwalker.cost.text_cost(text)` as the only Kotlin-backed planning query available to shared Lua modules
- [ ] 3.3 Return deterministic structured representability failure from `text_cost` without emitting actions, allocating sequence IDs, or mutating Editor/package state
- [ ] 3.4 Snapshot keyboard-layout and cost-metric identities before Lua planning and require the same identities during symbolic text compilation
- [ ] 3.5 Cache layout compilation results within one invocation so a selected text action reuses work already performed by `text_cost`
- [ ] 3.6 Reject planning or compilation when layout or metric identity changes within a transaction

## 4. Shared Pure Lua Planning Library

- [ ] 4.1 Add pure `sleepwalker.actions` constructors and list combinators that return only symbolic action data
- [ ] 4.2 Add pure `sleepwalker.state` copy and generic value-transformation helpers without retained module state
- [ ] 4.3 Add pure `sleepwalker.diff` common-affix and reusable text-differencing functions using documented zero-based ASCII byte offsets and half-open ranges
- [ ] 4.4 Add pure `sleepwalker.plan` identity, sequencing, candidate-choice, rejection, and validation combinators
- [ ] 4.5 Add pure `sleepwalker.cost` aggregation and deterministic candidate comparison with a stable tie-breaker
- [ ] 4.6 Ensure sequential composition passes predicted rendered text and opaque state between internal planner stages and adds actions and costs
- [ ] 4.7 Ensure state-only caret, selection, cursor, and mode transformations remain internal candidate steps rather than public requests or ABI fields

## 5. Symbolic Action Validation and Compilation

- [ ] 5.1 Decode the complete Lua result into bounded Kotlin values before creating any low-level operation
- [ ] 5.2 Validate each symbolic action against its exact closed schema and reject unknown kinds or extra executable/protocol fields
- [ ] 5.3 Resolve symbolic key usages through the canonical registry and compile tap, down, and up actions through existing `LowLevelHid` primitives
- [ ] 5.4 Compile text actions through the pinned layout-aware `TextPlanner` path and preserve atomic failure before execution
- [ ] 5.5 Enforce generic keyboard safety, plan-size, modifier-transition, and reserved-usage invariants without inferring editor semantics
- [ ] 5.6 Introduce the execution policy whose production default permits F24 and whose conformance configuration reserves F24
- [ ] 5.7 Reject F24 before execution only when the active policy reserves it, while leaving its canonical usage and generic wire path unchanged
- [ ] 5.8 Produce the existing inspectable ordered `LowLevelOp` plan only after all state and symbolic actions validate successfully

## 6. Transactional Editor Cutover

- [ ] 6.1 Replace `ReadlineProgramState` storage with separately committed rendered text and bounded opaque package state
- [ ] 6.2 Initialize opaque state through the package's pure initializer using the known initial rendered text
- [ ] 6.3 Re-run pure initialization during explicit reset and retain no Lua VM or module state across reset
- [ ] 6.4 Invoke Lua planning with only current rendered text, desired rendered text, and a boundary copy of committed opaque state
- [ ] 6.5 Remove Kotlin LCP/LCS computation, `oldMid`/`newMid` inputs, host-derived predicted point, new-middle probing, and target-specific state validation
- [ ] 6.6 Reject a no-action result that changes rendered text or returns opaque state structurally different from the committed input state
- [ ] 6.7 Retain prior rendered text and opaque state after package load, Lua, schema, policy, representability, identity, or compilation failure
- [ ] 6.8 Atomically commit desired rendered text and returned opaque state only after every compiled operation completes
- [ ] 6.9 Preserve terminal `Unknown` behavior after possible partial execution and reject later `setText` calls until reset or reconstruction
- [ ] 6.10 Preserve serialized arrival-order execution and the text-only public `setText(completeDesiredText)` API
- [ ] 6.11 Remove `DiffResult`, the seven-argument planner, side-effecting host action functions, mutable `PlanBuilder`, `ReadlineProgramState`, and every compatibility path

## 7. Bundled Readline Target Migration

- [ ] 7.1 Update the Readline package manifest/version while keeping `host_abi = 1` and the pinned target identity
- [ ] 7.2 Rewrite the Readline package main chunk to return the pure ABI table with deterministic initializer and planner functions
- [ ] 7.3 Move Readline buffer point, editing mode, and any other target-specific prediction fields into opaque Lua-owned state
- [ ] 7.4 Reimplement the existing correct Readline reconciliation behavior using shared Lua diff, action, plan, state, and cost modules
- [ ] 7.5 Make the Readline planner construct and compare candidates entirely in Lua and return only symbolic actions plus opaque next state
- [ ] 7.6 Preserve printable-ASCII and single-line package constraints as structured Lua planning rejection rather than Kotlin editor-model logic
- [ ] 7.7 Ensure identical package sources, inputs, opaque state, layout identity, and metric identity produce identical returned plans across fresh VMs

## 8. App Integration and Diagnostics

- [ ] 8.1 Update Editor construction and lifecycle wiring in the Android app for the pure ABI and opaque-state model
- [ ] 8.2 Configure production Editor instances with F24 permitted and conformance Editor instances with F24 reserved
- [ ] 8.3 Preserve the app's FIFO Editor command lane, generation checks, complete-snapshot UI behavior, and structured result callbacks
- [ ] 8.4 Replace app/ADB diagnostic fields for LCP, middle slices, caret, and Readline revision with opaque states, symbolic actions, compiled operations, identities, policy, and commit outcome
- [ ] 8.5 Ensure diagnostics never expose opaque package state through the public `EditorResult` while retaining it in internal verification evidence
- [ ] 8.6 Include the active F24 reservation policy in conformance setup diagnostics so the runner can reject an unsound session

## 9. HIL and Replay Migration

- [ ] 9.1 Replace LCP/LCS-specific conformance artifact fields with current/desired text, opaque input/output state, symbolic actions, compiled operations, identities, policy, and transaction outcome
- [ ] 9.2 Update replay input handling to restore the canonical opaque state and reject package, ABI, layout, metric, or policy identity mismatches
- [ ] 9.3 Keep HIL pass/fail comparison based on desired versus authoritative rendered text without decoding caret, cursor, selection, mode, or other opaque-state fields
- [ ] 9.4 Require conformance setup to confirm F24 reservation before executing an Editor example
- [ ] 9.5 Preserve separate low-level F24 injection after successful Editor execution and wait for fixture consumption before taking the authoritative snapshot
- [ ] 9.6 Preserve semantic, planning, fixture, synchronization, transport, environment, and non-reproducible failure classifications under the new artifact schema
- [ ] 9.7 Update failing-sequence shrinking and replay artifacts to preserve exact pure-ABI inputs and outputs for every ordered step

## 10. Focused Behavioral Verification

- [ ] 10.1 Add focused ABI-value tests covering valid round-trips and every rejected Lua value shape and resource bound
- [ ] 10.2 Add focused fresh-VM and module-loader tests covering global/module mutation isolation, all export types, caching, missing modules, shadowing, and cycles
- [ ] 10.3 Add focused pure-planner tests proving identical inputs and identities return identical actions/state with no host action side effects
- [ ] 10.4 Add focused text-cost tests covering configured-layout cost, unrepresentable text, identity pinning, cache reuse, and no state/action mutation
- [ ] 10.5 Add focused symbolic compiler tests covering every action kind, malformed actions, atomic text failure, generic safety bounds, and reserved-policy F24 rejection
- [ ] 10.6 Add focused Editor transaction tests covering initialization, successful commit, pre-execution retention, no-action invariants, reset, serialization, and partial-execution `Unknown`
- [ ] 10.7 Add focused shared-Lua-library and Readline-package tests covering composition, stable candidate tie-breaking, opaque state, and deterministic reconciliation
- [ ] 10.8 Update focused app diagnostics and HIL runner tests for the new evidence schema and explicit conformance F24 policy
- [ ] 10.9 Run only the affected core, app, and HIL test targets and record passing evidence

## 11. End-to-End Validation and Cleanup

- [ ] 11.1 Run `sleepwalker-protocol-check` to confirm canonical usage and wire parity remain unchanged
- [ ] 11.2 Run `sleepwalker-fw-build` and confirm the unchanged generic firmware path still builds
- [ ] 11.3 Run `sleepwalker-apk-build` and confirm the shared Lua modules and migrated target package are bundled
- [ ] 11.4 Run the focused quick Editor conformance scenario on the commissioned physical bench and inspect its replayable artifact schema
- [ ] 11.5 Run `sleepwalker-smoke-composite sleepwalker-hil/bench.toml` and inspect the generated `summary.json` for passing affected-component evidence
- [ ] 11.6 Remove obsolete LCP/LCS, side-effecting ABI, Readline-state, legacy diagnostics, and compatibility scaffolding left after the clean cutover
- [ ] 11.7 Confirm no dedicated production-F24 feature test was added and that the ordinary production symbolic-key path remains policy-permissive
- [ ] 11.8 Update all affected OpenSpec task checkboxes and record the final passing no-hardware and physical artifact locations
