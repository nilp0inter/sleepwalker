## ADDED Requirements

### Requirement: Explicit Editor F24 conformance policy
The Editor conformance HIL SHALL configure F24 reservation explicitly for the conformance run. Under that policy, the conformance Editor plan compiler SHALL reject symbolic F24 actions from the package so the separately injected physical F24 synchronization barrier remains authoritative; the policy SHALL be recorded in artifacts.

#### Scenario: Conformance plan reserves F24
- **WHEN** a conformance-run package plan contains a symbolic F24 action
- **THEN** the compiler rejects the plan before execution and the artifact records that F24 reservation policy was active

### Requirement: Conformance barrier remains fixture-authoritative
After each successfully completed Editor plan in a conformance run, HIL SHALL inject the physical F24 barrier using the existing low-level keyboard path and wait for the fixture to acknowledge consumption before obtaining the authoritative rendered-text snapshot. HIL SHALL not infer consumption from fixed delays or from event absence.

#### Scenario: Barrier precedes authoritative snapshot
- **WHEN** a conformance Editor request completes execution
- **THEN** HIL waits for fixture acknowledgement of the separately injected F24 barrier before recording the authoritative snapshot

### Requirement: ABI conformance and replay artifacts
Editor conformance artifacts SHALL preserve, for every request, current and desired rendered text, opaque input and output state, symbolic actions, compiled low-level operations, package identity, host ABI version, keyboard-layout identity, text-cost metric identity, F24 reservation policy, execution outcome, and authoritative fixture result. Failing sequences SHALL additionally preserve sequence ordering and replay data sufficient to rerun the same ABI inputs without regenerating them.

#### Scenario: Failing ABI sequence is replayable
- **WHEN** an Editor conformance sequence fails
- **THEN** its artifacts identify the failing request and preserve its opaque state values, symbolic actions, compiled operations, package/ABI identity, layout/metric identities, policy, fixture result, and replay sequence data

### Requirement: Conformance validates rendered text without decoding state
The HIL conformance oracle SHALL compare requested and authoritative rendered text and may retain opaque package-state values for replay, but SHALL not decode or assert caret, cursor, selection, mode, or any target-specific field within opaque state.

#### Scenario: Opaque state remains opaque to HIL
- **WHEN** a conformance artifact contains a package state value
- **THEN** HIL records and replays the value as constrained opaque data while determining pass or failure from rendered-text and execution evidence rather than named editor-state fields
