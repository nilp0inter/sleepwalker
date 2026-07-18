## MODIFIED Requirements

### Requirement: Single-File Data-Driven Keymap Generation

The `sleepwalker-core` keymap generator MUST emit a single Kotlin file containing all layout data as inline data structures, rather than one file per layout.

#### Scenario: Generator produces a single file

- **WHEN** the keymap generation derivation is built
- **THEN** the output directory MUST contain exactly one Kotlin file (`GeneratedKeymaps.kt`)
- **AND** that file MUST contain all 595 layouts as inline `List<KeymapEntry>` data
- **AND** the file MUST compile without exceeding 2GB of JVM heap

#### Scenario: Runtime lookup preserves existing behavior

- **WHEN** `GeneratedKeymapDatabase.lookup(profile)` is called with a `HostProfile`
- **THEN** it MUST return the same `List<KeymapEntry>` result as the previous per-object approach
- **AND** the lookup MUST use a `Map<HostProfile, List<KeymapEntry>>` built at initialization

#### Scenario: No per-layout Kotlin objects generated

- **WHEN** the generator processes the OmniKeymap database
- **THEN** it MUST NOT emit individual `<OS><Layout>Keymap.kt` files
- **AND** it MUST NOT emit individual Kotlin `object` declarations per layout