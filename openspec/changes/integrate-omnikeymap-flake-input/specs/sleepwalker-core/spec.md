## MODIFIED Requirements

### Requirement: Hermetic Layout Generation

The `sleepwalker-core` library MUST generate Kotlin layout classes from the OmniKeymap database during the Nix build process, ensuring hermetic builds without external network or filesystem dependencies.

#### Scenario: Nix Flake Input Fetches OmniKeymap Database

- **WHEN** a Nix build is initiated (e.g., `nix build .#sleepwalker-apk`)
- **THEN** the Nix flake input `omni-keymap` MUST automatically fetch the OmniKeymap database from `github:nilp0inter/OmniKeymap` at the pinned revision
- **AND** the database MUST be available in the Nix store at evaluation time

#### Scenario: Layout Generation Derivation Executes

- **WHEN** the Nix derivation `sleepwalker-keymap-gen` is built
- **THEN** it MUST execute `protocol/src/sleepwalker_protocol/generator.py` with `--db-path` pointing to the OmniKeymap flake input
- **AND** it MUST produce Kotlin layout classes in the output directory
- **AND** the derivation MUST be reproducible (same input → same output)

#### Scenario: Generated Layouts Are Copied Before APK Build

- **WHEN** `sleepwalker-apk-build` is invoked
- **THEN** it MUST copy the Nix-generated Kotlin layout classes into `android/sleepwalker-core/src/main/kotlin/` before executing Gradle compilation
- **AND** the copied files MUST overwrite any existing layout classes

#### Scenario: Local Override Supports Development

- **WHEN** a developer runs `nix build --override-input omni-keymap path:/local/OmniKeymap`
- **THEN** the build MUST use the local OmniKeymap directory instead of the GitHub input
- **AND** the layout generation MUST succeed with the overridden database path

#### Scenario: Build Fails Gracefully on Missing Database

- **WHEN** the OmniKeymap flake input is unreachable (e.g., network failure during fetch)
- **THEN** the Nix build MUST fail with a clear error message indicating the missing input
- **AND** the failure MUST occur before layout generation attempts
