## Purpose

Lua editor mode spec.

## Requirements

### Requirement: Stateful Editor with single public complete-snapshot mutation

The `lua-editor-mode` capability SHALL expose a stateful Editor whose only public text mutation is `setText(completeDesiredText)`. The Editor SHALL accept the entire desired target document content as a single complete snapshot on every call and SHALL NOT expose incremental insert, delete, caret-move, or selection-change operations to public callers. Callers SHALL NOT manage cursor mechanics, caret position, selection anchors, target-program state, revision counters, or synchronization lifecycle.

#### Scenario: setText is the only public text mutation

- **WHEN** a caller inspects the public Editor surface
- **THEN** the only text-mutating operation exposed is `setText(completeDesiredText)` accepting a complete desired document snapshot
- **AND** no public operation exposes caret movement, selection extension, incremental insertion, or incremental deletion

#### Scenario: Complete snapshot replaces prior desired state

- **WHEN** a caller invokes `setText("hello")` followed by `setText("help")`
- **THEN** the Editor treats the second call as a complete desired snapshot of `"help"` and SHALL NOT append `"help"` to `"hello"`

### Requirement: Empty known initial target state

The Editor SHALL begin every session with a known empty target document, an empty assumed target program state, a neutral caret at the start of the empty document, no active selection, and a zero revision counter. The Editor SHALL NOT assume any non-empty target content before the first `setText` call.

#### Scenario: First setText starts from empty

- **WHEN** a caller creates a new Editor and immediately invokes `setText("abc")`
- **THEN** the Editor computes the transition from an empty assumed target document to `"abc"`
- **AND** the resulting plan inserts `"abc"` without any leading deletion or backspace operations

### Requirement: Internal hidden-state tracking

The Editor SHALL track the assumed target document, caret position, selection anchors, target-program state, revision counter, and synchronization lifecycle internally. Caret position, selection anchors, and target-program state SHALL be hidden from public callers and SHALL NOT appear in public Editor result types. The Editor SHALL use this hidden state only to compute and verify text transitions and SHALL expose it solely through internal inspectable artifacts used for verification.

#### Scenario: Caret hidden from caller

- **WHEN** a caller receives the result of a `setText` call
- **THEN** the public result type exposes the requested complete snapshot, the structured Editor outcome, and inspectable plan evidence
- **AND** the public result type does NOT expose caret position, selection anchor, or target-program internal state

#### Scenario: Hidden state tracked for next transition

- **WHEN** a caller invokes `setText("hello")` and then `setText("help")`
- **THEN** the Editor internally tracks the assumed caret and document after the first call and uses that hidden state to compute the second transition
- **AND** the second plan reconciles `"hello"` to `"help"` rather than reinserting the entire string

### Requirement: Internal text transition computation

The Editor SHALL compute the internal text transition between the previously assumed complete target snapshot and the new complete desired snapshot by delegating to a loaded target behavior package. For this change the transition SHALL be a minimal vertical slice computed deterministically by longest-common-prefix and longest-common-suffix between the assumed and desired snapshots, yielding exactly one contiguous replacement region. The Editor SHALL NOT produce multiple disjoint edits, wordwise optimization, or any optimization beyond the single contiguous replacement; those remain later scope. The transition computation SHALL produce an inspectable plan of low-level keyboard operations that, when executed, are predicted to transform the assumed target state into the desired target state, and SHALL preserve the exact desired text and predicted hidden target state. The Editor SHALL validate candidate reconciliations against the target behavior package before execution.

#### Scenario: Transition plan inspectable before execution

- **WHEN** the Editor computes a transition from `"hello"` to `"help"`
- **THEN** it produces an inspectable ordered plan of low-level keyboard operations before any operation is sent over BLE
- **AND** the plan can be examined for opcode, USB usage, sequence identifier, and modifier operations

#### Scenario: Single contiguous replacement via common prefix and suffix

- **WHEN** the Editor computes a transition from `"hello"` to `"help"`
- **THEN** it determines the longest common prefix `"hel"` and longest common suffix `"p"` and yields exactly one contiguous replacement of the differing middle region
- **AND** the plan does not contain multiple disjoint edits or wordwise operations

#### Scenario: Exact text and predicted hidden state preserved

- **WHEN** the Editor computes a transition plan
- **THEN** the predicted resulting target document equals the desired complete snapshot exactly
- **AND** the predicted hidden target state (caret, line buffer, editing mode) is preserved alongside the predicted document

#### Scenario: Candidate reconciliation validated

- **WHEN** the target behavior package reports that a candidate plan cannot reconcile the assumed state to the desired state
- **THEN** the Editor SHALL NOT execute the plan and SHALL return a structured semantic failure

### Requirement: Readline Emacs navigation composition

The pinned GNU Readline Emacs target package SHALL compose its plans from the existing `sleepwalker-core` `TextPlanner` plus a minimal set of Readline Emacs navigation and deletion behaviors: move-to-start (`C-a`), move-to-end (`C-e`), move-backward-char (`C-b`), move-forward-char (`C-f`), delete-char-forward (`C-d`), and backward-delete-char. The package SHALL NOT introduce broad new symbolic navigation usages beyond these. The package SHALL combine these navigation primitives with `TextPlanner` output to type the contiguous replacement region computed by the transition slice.

#### Scenario: Plan composed from Emacs primitives and TextPlanner

- **WHEN** the Readline target package generates a plan for a contiguous replacement region
- **THEN** the plan uses only `C-a`, `C-e`, `C-b`, `C-f`, `C-d`, backward-delete-char, and `TextPlanner` typing operations
- **AND** no other navigation usages are introduced

#### Scenario: Navigation reaches replacement region

- **WHEN** the replacement region begins at an offset within the line
- **THEN** the plan navigates the Readline cursor to that offset using `C-a` followed by `C-f` or `C-b` as needed
- **AND** deletes the differing region using `C-d` or backward-delete-char before typing the new text via `TextPlanner`

### Requirement: Serialized executor boundary

The Editor SHALL execute reconciliation plans through a serialized executor boundary that processes one `setText` call to completion before beginning the next. Concurrent `setText` calls SHALL enter the executor in arrival order, SHALL NOT be coalesced, and SHALL NOT interleave plan computation or execution. The serialized executor SHALL retain BLE ownership outside `sleepwalker-core` by emitting low-level operations through the public low-level keyboard API.

#### Scenario: Concurrent setText calls serialized

- **WHEN** two `setText` calls are issued concurrently
- **THEN** the executor completes the first call's plan computation and execution before beginning the second call's plan computation
- **AND** the two plans do not interleave

#### Scenario: Executor uses low-level keyboard API

- **WHEN** the executor executes a reconciliation plan
- **THEN** every emitted operation is one of the public low-level keyboard operations from the inspectable plan
- **AND** the executor does not construct protocol frames directly

### Requirement: Bundled trusted Lua target packages

The Editor SHALL load bundled, trusted Lua 5.4 target behavior packages. Target packages SHALL be app-bundled and trusted; the Editor SHALL NOT load dynamic or untrusted packages. Each target package SHALL declare the target environment it models and SHALL produce inspectable plans for text transitions.

#### Scenario: Bundled package loaded

- **WHEN** the Editor is initialized with the bundled GNU Readline Emacs target package
- **THEN** the package is loaded from app-bundled trusted assets and is available for transition computation

#### Scenario: Dynamic package rejected

- **WHEN** a caller attempts to load a target package that is not app-bundled
- **THEN** the Editor SHALL reject the package and SHALL NOT execute any plan from it

### Requirement: Versioned constrained Lua host ABI

The Editor SHALL provide a versioned, constrained Lua host ABI to target packages. The ABI SHALL be deterministic and SHALL expose only the operations required for target-state modeling and plan generation. The host ABI SHALL NOT expose BLE access, Android services, ambient I/O, filesystem access, real time or wall-clock time, random sources, or transport pacing to target packages. The ABI version SHALL be recorded with every plan so incompatible packages are rejected.

#### Scenario: Target package cannot access forbidden capabilities

- **WHEN** a target package attempts to access BLE, Android services, ambient I/O, filesystem, time, random sources, or transport pacing
- **THEN** the host ABI does not expose those capabilities and the attempt fails without side effects

#### Scenario: ABI version recorded with plan

- **WHEN** the Editor produces a plan from a target package
- **THEN** the plan records the host ABI version used to generate it
- **AND** a plan generated under an incompatible ABI version is rejected before execution

#### Scenario: Deterministic plan generation

- **WHEN** the same target package computes a transition from the same assumed state and the same desired snapshot
- **THEN** the resulting plan is identical across repeated invocations

### Requirement: Explicit transactional target-program state

Target packages SHALL receive target-program state as an explicit invocation input and SHALL return the predicted next target-program state as an explicit output. Target packages SHALL NOT depend on mutable Lua VM state retained between planning calls. The Editor SHALL commit the returned next state only after the complete reconciliation plan succeeds, SHALL discard it when execution has not begun and planning or validation fails, and SHALL mark the Editor state Unknown when execution may have been partial.

#### Scenario: Repeated invocation does not depend on VM history

- **WHEN** the same target package is invoked twice with the same explicit program state, assumed document, and desired snapshot
- **THEN** both invocations return identical plans and identical next program state regardless of prior uses of the Lua runtime

#### Scenario: Program state committed after complete execution

- **WHEN** a target package returns a valid plan and predicted next program state and the plan executes completely
- **THEN** the Editor commits the predicted next program state for the following `setText` call

#### Scenario: Unexecuted next state discarded

- **WHEN** planning or validation fails before any operation executes
- **THEN** the Editor discards the invocation's predicted next program state and retains its previously committed state

#### Scenario: Partial execution does not commit a prediction

- **WHEN** plan execution begins but may have completed only partially
- **THEN** the Editor does not commit the predicted next program state and marks the session Unknown

### Requirement: Pinned GNU Readline Emacs ASCII target package

The first bundled target package SHALL model a pinned GNU Readline Emacs-mode, single-line ASCII environment. The package SHALL model the Readline line buffer, cursor position, and Emacs-mode editing behavior for ASCII characters only. The package SHALL NOT model Vi mode, history, completion, submission, Unicode, graphemes, active selections, or wordwise optimization in this change.

#### Scenario: Readline Emacs target declared

- **WHEN** the bundled GNU Readline target package is loaded
- **THEN** it declares a pinned GNU Readline Emacs-mode, single-line ASCII target environment

#### Scenario: Out-of-scope behaviors excluded

- **WHEN** a caller invokes `setText` with content requiring Vi mode, history, completion, submission, Unicode, graphemes, active selections, or wordwise optimization
- **THEN** the target package reports the behavior as out of scope for this change rather than approximating it

### Requirement: Structured Editor results

The Editor SHALL return a structured Editor result for every `setText` call. The result SHALL distinguish success from failure, SHALL carry the requested complete snapshot, SHALL expose the inspectable plan when one was computed, and SHALL carry a structured failure classification when the call did not succeed. Public result types SHALL NOT expose hidden caret or selection state.

#### Scenario: Successful reconciliation result

- **WHEN** a `setText` call reconciles successfully
- **THEN** the Editor returns a structured success result containing the requested complete snapshot and the inspectable plan that was executed

#### Scenario: Failed reconciliation result

- **WHEN** a `setText` call fails to reconcile
- **THEN** the Editor returns a structured failure result containing the requested complete snapshot and a structured failure classification
- **AND** the result does not expose hidden caret or selection state

### Requirement: Assumed post-USB target state

The Editor SHALL treat the post-USB target document state as assumed unless it is independently observed. After executing a plan, the Editor SHALL update its assumed target state from the target behavior package prediction and SHALL NOT treat that predicted state as observed fact. The Editor SHALL mark the assumed state as unobserved until an authoritative fixture snapshot independently confirms it.

#### Scenario: Predicted state marked assumed

- **WHEN** the Editor executes a reconciliation plan
- **THEN** it updates its assumed target state from the target package prediction and marks that state as assumed, not observed

#### Scenario: Observed state requires independent confirmation

- **WHEN** an authoritative fixture snapshot confirms the target state after a plan execution
- **THEN** the Editor marks the matching state as observed
- **AND** in the absence of such confirmation the state remains marked assumed

### Requirement: Partial execution is terminal

The Editor SHALL treat partial execution of a reconciliation plan as a terminal condition. If a plan begins executing but cannot complete for any reason, the Editor SHALL NOT attempt generic recovery by re-derived a plan from an unknown target state. The Editor SHALL mark the assumed target state as Unknown and SHALL require an authoritative fixture snapshot or an explicit reset to empty known state before accepting further `setText` calls against the same target.

#### Scenario: Partial execution marks state Unknown

- **WHEN** a reconciliation plan begins executing but does not complete
- **THEN** the Editor marks the assumed target state as Unknown and returns a structured failure
- **AND** the Editor does not attempt generic recovery by re-deriving a plan from the unknown state

#### Scenario: Recovery requires reset or observation

- **WHEN** the assumed target state is Unknown
- **THEN** the Editor rejects further `setText` calls against that target until an authoritative fixture snapshot confirms the state or the Editor is explicitly reset to empty known state

### Requirement: Semantic failure classification

The Editor SHALL classify semantic failures separately from infrastructure failures. Semantic failures arise from target behavior package plan generation or reconciliation validation, including unsupported target behavior, unrepresentable content, and impossible transitions. Infrastructure failures arise from planning-external causes including fixture malfunction, synchronization failure, transport failure, environment failure, and non-reproducible hardware failure. The Editor SHALL NOT present an infrastructure failure as a semantic counterexample.

#### Scenario: Semantic failure classified distinctly

- **WHEN** a target behavior package reports that a transition cannot be reconciled
- **THEN** the Editor returns a structured failure classified as semantic
- **AND** the classification is distinct from planning, fixture, synchronization, transport, environment, and hardware failure classes

#### Scenario: Infrastructure failure not misclassified

- **WHEN** a plan execution fails due to a transport, fixture, synchronization, environment, or non-reproducible hardware cause
- **THEN** the Editor returns a structured failure classified under the matching infrastructure class
- **AND** the failure is not presented as a semantic reconciliation counterexample

### Requirement: Inspectable plan retention

The Editor SHALL retain inspectable plans for executed and attempted reconciliations so verification can examine the predicted operations and predicted target state alongside the requested complete snapshot. Retained plans SHALL include the host ABI version, the target package identity, the assumed prior state, the desired snapshot, the predicted resulting state, and the ordered low-level operations.

#### Scenario: Executed plan retained

- **WHEN** a `setText` call completes
- **THEN** the Editor retains the inspectable plan including ABI version, target package identity, assumed prior state, desired snapshot, predicted resulting state, and ordered low-level operations
- **AND** the retained plan is available to verification artifacts

### Requirement: Existing append-only text APIs preserved

The Editor SHALL NOT alter the existing public append-only high-level text planning API. The existing `TextPlanner` append-only streaming behavior SHALL remain available and unchanged unless a requirement explicitly adds Editor behavior to it. The Editor is an additional stateful surface coexisting with the append-only text path.

#### Scenario: Append-only text planner unchanged

- **WHEN** a caller uses the existing high-level text planning API without the Editor
- **THEN** the append-only streaming behavior remains available and unchanged
- **AND** the Editor does not modify or replace the existing text planning path
