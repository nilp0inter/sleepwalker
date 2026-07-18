## Purpose
Pure layout-aware Lua planning environment, host adaptation boundary, and value/action schemas for version 1 pure data planner packages.

## Requirements

### Requirement: Pure Lua editor planning ABI version 1
The host SHALL invoke a trusted bundled target package through host ABI version 1 as a pure data transformation with exactly current rendered text, desired rendered text, and opaque prior state as planning inputs. The package SHALL return inert symbolic actions and opaque next state; it SHALL not emit, enqueue, or execute HID operations. The host SHALL expose neither ambient I/O, time, randomness, filesystem access, Android services, BLE, transport pacing, nor mutable planner state to the package.

#### Scenario: Planning is pure data transformation
- **WHEN** the same package identity receives identical current rendered text, desired rendered text, opaque prior state, layout identity, and metric identity
- **THEN** it returns identical symbolic actions and opaque next state without any HID, transport, or host-state side effect

### Requirement: Fresh invocation isolation
The host SHALL execute every opaque-state initializer and planning call in a fresh constrained Lua VM, reload immutable bundled shared and package modules for that invocation, and discard the VM after decoding the returned value. No global, closure, module cache, registry value, or other Lua runtime state SHALL survive into a later invocation.

#### Scenario: Prior VM mutation cannot affect planning
- **WHEN** a package mutates a Lua global or module-local table during one planning invocation and a later invocation receives the same explicit inputs and identities
- **THEN** the later invocation runs in a fresh environment and returns the same result it would have produced without the earlier invocation

### Requirement: Deterministic opaque state initialization and round-trip
A target package SHALL provide deterministic pure initialization of opaque state from the known initial rendered text. The host SHALL supply the last committed opaque state to each planning call and SHALL treat its representation and semantics as package-owned data rather than interpreting editor concepts.

#### Scenario: Initial opaque state is package-derived
- **WHEN** an Editor session is initialized with known rendered text `""`
- **THEN** the host obtains the initial opaque state from the selected package's pure initializer and preserves the returned value without decoding caret, selection, cursor, or mode fields

### Requirement: Constrained ABI value schema
ABI arguments and results SHALL use only nil, booleans, signed 64-bit integers, UTF-8 strings, and recursively composed tables with string keys or positive consecutive integer keys. Tables SHALL be acyclic, SHALL not use functions, userdata, threads, metatables, fractional or non-finite numbers, sparse arrays, non-string record keys, or mixed numeric/keyed array forms, and SHALL be bounded by documented host limits for nesting, entries, string bytes, and total encoded size.

#### Scenario: Invalid ABI value rejected
- **WHEN** a package returns an action or opaque state containing a function, cyclic table, metatable, sparse array, fractional or non-finite number, unsupported integer, or value exceeding an ABI bound
- **THEN** the host rejects the result before compiling or executing actions and retains its previously committed state

### Requirement: Symbolic action result schema
A planning result SHALL contain an ordered sequence of inert symbolic action data and opaque next state. Each action SHALL use a documented closed action kind and only data required to compile it to existing low-level keyboard operations, including symbolic USB usage and modifiers where applicable; action data SHALL not contain protocol frames, sequence identifiers, Kotlin objects, callbacks, or executable behavior.

#### Scenario: Symbolic actions are inert and decodable
- **WHEN** a target package returns a sequence containing a symbolic key tap and a symbolic modifier transition
- **THEN** the host can validate each action against the ABI schema before assigning execution details and no action has executed merely because the result was returned

### Requirement: Shared pure Lua planning modules
The host SHALL bundle reusable pure Lua modules for text differencing, state transformation, symbolic action construction, plan sequencing, candidate choice, and deterministic cost aggregation. Target packages SHALL compose these modules through ordinary Lua data flow; the modules SHALL not retain invocation state or access host side effects.

#### Scenario: Shared modules compose without host mutation
- **WHEN** a package composes a candidate from shared diff, action, sequencing, and cost modules
- **THEN** the composition returns only ABI-schema data and leaves host state, transport state, and pending HID operations unchanged

### Requirement: Planner composition keeps editor semantics package-local
A target package MAY compose state-only intermediate transformations for caret, cursor, selection, multiple-cursor, mode, or target-specific behavior while planning. Those transformations SHALL remain package-local opaque-state computations; no such concept SHALL be required in the host ABI action schema or exposed to public callers.

#### Scenario: Caret-only intermediate step remains internal
- **WHEN** a package uses an internal caret-only transformation to choose a later text-rendering action sequence
- **THEN** its returned result contains only symbolic actions and opaque next state and the Kotlin host does not receive a caret-only public request or decode a caret field

### Requirement: Pure layout-aware text-cost capability
The only Kotlin planning capability exposed to Lua SHALL be a read-only query that accepts a supplied string and returns its deterministic layout-aware text cost together with layout and metric identities. The query SHALL emit no symbolic or HID actions, mutate no Editor, package, or planner state, and SHALL not expose individual cursor, selection, or mode behavior.

#### Scenario: Text-cost query has no side effect
- **WHEN** a package queries the cost of the same supplied string twice under one configured layout and metric
- **THEN** both results and identities are identical and no action is emitted or state changed by either query

### Requirement: Deterministic layout and metric identity
Every planning invocation and retained result SHALL identify the exact keyboard-layout data identity and text-cost metric identity used for candidate comparison. A package SHALL compare costs only from the supplied query identities, and replay SHALL reject or classify as non-equivalent any attempt to reproduce a plan with different layout or metric identity.

#### Scenario: Replay identity mismatch is detected
- **WHEN** a retained planning result is replayed using a different layout-data or text-cost-metric identity
- **THEN** the replay reports the identity mismatch before claiming equivalent planning behavior
