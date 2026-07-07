## Context

Currently, the Android reference application `MainActivity` hardcodes text injection text planning to `HostProfile.LINUX_US`. While the library core can dynamically map characters to key sequences for multiple layout profiles (OS, layout, variant), the UI offers no way for the user to select their desired layout.

To support manual testing and dynamic verification of international layouts, the UI needs a layout selection dropdown.

## Goals / Non-Goals

**Goals:**
- Add a user-selectable dropdown menu (Spinner) in `MainActivity` to choose among the compiled layout profiles.
- Populate the dropdown dynamically from `GeneratedKeymapDatabase.profiles` to ensure only valid, compiled keymaps are shown.
- Update the text planning invocation in the text watcher to use the user-selected layout profile.

**Non-Goals:**
- No static XML layout files (keep UI fully programmatic in Kotlin to match existing codebase patterns).
- No complex multi-level layout filtering (use a single profile list derived from the registry).

## Decisions

### Decision: Single Profile Dropdown (Spinner)
We choose to use a single programmatic `Spinner` displaying the lookup keys of all registered layouts (e.g. `"linux:us"`, `"linux:us:intl"`) rather than three separate spinners for OS, Layout, and Variant.
- *Alternatives considered:*
  1. *Three dropdowns (OS, Layout, Variant):* Requires dynamic cascaded filtering (e.g. choosing Windows updates Layout spinner, etc.), leading to verbose, error-prone listener code and potential invalid combinations.
- *Rationale:* A single spinner populated directly from the list of valid `HostProfile` objects in the database guarantees that the user can only choose a layout profile that is compiled and available, while keeping the UI code minimal and robust.

### Decision: Programmatic Layout Modification in Kotlin
We will insert the dropdown programmatically in `MainActivity.onCreate` rather than defining a separate XML resource.
- *Rationale:* The existing `MainActivity` builds its view hierarchy programmatically via Kotlin DSL/builders. Adhering to this existing pattern avoids introducing layout files and keeps UI construction localized to a single file.

## Risks / Trade-offs

- **[Risk] Spinner rendering differences across Android versions**
  - *Mitigation:* We use standard Android framework adapters (`android.R.layout.simple_spinner_item` and `simple_spinner_dropdown_item`) which adapt cleanly to the system theme of any device version.
