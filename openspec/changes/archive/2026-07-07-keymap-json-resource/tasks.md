## 1. Remove Kotlin Codegen

- [x] 1.1 Remove `sleepwalker-keymap-gen` derivation from `flake.nix` overlay and packages
- [x] 1.2 Remove `nix/keymap-gen.nix` file
- [x] 1.3 Remove `keymapGen` parameter from `nix/apk-build.nix`
- [x] 1.4 Delete `android/sleepwalker-core/src/main/kotlin/io/sleepwalker/core/keymap/generated/` directory
- [x] 1.5 Remove `.gitignore` entry for generated keymap Kotlin files

## 2. Bundle JSON as Raw Resources

- [x] 2.1 Update `nix/apk-build.nix` to copy OmniKeymap `database/` into `android/sleepwalker-core/src/main/res/raw/keymaps/`
- [x] 2.2 Pass `omni-keymap` flake input path to `apk-build.nix` as `keymapDb` parameter
- [x] 2.3 Update `flake.nix` overlay to pass `keymapDb = omni-keymap` instead of `keymapGen`
- [x] 2.4 Verify JSON files appear in the APK's `res/raw/keymaps/` directory

## 3. Implement JsonKeymapDatabase

- [x] 3.1 Create `JsonKeymapDatabase.kt` in `sleepwalker-core` implementing `KeymapDatabase`
- [x] 3.2 Implement X11-to-USB key name mapping table in Kotlin
- [x] 3.3 Implement modifier name to bit flag mapping in Kotlin
- [x] 3.4 Implement JSON parsing: read `metadata.platform`, `metadata.layout_name`, variant from filename
- [x] 3.5 Implement mapping parsing: handle `sequence` arrays with `key` and `modifiers` fields
- [x] 3.6 Skip multi-character entries that cannot fit in a `Char`
- [x] 3.7 Build `Map<HostProfile, List<KeymapEntry>>` lazily on first access with caching
- [x] 3.8 Implement `lookup()` and `profiles` from the cached map

## 4. Wire Up and Verify

- [x] 4.1 Replace `GeneratedKeymapDatabase` references with `JsonKeymapDatabase` in the app
- [x] 4.2 Test `nix build .#sleepwalker-apk-build` compiles without OOM
- [x] 4.3 Install APK on device and verify all layouts are listed