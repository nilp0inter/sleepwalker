## Context

The Sleepwalker project currently generates Kotlin layout classes from the OmniKeymap database using a Python script (`protocol/src/sleepwalker_protocol/generator.py`). This script expects the OmniKeymap database to be available at a local path (currently `/home/nil/Projects/github.com/nilp0inter/OmniKeymap`).

**Current State:**
- The generator script works locally but breaks in hermetic environments (Nix sandboxed builds, CI/CD)
- The `flake.nix` defines tooling overlays but does not fetch the OmniKeymap database
- The `sleepwalker-apk-build` script assumes layout classes already exist in the Android source tree

**Constraints:**
- Nix builds must be fully hermetic (no network access during build)
- The solution must support local development with `--override-input`
- The generator script must not be modified (it already accepts `--db-path` and `--out-dir`)

## Goals / Non-Goals

**Goals:**
- Add `omni-keymap` as a Nix flake input pointing to `github:nilp0inter/OmniKeymap`
- Define a Nix derivation that executes the Python generator with the flake input as the database source
- Integrate the generated layouts into the APK build process by copying them before Gradle compilation
- Support `--override-input` for local development workflows

**Non-Goals:**
- Modifying the Python generator script (`generator.py`)
- Changing the OmniKeymap database schema or structure
- Implementing runtime layout fetching (this is a build-time solution only)
- Supporting multiple OmniKeymap repositories or forks

## Decisions

### Decision 1: Non-Flake Input for OmniKeymap

**Choice:** Add `omni-keymap` as a non-flake input using `github:nilp0inter/OmniKeymap` (no `flake:` prefix).

**Rationale:**
- OmniKeymap is a data repository, not a Nix flake
- Non-flake inputs are simpler and avoid requiring the upstream repo to define flake outputs
- Standard Nix pattern for consuming data/assets from GitHub repositories

**Alternatives Considered:**
- `flake:github:nilp0inter/OmniKeymap` — rejected because OmniKeymap doesn't define flake outputs
- `fetchTarball` in the derivation — rejected because it bypasses flake input pinning and `--override-input` support

### Decision 2: Separate Derivation for Layout Generation

**Choice:** Define a standalone Nix derivation `sleepwalker-keymap-gen` that produces the generated Kotlin files as its output.

**Rationale:**
- Separation of concerns: generation is distinct from APK compilation
- Enables caching: if OmniKeymap hasn't changed, the generation step is skipped
- Allows independent testing of the generation step (`nix build .#sleepwalker-keymap-gen`)

**Alternatives Considered:**
- Inline generation in `sleepwalker-apk-build` — rejected because it would re-run generation on every APK build, even if OmniKeymap hasn't changed
- Generate directly into the Android source tree during `nix build` — rejected because Nix builds are read-only; we can't write to the source tree

### Decision 3: Copy Generated Files in APK Build Script

**Choice:** Modify `nix/apk-build.nix` to accept an optional `keymapGen` parameter. If provided, copy the generated Kotlin files into `android/sleepwalker-core/src/main/kotlin/` before running Gradle.

**Rationale:**
- The APK build script is the natural integration point
- Copying ensures Gradle sees the generated files without modifying the source tree permanently
- Optional parameter allows the script to work without the flake input (e.g., if layouts are committed to the repo)

**Alternatives Considered:**
- Generate directly into a Gradle source set — rejected because it requires modifying the Android project structure
- Use a Gradle task to invoke the Python generator — rejected because it breaks hermeticity (Gradle would need network access or a pre-fetched database)

### Decision 4: Python Generator Invocation

**Choice:** Use `python3` from `nixpkgs` to execute `protocol/src/sleepwalker_protocol/generator.py` with `--db-path ${omni-keymap}` and `--out-dir $out`.

**Rationale:**
- The generator script already exists and works correctly
- No need to rewrite it in Nix or another language
- Using `python3` from `nixpkgs` ensures the Python version is pinned and reproducible

**Alternatives Considered:**
- Rewrite the generator in Kotlin — rejected because it would duplicate logic and break the existing workflow
- Use a Python package from `nixpkgs` — rejected because the generator is project-specific and not published

## Risks / Trade-offs

### Risk 1: OmniKeymap Repository Availability

**Risk:** If the `github:nilp0inter/OmniKeymap` repository is deleted, renamed, or made private, the flake input will fail to fetch, breaking all builds.

**Mitigation:**
- Pin the flake input to a specific commit (not `main` or `master`)
- Document the dependency in the project README
- Provide instructions for using `--override-input` with a local copy

**Trade-off:** We accept the risk of external dependency in exchange for automated database fetching.

### Risk 2: Generator Script Changes

**Risk:** If the generator script (`generator.py`) is modified in a way that changes its command-line interface or output format, the Nix derivation will break.

**Mitigation:**
- The generator script is part of this repository, so changes are visible in PRs
- Add a CI check that runs `nix build .#sleepwalker-keymap-gen` to catch breakage early

**Trade-off:** We accept tight coupling between the generator and the Nix derivation in exchange for simplicity.

### Risk 3: Generated File Conflicts

**Risk:** If the generated Kotlin files are committed to the repository, copying them during the APK build may cause conflicts or overwrite manual edits.

**Mitigation:**
- Document that generated files should not be committed to the repository
- Add a `.gitignore` entry for the generated layout classes
- Provide a script to regenerate layouts locally for development

**Trade-off:** We accept the risk of accidental commits in exchange for a simpler build process (no need to track generated files in version control).

### Risk 4: Build Time Overhead

**Risk:** Adding a layout generation step increases the total build time for the APK.

**Mitigation:**
- The generation step is cached by Nix; it only re-runs when OmniKeymap changes
- The generator is fast (Python script processing JSON files)

**Trade-off:** We accept a small build time increase (seconds) in exchange for hermetic builds.
