## Why

During hermetic builds (like Nix sandboxed builds) or automated CI/CD checks, the local parent directory `/home/nil/Projects/github.com/nilp0inter/OmniKeymap` is not accessible, breaking the layout generation step. Integrating the `OmniKeymap` database as a Nix Flake input ensures that the layout source JSONs are automatically fetched, cached, and available at Nix evaluation time to generate keymaps deterministically before compilation.

## What Changes

- **Flake Input Integration**: Add `omni-keymap` as a non-flake input pointing to `github:nilp0inter/OmniKeymap` in `flake.nix`.
- **Nix-Native Keymap Generation**: Define a derivation in `flake.nix` that executes the Python layout generator using the fetched flake input, producing the layout Kotlin classes.
- **Integrate Generated Keymaps into APK Build**: Update the `sleepwalker-apk-build` script to copy the Nix-generated Kotlin files into the Android project directory before executing Gradle compilation.
- **Nix CLI Overrides Support**: Enable developers to override the layout database source location locally by utilizing standard Nix `--override-input` arguments.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sleepwalker-core`: Update build system requirements to fetch and compile layout metadata hermetically utilizing the flake-input layout database source.
