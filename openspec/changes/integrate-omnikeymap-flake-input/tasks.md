## 1. Flake Input Configuration

- [x] 1.1 Add `omni-keymap` input to `flake.nix` pointing to `github:nilp0inter/OmniKeymap`
- [x] 1.2 Pin the input to a specific commit hash for reproducibility
- [x] 1.3 Update flake outputs function signature to accept `omni-keymap` parameter

## 2. Layout Generation Derivation

- [x] 2.1 Create `sleepwalker-keymap-gen` derivation in `flake.nix`
- [x] 2.2 Configure derivation to use `python3` from `nixpkgs`
- [x] 2.3 Set derivation inputs: `protocol/src/sleepwalker_protocol/generator.py` and `omni-keymap` database
- [x] 2.4 Define build phase to execute generator with `--db-path ${omni-keymap}` and `--out-dir $out`
- [x] 2.5 Add derivation to flake outputs under `packages.<system>`

## 3. APK Build Integration

- [x] 3.1 Modify `nix/apk-build.nix` to accept optional `keymapGen` parameter
- [x] 3.2 Add logic to copy generated Kotlin files from `keymapGen` to `android/sleepwalker-core/src/main/kotlin/` before Gradle build
- [x] 3.3 Ensure copy operation overwrites existing layout classes
- [x] 3.4 Update `sleepwalkerOverlay` to pass `keymapGen` to `apk-build.nix`

## 4. Wiring and Testing

- [x] 4.1 Update `sleepwalker-apk` package in `flake.nix` to depend on `sleepwalker-keymap-gen`
- [x] 4.2 Test `nix build .#sleepwalker-keymap-gen` produces valid Kotlin files
- [x] 4.3 Test `nix build .#sleepwalker-apk` successfully builds with generated layouts
- [x] 4.4 Test `--override-input omni-keymap path:/local/path` works for local development
- [x] 4.5 Verify build fails gracefully when OmniKeymap input is unreachable

## 5. Documentation

- [x] 5.1 Update `AGENTS.md` to document the OmniKeymap flake input dependency
- [x] 5.2 Add `.gitignore` entry for generated layout classes in `android/sleepwalker-core/src/main/kotlin/`
- [x] 5.3 Document local development workflow with `--override-input` in project README or contributing guide
