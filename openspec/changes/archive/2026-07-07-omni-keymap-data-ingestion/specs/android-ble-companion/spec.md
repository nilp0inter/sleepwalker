## MODIFIED Requirements

### Requirement: ADB command path layout profile selection
The ADB text command SHALL accept optional parameters specifying the target OS, layout, and variant, and pass them to the library's text planner.

#### Scenario: Text typed on custom layout
- **WHEN** an ADB text command is received with parameters for OS (e.g., `linux`), layout (e.g., `us`), and variant (e.g., `intl`)
- **THEN** the app resolves these to a `HostProfile` and plans/transmits the text using that layout's mappings.
