## Context

The current Sleepwalker text rendering system only supports a hardcoded US-QWERTY layout, modeled in a simple `SeedKeymapDatabase` class. Furthermore, the `TextPlanner` is modifier-stateless; for every shifted character, it explicitly emits `keyDown` and `keyUp` for the Shift key, creating massive command and BLE packet overhead when typing consecutive uppercase letters or symbols.

To support international keyboards, we need to ingest the OmniKeymap layout database (JSON format) and map characters to multi-tap key sequences (dead keys) and multi-modifier strokes (such as `AltGr`). We also want to optimize text injection throughput by tracking modifier states.

## Goals / Non-Goals

**Goals:**
- Ingest layout mapping JSONs from the OmniKeymap database and compile them into static Kotlin files for the `sleepwalker-core` library.
- Avoid JVM class initializer limits (64KB bytecode) and reduce memory footprint by splitting layouts into lazy class-loaded objects.
- Expand protocol/usages mapping to include all 8 USB HID modifier keys.
- Implement modifier-state awareness in the text planner to minimize modifier state transitions.
- Expose layout OS, layout, and variant parameters in the ADB companion app commands.

**Non-Goals:**
- No runtime parsing of JSON files in the Android application.
- No dynamic layout updates over BLE or network (all supported layouts are compiled into the core library).
- No modification to the ESP32-S3 firmware code (it already supports general tap scripts).

## Decisions

### Decision: Layouts split into separate Kotlin objects (Lazy Class Loading)
We choose to compile each layout file into its own Kotlin object file (e.g. `LinuxUsKeymap.kt`) under a generated subpackage.
- *Alternatives considered:*
  1. *Single huge database class:* Exceeds the JVM 64KB constructor/clinit method limit and loads all layouts into RAM at startup.
  2. *Compressed/encoded string database:* Saves code size but requires parsing string bytes and allocates garbage at runtime.
- *Rationale:* Splitting layouts into separate files leverages the JVM's lazy class-loading mechanism. The bytecode for `LinuxUsIntlKeymap` is only loaded and allocated when a user explicitly requests that layout, resulting in zero memory footprint for unused layouts and no method limit issues.

### Decision: Python-based build-time code generator script
We will implement the code generator as a Python script (`protocol/src/sleepwalker_protocol/generator.py`) and hook it into the Gradle build process of `sleepwalker-core`.
- *Alternatives considered:*
  1. *Kotlin-based build-time generator:* Requires Gradle Kotlin scripting/plugins, increasing Gradle configuration complexity.
- *Rationale:* The Python environment already has access to `sleepwalker_protocol/usages.py` containing the canonical symbolic usages mapping, allowing the generator to validate and translate symbolic names to USB usage codes easily.

### Decision: Stateful Modifier Planner
The `TextPlanner` will maintain `currentModifiers` state while planning text, emitting `keyDown` and `keyUp` commands only on modifier transition boundaries.
- *Alternatives considered:*
  1. *Stateless planning with compiler optimization:* Keep the planner simple and have `TapScriptCompiler` optimize the operations. However, this still leaves the raw (non-compiled) operation stream unoptimized and redundant.
- *Rationale:* Emitting minimal modifier transitions at the planning phase keeps both raw execution and compiled execution optimal.

## Risks / Trade-offs

- **[Risk] Out-of-sync layout state during raw operations**
  - *Mitigation:* The planner always appends a final transition block at the end of the text plan to release all active modifier keys. If a raw execution is interrupted, the existing safety `disarm`/`kill` command handler in the firmware automatically issues a full USB release report.
- **[Risk] Missing local database folder breaking build**
  - *Mitigation:* The generator task is configured to exit with code `0` and print a warning if the database path is missing. The generated files are checked into Git, ensuring the project builds successfully out-of-the-box.
