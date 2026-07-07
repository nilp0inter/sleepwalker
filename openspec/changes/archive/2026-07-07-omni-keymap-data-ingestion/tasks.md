## 1. Protocol and Usages Expansion

- [x] 1.1 Add all 8 standard USB modifier usages (Left Control, Left Alt, Left Meta, Right Control, Right Shift, Right Alt, Right Meta) to `protocol/src/sleepwalker_protocol/usages.py`
- [x] 1.2 Mirror the modifier key symbolic usages in `android/sleepwalker-core/src/main/kotlin/io/sleepwalker/core/protocol/Usages.kt`
- [x] 1.3 Verify python protocol check passes using `sleepwalker-protocol-check`

## 2. Core Keymap and Planner Refactoring

- [x] 2.1 Refactor `KeymapEntry` in `HostProfile.kt` to represent a character mapping as a list of `KeymapTap` (usage, modifiers) instead of single int values
- [x] 2.2 Modernize `SeedKeymapDatabase.kt` to conform to the new `KeymapEntry` list of taps structure, preserving existing US layout behavior
- [x] 2.3 Refactor `TextPlanner.kt` to implement the stateful modifier tracking algorithm, tracking `currentModifiers` and only emitting `keyDown`/`keyUp` on state boundaries
- [x] 2.4 Update `TextPlanner` to append a final transition block at the end of planning to release all active modifiers and return to neutral state
- [x] 2.5 Ensure the new `TextPlanner` logic passes all existing `TextPlannerTest` cases

## 3. Code Generation Pipeline

- [x] 3.1 Implement the Python-based code generator script under `protocol/src/sleepwalker_protocol/generator.py` to compile JSON files into class-loaded Kotlin objects
- [x] 3.2 Ensure the generator script gracefully prints a warning and exits with code `0` if the database directory is missing
- [x] 3.3 Register the Gradle `generateKeymaps` task in `android/sleepwalker-core/build.gradle.kts` and hook it to run before Kotlin compilation
- [x] 3.4 Generate the initial default database layout classes (e.g. US layout) under `io.sleepwalker.core.keymap.generated` to ensure out-of-the-box compilation

## 4. Companion App ADB Integration

- [x] 4.1 Update `AdbCommandReceiver.kt` to parse `os`, `layout`, and `variant` parameters from the input intent
- [x] 4.2 Update `AdbCommandReceiver.kt`s text injection handler to pass the resolved layout profile to the `TextPlanner` instead of hardcoding `LINUX_US`

## 5. Verification and Smoke Testing

- [x] 5.1 Add unit tests in `TextPlannerTest.kt` covering multi-modifier mapping, dead-key sequences, and stateful planning modifier-count reduction
- [x] 5.2 Add unit tests in `TapScriptCompilerTest.kt` verifying stateful planner output compiles to the correct tap script frames
- [x] 5.3 Run a successful local build check `sleepwalker-core` tests
- [x] 5.4 Execute the composite smoke test with Nix shell to verify end-to-end typing behavior
