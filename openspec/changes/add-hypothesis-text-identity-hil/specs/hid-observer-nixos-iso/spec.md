## ADDED Requirements

### Requirement: Linux console text sink
The observer ISO SHALL provide a raw-mode Linux console text sink that can act as the target output oracle for text identity HIL scenarios.

#### Scenario: Text sink captures rendered input
- **WHEN** the ESP32-S3 emits USB HID keyboard reports while the text sink owns the active Linux virtual console
- **THEN** the sink records the bytes delivered by the Linux console input path to an SSH-readable artifact

#### Scenario: Text sink runs in raw mode
- **WHEN** the text sink is active for an identity test
- **THEN** it disables canonical input buffering, echo, and terminal signal interpretation so printable text can be captured without shell or line-editing side effects

#### Scenario: Text sink reset isolates examples
- **WHEN** the HIL resets the text sink before a generated example
- **THEN** subsequent reads expose only text captured after that reset

### Requirement: Console keymap configuration for identity tests
The observer environment SHALL allow HIL automation to configure the Linux console keymap used by the text sink for supported identity-test profiles.

#### Scenario: Linux US console keymap selected
- **WHEN** the text identity scenario prepares the `linux:us` backend
- **THEN** the observer host is configured to use the Linux console US keymap before generated text is sent

### Requirement: Non-exclusive evdev diagnostics for text identity
The observer environment SHALL allow identity tests to collect evdev diagnostics without exclusively grabbing the Sleepwalker keyboard event device, so the Linux console text sink still receives injected input.

#### Scenario: Text identity observes without grab
- **WHEN** the text identity scenario starts optional evdev diagnostics
- **THEN** the observer helper is run without exclusive grab and injected HID events remain deliverable to the console text sink

#### Scenario: Existing grab smokes remain supported
- **WHEN** fixed keyboard, mouse, text, or composite smoke scenarios request exclusive observation
- **THEN** the observer helper still supports exclusive grab behavior for those scenarios
