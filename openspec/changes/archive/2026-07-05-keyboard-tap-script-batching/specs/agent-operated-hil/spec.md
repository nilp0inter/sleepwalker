## ADDED Requirements

### Requirement: Text batch HIL verification
The HIL text smoke scenario SHALL verify keyboard event sequence and timing when streaming text using the batched tap script path, including shifted repeated-key input that would expose release-gap coalescing.

#### Scenario: Batched text smoke passes
- **WHEN** the HIL text smoke scenario runs using the batched tap script path for `aA1`
- **THEN** it verifies the `KEY_A`, `KEY_LEFTSHIFT`, and `KEY_1` event sequence on the observer host and confirms the execution duration is within the expected speed envelope
