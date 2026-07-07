## Context

Two attempts at compiling generated Kotlin keymap code failed with OOM:
1. 595 per-layout Kotlin object files — Kotlin compiler exceeded 16GB heap
2. Single 145K-line file with inline data — still exceeded 10GB and climbing

The root cause is that the Kotlin compiler's type checker and IR backend consume memory proportional to the number of expressions in a compilation unit. 1065 layouts × hundreds of `KeymapEntry` constructor calls each = millions of AST nodes.

The OmniKeymap database is already structured JSON. Bundling it as Android raw resources and parsing at runtime eliminates the compiler entirely from the keymap data path.

## Goals / Non-Goals

**Goals:**
- Eliminate Kotlin compiler OOM by removing all generated keymap code
- Bundle OmniKeymap JSON as Android raw resources (zero codegen)
- Implement a runtime `JsonKeymapDatabase` that parses JSON and implements `KeymapDatabase`
- Preserve the existing `KeymapDatabase.lookup()` API and `HostProfile` semantics

**Non-Goals:**
- Changing the `KeymapEntry`, `KeymapTap`, or `KeymapDatabase` interfaces
- Supporting network-fetched keymaps (resources are bundled at build time)
- Optimizing startup time (lazy parsing with caching is sufficient)

## Decisions

### Decision 1: Bundle JSON as Android raw resources

**Choice:** Copy the OmniKeymap `database/` directory into `android/sleepwalker-core/src/main/res/raw/keymaps/` during the APK build.

**Rationale:**
- Android raw resources are read-only, uncompressed, and accessible via `Resources.openRawResource()`
- No codegen step — the Nix derivation just copies files
- The database ships as data, not code

**Alternatives Considered:**
- Assets directory (`assets/`) — rejected because raw resources are more idiomatic for read-only data files in libraries
- Compile-time JSON → binary format — rejected as premature optimization

### Decision 2: Runtime JSON parser in Kotlin

**Choice:** Implement `JsonKeymapDatabase` in Kotlin using `org.json.JSONObject` (already available in the Android SDK).

**Rationale:**
- `org.json` is part of the Android platform — no external dependency
- The parser runs once at first access and caches the result
- The X11-to-USB key mapping table is a simple Kotlin `Map<String, Int>` constant

**Alternatives Considered:**
- kotlinx.serialization — rejected because it requires a codegen plugin and schema classes
- Gson/Moshi — rejected to avoid adding dependencies for a one-time parse

### Decision 3: Copy JSON via apk-build.nix, not via Nix derivation

**Choice:** The `apk-build.nix` script copies the OmniKeymap database from the flake input directly into the res/raw directory, replacing the previous keymapGen copy step.

**Rationale:**
- The flake input is already available as a Nix store path
- Copying JSON files is trivial — no Python derivation needed
- Removes the `sleepwalker-keymap-gen` derivation and `generator.py` from the build pipeline

## Risks / Trade-offs

### Risk: Runtime parsing latency

**Risk:** Parsing 1065 JSON files at first access could take noticeable time on a cold start.

**Mitigation:** Parse lazily on first `lookup()` call and cache the full map. Android devices are fast enough for JSON parsing — 1065 small files should parse in under 1 second.

### Trade-off: Increased APK size

**Trade-off:** The JSON database (~1-2MB) ships uncompressed in the APK instead of being compiled into `.dex` bytecode.

**Acceptance:** 1-2MB is negligible for an APK. The compiled `.kt` approach would have produced similar-sized `.class` files.

### Trade-off: X11 key mapping logic moves to Kotlin

**Trade-off:** The X11-to-USB key name mapping previously lived in `generator.py` (Python). Now it must be implemented in Kotlin.

**Acceptance:** The mapping is a simple lookup table. Having it in Kotlin means the runtime parser is self-contained.