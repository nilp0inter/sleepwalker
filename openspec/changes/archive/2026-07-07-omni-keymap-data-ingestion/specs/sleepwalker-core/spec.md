## MODIFIED Requirements

### Requirement: Keymap entry tap sequence mapping
A keymap entry mapping a character SHALL represent a sequence of one or more key taps (each containing a USB usage and modifier mask), supporting dead-key sequences and multi-modifier strokes.

#### Scenario: Dead key character planned
- **WHEN** a caller plans a character requiring a dead-key sequence (e.g., `'á'` requiring acute dead key followed by `'a'`)
- **THEN** the resulting plan contains the sequence of taps corresponding to the dead key and the base character.

### Requirement: Bundled keymap database boundary
The library SHALL use a generated keymap database that lazily loads layout-specific class definitions to avoid JVM class initializer limits and memory overhead.

#### Scenario: Layout lookup succeeds
- **WHEN** a caller requests a host profile present in the generated database
- **THEN** the library resolves the host profile to keymap data without loading other unrelated layouts into memory.

### Requirement: Modifier-state-aware text planning
The text planner SHALL track the active modifier state on the virtual keyboard and emit modifier `keyDown`/`keyUp` operations only when the required modifier state transitions, minimizing redundant modifier events.

#### Scenario: Sequence of uppercase letters planned
- **WHEN** a caller plans text `"HELLO"` for the US QWERTY host profile
- **THEN** the resulting plan contains a single `KEY_DOWN USB_KEY_LEFTSHIFT` at the start, followed by key taps for each letter, and a single `KEY_UP USB_KEY_LEFTSHIFT` at the end of the text.
