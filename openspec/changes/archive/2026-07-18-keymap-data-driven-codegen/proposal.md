## Why

Generating 595 individual Kotlin files (one per keyboard layout) causes the Kotlin compiler to run out of memory even with 16GB heap. The per-file object approach creates excessive class loading and type-checking overhead. Embedding all layout data as compact data structures in a single generated file eliminates this bottleneck while preserving the same runtime lookup behavior.

## What Changes

- **Single Generated File**: Replace 595 per-layout Kotlin object files with one `GeneratedKeymaps.kt` containing all layout data as inline data structures (lists of data class instances).
- **Data-Driven Lookup**: Replace the `when`-based registry dispatch with a single `Map<HostProfile, List<KeymapEntry>>` built from the inline data.
- **Generator Rewrite**: Update `generator.py` to emit one file with a flat data table instead of per-layout objects and a registry.
- **Memory Efficiency**: The single file with inline data compiles in seconds vs OOM failures with 595 files.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sleepwalker-core`: Change the generated keymap representation from per-layout Kotlin objects to a single data-driven file with inline layout entries.