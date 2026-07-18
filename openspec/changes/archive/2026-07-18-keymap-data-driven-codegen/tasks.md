## 1. Rewrite Generator Output

- [x] 1.1 Replace `generate_layout_class()` with a function that emits layout entries as inline data
- [x] 1.2 Replace `generate_registry()` with a function that emits a single `Map<HostProfile, List<KeymapEntry>>` literal
- [x] 1.3 Update `main()` to write a single `GeneratedKeymaps.kt` file instead of per-layout files + registry
- [x] 1.4 Ensure `GeneratedKeymapDatabase` object wraps the map and implements `KeymapDatabase.lookup()`
- [x] 1.5 Include `profiles` collection from the map keys
- [x] 2.1 Remove old per-layout `.kt` files from `android/sleepwalker-core/src/main/kotlin/io/sleepwalker/core/keymap/generated/`
- [x] 2.2 Update `.gitignore` entry if path changed
- [x] 2.3 Update `nix/apk-build.nix` copy destination if file name changed

## 3. Build and Verify

- [ ] 3.1 Test `nix build .#sleepwalker-keymap-gen` produces a single `GeneratedKeymaps.kt`
- [ ] 3.2 Test `nix build .#sleepwalker-apk-build` compiles without OOM
- [ ] 3.3 Install APK on device and verify all layouts are listed