## MODIFIED Requirements

### Requirement: Public two-level library API
The `sleepwalker-core` Kotlin library SHALL expose a public two-level API consisting of low-level HID primitives and high-level text planning. The high-level API SHALL compose low-level primitives rather than bypassing them. The library SHALL additionally expose a stateful Editor reconciliation surface and a serialized executor abstraction that compose low-level primitives while preserving inspectable low-level plans. The Editor and executor SHALL NOT bypass the low-level keyboard API.

#### Scenario: High-level text uses low-level plan
- **WHEN** a caller requests high-level text input for a host profile
- **THEN** the library produces a sequence of low-level keyboard operations that can be inspected before execution

#### Scenario: Editor reconciliation uses low-level plan
- **WHEN** a caller invokes the stateful Editor `setText` reconciliation
- **THEN** the Editor produces an inspectable plan of low-level keyboard operations composed through the low-level keyboard API rather than bypassing it

#### Scenario: Serialized executor preserves inspectable plans
- **WHEN** the serialized executor executes an Editor reconciliation plan
- **THEN** the inspectable plan remains available to callers and verification before and after execution
- **AND** the executor emits only public low-level keyboard operations from that plan

## ADDED Requirements

### Requirement: Stateful Editor reconciliation API

The `sleepwalker-core` library SHALL expose a stateful Editor reconciliation API whose only public text mutation is `setText(completeDesiredText)`. The Editor SHALL track assumed target document, caret, selection, target-program state, revision, and synchronization lifecycle internally and SHALL NOT expose caret or selection state to public callers. The Editor SHALL compute internal text transitions between successive complete snapshots as a minimal vertical slice using longest-common-prefix and longest-common-suffix yielding exactly one contiguous replacement, validate candidate reconciliations through a loaded target behavior package, and execute them through a serialized executor boundary. The Editor SHALL preserve the exact desired text and predicted hidden target state.

#### Scenario: Editor setText produces inspectable plan

- **WHEN** a caller invokes `setText("help")` against an Editor with assumed target `"hello"`
- **THEN** the Editor produces an inspectable plan of low-level keyboard operations that reconciles `"hello"` to `"help"` via one contiguous replacement before execution

#### Scenario: Editor hides caret from caller

- **WHEN** a caller receives an Editor result
- **THEN** the result exposes the requested complete snapshot and the structured outcome but does not expose caret position, selection anchor, or target-program internal state

#### Scenario: Editor starts from empty known state

- **WHEN** a caller creates a new Editor and invokes `setText("abc")`
- **THEN** the Editor computes the transition from an empty assumed target document to `"abc"`

#### Scenario: Single contiguous replacement only

- **WHEN** the Editor computes a transition between two snapshots
- **THEN** it produces exactly one contiguous replacement region via longest-common-prefix and longest-common-suffix and does not produce multiple disjoint edits or wordwise operations

### Requirement: Serialized Editor executor

The `sleepwalker-core` library SHALL provide a serialized executor that processes one Editor `setText` call to completion before beginning the next. The executor SHALL serialize concurrent calls in arrival order, SHALL NOT coalesce calls, and SHALL NOT interleave plan computation or execution. The executor SHALL retain BLE ownership outside `sleepwalker-core` by emitting operations through the public low-level keyboard API.

#### Scenario: Concurrent calls serialized

- **WHEN** two `setText` calls are issued concurrently to the Editor
- **THEN** the executor completes the first call before beginning the second and the two plans do not interleave

#### Scenario: Executor delegates to low-level API

- **WHEN** the executor executes an Editor plan
- **THEN** every emitted operation is a public low-level keyboard operation and the executor does not construct protocol frames directly

### Requirement: Bundled Lua host adapter

The `sleepwalker-core` library SHALL provide a bundled Lua 5.4 host adapter that loads trusted app-bundled target behavior packages through a versioned constrained host ABI. The adapter SHALL NOT expose BLE access, Android services, ambient I/O, filesystem access, real time, random sources, or transport pacing to target packages. The adapter SHALL record the host ABI version with every plan. It SHALL pass target-program state explicitly into each planning invocation, receive predicted next state explicitly, and SHALL NOT allow packages to depend on retained mutable Lua VM state between invocations.

#### Scenario: Trusted bundled package loaded

- **WHEN** the Editor initializes the Lua host adapter with the bundled GNU Readline target package
- **THEN** the adapter loads the package from trusted app-bundled assets and records the host ABI version

#### Scenario: Forbidden capabilities unavailable

- **WHEN** a target package attempts to access BLE, Android services, ambient I/O, filesystem, time, random sources, or transport pacing
- **THEN** the host adapter does not expose those capabilities and the attempt fails without side effects

#### Scenario: Lua state is explicit and transactional

- **WHEN** a target package computes a plan from an explicit assumed program state
- **THEN** the adapter returns the predicted next program state as an explicit output and does not retain it as hidden Lua VM state
- **AND** the Editor commits that next state only after complete plan execution

### Requirement: Editor structured failures

The `sleepwalker-core` library SHALL report structured Editor failures that classify semantic failures separately from infrastructure failures. Semantic failures include unsupported target behavior, unrepresentable content, and impossible transitions. Infrastructure failures include fixture malfunction, synchronization failure, transport failure, environment failure, and non-reproducible hardware failure. The library SHALL NOT present an infrastructure failure as a semantic reconciliation counterexample.

#### Scenario: Semantic failure structured

- **WHEN** a target behavior package reports an impossible transition
- **THEN** the Editor returns a structured semantic failure and emits no HID operations for that request

#### Scenario: Infrastructure failure classified separately

- **WHEN** an Editor plan execution fails due to a transport or fixture cause
- **THEN** the Editor returns a structured failure classified under the matching infrastructure class rather than as a semantic counterexample

### Requirement: Assumed and observed Editor state

The `sleepwalker-core` library SHALL treat post-USB target state as assumed unless it is independently observed by an authoritative fixture snapshot. After executing a plan, the Editor SHALL update its assumed state from the target package prediction and SHALL mark that state as assumed, not observed. Partial execution SHALL be terminal: the Editor SHALL mark the assumed state as Unknown and SHALL require an authoritative snapshot or explicit reset before accepting further `setText` calls against the same target.

#### Scenario: Predicted state marked assumed

- **WHEN** the Editor executes a reconciliation plan
- **THEN** it updates its assumed target state from the target package prediction and marks it assumed, not observed

#### Scenario: Partial execution marks state Unknown

- **WHEN** a reconciliation plan begins executing but does not complete
- **THEN** the Editor marks the assumed target state as Unknown and returns a structured failure without attempting generic recovery

#### Scenario: Unknown state rejects further calls

- **WHEN** the assumed target state is Unknown
- **THEN** the Editor rejects further `setText` calls against that target until an authoritative snapshot confirms the state or the Editor is explicitly reset to empty known state