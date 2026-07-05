## ADDED Requirements

### Requirement: Text plan tap script compilation
The library SHALL support compiling a planned sequence of keyboard operations into one or more compact keyboard tap scripts suitable for batch transmission, chunking the script if the number of taps exceeds a safe batch size limit.

#### Scenario: Long text plan chunked into scripts
- **WHEN** a caller requests text plan compilation for a text plan containing 100 keyboard taps and the safe batch size is set to 32
- **THEN** the library compiles the plan into 4 keyboard tap scripts (three with 32 taps and one with 4 taps)
