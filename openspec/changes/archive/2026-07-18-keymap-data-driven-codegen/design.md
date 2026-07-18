## Context

The current codegen produces 595 individual Kotlin object files (one per layout) plus a registry file. Compiling these 595 files causes the Kotlin compiler to consume >16GB of heap and OOM. Each file defines a Kotlin `object` with a `val entries: List<KeymapEntry>`, and the registry dispatches via nested `when` expressions. The type checker must resolve each object independently, creating massive memory pressure.

## Goals / Non-Goals

**Goals:**
- Eliminate Kotlin compiler OOM by reducing 595 files to one
- Preserve runtime lookup behavior and API surface
- Keep the generator script simple and deterministic

**Non-Goals:**
- Changing the `KeymapEntry` or `KeymapTap` data classes
- Changing the `KeymapDatabase` interface
- Changing the `HostProfile` class
- Optimizing runtime lookup performance (map lookup is already O(1))

## Decisions

### Decision 1: Single file with a Map literal

**Choice:** Generate one `GeneratedKeymaps.kt` file containing a `Map<HostProfile, List<KeymapEntry>>` initialized with all layout data inline.

**Rationale:**
- One file = one compilation unit = bounded memory
- A `mapOf()` with 595 entries is trivial for the Kotlin compiler
- No per-layout objects means no per-object type checking overhead
- The `KeymapDatabase.lookup()` implementation becomes a single map lookup

**Alternatives Considered:**
- JSON resource file loaded at runtime — rejected because it breaks hermetic compilation and adds I/O
- Splitting into N files by OS — rejected because it's fragile and doesn't solve the root cause (still too many objects)

### Decision 2: Flatten entries into the map value

**Choice:** Each map entry's value is a `listOf(KeymapEntry(...), KeymapEntry(...), ...)` with all character mappings for that layout inline.

**Rationale:**
- No intermediate objects — just data class constructor calls in a list
- The compiler handles a large `listOf()` far better than 595 separate object declarations
- The generated code is straightforward and readable

### Decision 3: HostProfile as map key

**Choice:** Use `HostProfile(os, layout, variant)` as the map key directly.

**Rationale:**
- `HostProfile` is already a data class with proper `equals`/`hashCode`
- The lookup becomes `map[profile]` — no `when` dispatch needed
- Variant `null` vs string is handled naturally by the data class equality

## Risks / Trade-offs

### Risk: Large generated file size

**Risk:** The single file may be several MB of source code.

**Mitigation:** Kotlin handles large files fine — the issue was 595 separate compilation units, not total code size. A single 2-3MB file compiles in seconds.

### Risk: Map key collision

**Risk:** Two layouts with the same `(os, layout, variant)` would overwrite each other in the map.

**Mitigation:** The generator already deduplicates by `(os, layout, variant)` — the previous registry used the same grouping. No new risk introduced.

### Trade-off: Lost per-layout object encapsulation

**Trade-off:** Individual layout objects (e.g., `LinuxUsKeymap.entries`) are no longer addressable by name.

**Acceptance:** Nothing in the codebase references individual layout objects directly — all access goes through `GeneratedKeymapDatabase.lookup()`. No external consumers exist.