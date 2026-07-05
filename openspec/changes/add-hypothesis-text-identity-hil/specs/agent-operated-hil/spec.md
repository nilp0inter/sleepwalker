## ADDED Requirements

### Requirement: Property-based text identity HIL scenario
The HIL SHALL provide a Hypothesis-driven text identity scenario that sends generated supported text through the direct ADB command path, Android companion app, `sleepwalker-core`, BLE transport, ESP32-S3 firmware, and USB HID output, then verifies that the observer host captures the same text as target output.

#### Scenario: Generated Linux US text renders identically
- **WHEN** the property identity scenario generates a printable text string from the `linux:us` seed identity alphabet and sends it through the direct ADB text command path
- **THEN** the observer host's Linux console text sink captures exactly the same text bytes as the generated input

#### Scenario: Unsupported identity characters excluded
- **WHEN** the property identity scenario builds its initial `linux:us` generation alphabet
- **THEN** it includes printable seed-US text characters and excludes ESC and other control-key behaviors that do not have stable text-output identity semantics

### Requirement: Session-scoped property execution
The HIL property identity scenario SHALL amortize expensive bench setup across generated examples rather than resetting, reconnecting, or rebuilding the entire bench loop for each example.

#### Scenario: Setup shared across examples
- **WHEN** the quick identity profile starts on a commissioned bench
- **THEN** it validates bench configuration, resets the ESP32-S3, starts captures, prepares the observer text sink, connects BLE, and arms the firmware once before running generated examples

#### Scenario: Each generated example isolated
- **WHEN** a generated text example is executed inside the property run
- **THEN** the HIL resets the observer text sink before sending the example and compares only the output captured for that example

### Requirement: Quick and deep identity profiles
The HIL SHALL expose bounded quick and deep execution profiles for text identity testing. The quick profile SHALL be suitable for end-of-iteration verification on the commissioned bench, while the deep profile SHALL allow larger manual sweeps.

#### Scenario: Quick profile bounded
- **WHEN** the quick text identity profile runs
- **THEN** it uses bounded Hypothesis settings for example count and string length and writes those settings to the summary artifact

#### Scenario: Deep profile available
- **WHEN** a caller requests the deep text identity profile
- **THEN** the HIL runs the same identity property with larger configured bounds and the same artifact schema

### Requirement: Text identity failure artifacts
The HIL text identity scenario SHALL preserve enough structured evidence to classify and replay generated E2E text failures.

#### Scenario: Passing summary includes property metadata
- **WHEN** the text identity scenario passes
- **THEN** its summary records the profile, backend, alphabet, Hypothesis settings, generated example count, seed when available, duration, and artifact paths

#### Scenario: Failing example preserves layer evidence
- **WHEN** a generated text identity example fails
- **THEN** the artifact directory preserves generated input, Android-received input metadata, captured target output, failure classification, Hypothesis replay data, Android diagnostics, ESP UART diagnostics, and non-grabbing evdev diagnostics when available

#### Scenario: Flaky hardware failure classified separately
- **WHEN** a generated text identity example fails once but replaying the same example does not reproduce the failure
- **THEN** the scenario reports an infrastructure or timing instability classification rather than presenting the example as a deterministic text identity counterexample
