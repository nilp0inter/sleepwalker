## Purpose
Core Sleepwalker library API and behaviors, including low-level HID primitives, relative mouse support, host profile matching, and high-level text planning.

## Requirements

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

### Requirement: Modifier-aware text planning
The `sleepwalker-core` text planner SHALL use keymap modifier metadata to emit explicit modifier key down/up operations around key taps.

#### Scenario: Shifted letter planned
- **WHEN** a caller plans text `A` for the seed US QWERTY host profile
- **THEN** the resulting plan contains `KEY_DOWN USB_KEY_LEFTSHIFT`, `KEY_TAP USB_KEY_A`, and `KEY_UP USB_KEY_LEFTSHIFT` in that order

#### Scenario: Unmodified letter planned
- **WHEN** a caller plans text `a` for the seed US QWERTY host profile
- **THEN** the resulting plan contains `KEY_TAP USB_KEY_A` without modifier operations

### Requirement: Complete seed US QWERTY printable subset
The bundled seed keymap database SHALL include direct and shifted mappings for printable US QWERTY ASCII characters and selected controls needed by the first text demo.

#### Scenario: Shifted digit punctuation planned
- **WHEN** a caller plans text `!` for the seed US QWERTY host profile
- **THEN** the resulting plan contains Shift around `USB_KEY_1`

#### Scenario: Space and digit planned
- **WHEN** a caller plans text ` 1` for the seed US QWERTY host profile
- **THEN** the resulting plan contains taps for `USB_KEY_SPACE` and `USB_KEY_1`

### Requirement: Inspectable text execution plan
The high-level text API SHALL return an inspectable ordered plan containing low-level keyboard operations before those operations are sent over BLE.

#### Scenario: Plan can be inspected
- **WHEN** a caller plans text `aA1`
- **THEN** the caller can inspect every low-level operation, including opcode, USB usage, sequence identifier, and modifier operations

### Requirement: Atomic text rendering failure
The text planner SHALL fail before emitting any HID operation when any character in the requested text is not representable by the selected host profile.

#### Scenario: Invalid glyph blocks whole request
- **WHEN** a caller plans text containing a glyph absent from the seed US QWERTY profile
- **THEN** the planner returns a structured unrepresentable-glyph failure and no low-level HID operations

### Requirement: Text execution uses low-level keyboard API
Executing a text plan SHALL send operations through the same low-level keyboard API exposed to library consumers.

#### Scenario: Text execution reuses low-level operations
- **WHEN** a text plan is executed
- **THEN** each emitted command is one of the public low-level keyboard operations from the plan
