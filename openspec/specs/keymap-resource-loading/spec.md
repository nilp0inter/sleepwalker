## Purpose
Runtime JSON Keymap Loading: load keyboard layout data from bundled JSON resource files at runtime.

## Requirements

### Requirement: Runtime JSON Keymap Loading

The `sleepwalker-core` library MUST load keyboard layout data from bundled JSON resource files at runtime, replacing the previous approach of compiling generated Kotlin code.

#### Scenario: JSON resources are bundled in the APK

- **WHEN** the APK is built
- **THEN** the OmniKeymap `database/` directory MUST be copied into `android/sleepwalker-core/src/main/res/raw/keymaps/`
- **AND** the JSON files MUST be accessible via Android resources at runtime

#### Scenario: KeymapDatabase resolves from JSON at runtime

- **WHEN** `GeneratedKeymapDatabase.lookup(profile)` is called
- **THEN** it MUST parse the relevant JSON resource file(s) and return the same `List<KeymapEntry>` result as the previous compiled approach
- **AND** the parsing MUST happen lazily (first access) and be cached for subsequent calls

#### Scenario: No Kotlin codegen for keymap data

- **WHEN** the build pipeline runs
- **THEN** no `.kt` files MUST be generated from the OmniKeymap database
- **AND** the Kotlin compiler MUST NOT be required to process keymap layout data
