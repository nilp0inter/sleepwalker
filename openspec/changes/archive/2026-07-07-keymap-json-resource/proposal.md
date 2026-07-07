## Why

Both per-layout Kotlin objects (595 files → OOM) and single-file inline data (145K lines → OOM) fail to compile. The Kotlin compiler cannot handle the volume of generated keymap code regardless of file organization. Bundling the OmniKeymap JSON files as Android raw resources and parsing them at runtime eliminates codegen entirely — no Kotlin compilation of keymap data, no OOM, and the database updates ship as data not code.

## What Changes

- **No Kotlin codegen**: Remove `generator.py` output from the build. The `sleepwalker-keymap-gen` derivation instead copies the OmniKeymap JSON database into the Android raw resources directory.
- **Runtime JSON parser**: Add a `JsonKeymapDatabase` class in `sleepwalker-core` that reads the bundled JSON files at initialization and builds the `Map<HostProfile, List<KeymapEntry>>` in memory.
- **Raw resource bundling**: The `apk-build.nix` script copies the OmniKeymap `database/` directory into `android/sleepwalker-core/src/main/res/raw/keymaps/` before Gradle build.
- **Remove old generated Kotlin**: Delete the `generated/` Kotlin package and all per-layout object files.

## Capabilities

### New Capabilities

- `keymap-resource-loading`: Runtime loading of keyboard layout data from bundled JSON resources instead of compiled Kotlin code.

### Modified Capabilities

- `sleepwalker-core`: Replace generated Kotlin keymap objects with a JSON resource parser that produces the same `KeymapDatabase` interface at runtime.