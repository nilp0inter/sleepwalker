## ADDED Requirements

### Requirement: Public two-level library API
The `sleepwalker-core` Kotlin library SHALL expose a public two-level API consisting of low-level HID primitives and high-level text planning. The high-level API SHALL compose low-level primitives rather than bypassing them.

#### Scenario: High-level text uses low-level plan
- **WHEN** a caller requests high-level text input for a host profile
- **THEN** the library produces a sequence of low-level keyboard operations that can be inspected before execution

### Requirement: Low-level keyboard primitive API
The `sleepwalker-core` library SHALL expose low-level keyboard operations for arm, disarm, kill, release-all, key tap, key down, and key up using symbolic USB HID usages.

#### Scenario: Low-level key tap encoded
- **WHEN** a caller requests a low-level tap for `USB_KEY_SPACE`
- **THEN** the library encodes a sequenced `KEY_TAP` protocol frame carrying the USB usage for space

### Requirement: Low-level relative mouse primitive API
The `sleepwalker-core` library SHALL expose low-level relative mouse operations using a raw relative mouse report model with button mask, relative X/Y movement, vertical wheel, and horizontal pan fields.

#### Scenario: Left click planned as raw reports
- **WHEN** a caller requests a low-level left mouse click
- **THEN** the library emits a raw relative mouse report with the left button bit set followed by a raw relative mouse report with the button mask cleared

#### Scenario: Large movement chunked
- **WHEN** a caller requests relative mouse movement outside the signed 8-bit report range
- **THEN** the library splits the movement into multiple raw relative mouse reports whose per-axis deltas fit the protocol payload

### Requirement: Host profile model
The `sleepwalker-core` library SHALL model host text rendering through an explicit host profile containing host OS, keymap/layout identifier, and optional variant metadata.

#### Scenario: Host profile selected
- **WHEN** a caller selects a Linux host with a US keymap
- **THEN** the text planner uses the matching bundled keymap data for rendering decisions

### Requirement: Bundled keymap database boundary
The `sleepwalker-core` library SHALL use a bundled keymap database abstraction for host OS/layout/variant mappings. The public API SHALL allow the complete keymap corpus to be shipped as library data without changing text rendering APIs.

#### Scenario: Layout lookup succeeds
- **WHEN** a caller requests a host profile present in the bundled keymap database
- **THEN** the library resolves the host profile to keymap data usable by the text planner

#### Scenario: Layout lookup fails
- **WHEN** a caller requests a host profile absent from the bundled keymap database
- **THEN** the library reports a structured missing-layout failure

### Requirement: High-level text planning API
The `sleepwalker-core` library SHALL translate requested text into an inspectable execution plan of low-level keyboard operations for the selected host profile.

#### Scenario: Representable text planned
- **WHEN** text is representable by the selected host profile
- **THEN** the library returns an execution plan containing the required key down, key up, and key tap operations

### Requirement: Structured text rendering failures
The `sleepwalker-core` library SHALL report structured failures when text cannot be represented by the selected host profile rather than silently emitting approximate or incorrect keystrokes.

#### Scenario: Unrepresentable glyph rejected
- **WHEN** requested text contains a glyph that cannot be represented by the selected host profile
- **THEN** the library returns a structured unrepresentable-glyph failure and emits no HID operations for that request

### Requirement: Public session status correlation
The `sleepwalker-core` library SHALL expose command sequence identifiers and parsed status notifications so callers can correlate library operations with firmware acknowledgements.

#### Scenario: Status acknowledgement correlated
- **WHEN** firmware notifies a status for a command sequence sent by the library
- **THEN** the library exposes the status with the same sequence identifier and parsed status name
