## MODIFIED Requirements

### Requirement: Public two-level library API
The `sleepwalker-core` Kotlin library SHALL expose a public two-level API consisting of low-level HID primitives and high-level text planning. The high-level API SHALL compose low-level primitives rather than bypassing them. The library SHALL additionally expose a serialized stateful Editor reconciliation surface whose only public text mutation is `setText(completeDesiredText)` and that composes low-level primitives while retaining inspectable plan evidence. The Editor and executor SHALL NOT bypass the low-level keyboard API or expose caret, cursor, selection, mode, or package-state fields to public callers.

#### Scenario: High-level text uses low-level plan
- **WHEN** a caller requests high-level text input for a host profile
- **THEN** the library produces a sequence of low-level keyboard operations that can be inspected before execution

#### Scenario: Editor reconciliation uses low-level plan
- **WHEN** a caller invokes `setText` with a complete desired text snapshot
- **THEN** the Editor validates and compiles its package-returned symbolic actions into an inspectable sequence of existing low-level keyboard operations rather than bypassing the low-level keyboard API

#### Scenario: Editor public surface is text-only
- **WHEN** a caller inspects the Editor API and result types
- **THEN** `setText(completeDesiredText)` is the only public text-mutating Editor operation and no public type exposes caret, cursor, selection, mode, or opaque package-state fields

## ADDED Requirements

### Requirement: Lua planning invocation and validation boundary
The Editor SHALL invoke the selected Lua package using host ABI version 1 with current rendered text, desired rendered text, and opaque committed state, then validate the complete returned constrained value graph and symbolic action sequence before assigning sequence identifiers, compiling, or executing any operation. Kotlin SHALL not derive a text diff, choose reconciliation policy, predict caret behavior, or interpret package state.

#### Scenario: Invalid plan fails before execution
- **WHEN** Lua returns a malformed ABI value or unsupported symbolic action
- **THEN** the Editor returns a structured pre-execution failure, emits no low-level keyboard operation, and retains the previously committed rendered text and opaque state

### Requirement: Opaque transactional Editor state
The Editor SHALL obtain opaque initial state from the package's deterministic initializer for the known initial rendered text. It SHALL commit returned rendered text and opaque next state only after every compiled operation completes. If failure occurs before execution begins, it SHALL retain the prior committed values; if execution may have partially occurred, it SHALL mark the Editor state Unknown and reject further `setText` calls until authoritative recovery or explicit reset.

#### Scenario: Complete execution commits opaque state
- **WHEN** a valid package plan completes all compiled operations
- **THEN** the Editor commits the returned desired rendered text and opaque next state without interpreting its contents

#### Scenario: Possible partial execution becomes Unknown
- **WHEN** execution begins and an operation fails or completion cannot be established
- **THEN** the Editor does not commit the returned opaque next state, marks its session Unknown, and rejects subsequent `setText` calls pending authoritative recovery or explicit reset

#### Scenario: No-action result cannot advance target state
- **WHEN** Lua returns no symbolic actions but either the desired rendered text differs from the committed rendered text or the opaque next state differs structurally from the committed opaque state
- **THEN** the Editor rejects the result before commit and retains the previously committed rendered text and opaque state

### Requirement: Kotlin symbolic action compilation and execution
Kotlin SHALL validate symbolic actions solely against the ABI action schema, compile valid actions to existing public low-level keyboard operations, and execute the compiled operations through the serialized executor. Kotlin SHALL not attach target-specific editor meaning to an action or require package state to have named editor fields.

#### Scenario: Symbolic action compiles through existing path
- **WHEN** a validated package plan contains supported symbolic keyboard actions
- **THEN** Kotlin compiles them to existing public low-level keyboard operations and executes them through the same keyboard API used by other library consumers

### Requirement: Clean experimental ABI cutover at host ABI version 1
The implementation SHALL replace the experimental seven-argument planning function, side-effecting `host.*` plan builder, Kotlin LCP/LCS reconciliation, host-derived caret prediction, `ReadlineProgramState`, and generic target-specific state validation with the version-1 pure data ABI. It SHALL provide no compatibility shim, alias, dual invocation path, or fallback to the experimental ABI while host ABI version remains 1.

#### Scenario: Experimental ABI is not invocable
- **WHEN** a bundled package or host component attempts to use the experimental planning signature or side-effecting host plan builder
- **THEN** the invocation is unavailable and no plan is produced through a compatibility path

### Requirement: Retained planning evidence
For every attempted or executed Editor request, retained plan evidence SHALL record package identity, host ABI version, current and desired rendered text, opaque input and output state values, symbolic actions, compiled low-level operations, keyboard-layout identity, text-cost metric identity, execution outcome, and whether state was committed or became Unknown. It SHALL not record host-selected LCP/LCS replacement fields as planning evidence.

#### Scenario: Retained evidence supports ABI replay
- **WHEN** an Editor request produces a retained plan
- **THEN** the evidence contains the opaque state round-trip, symbolic action sequence, compiled operations, package and ABI identity, layout and metric identities, and transaction outcome needed to replay or diagnose the request
