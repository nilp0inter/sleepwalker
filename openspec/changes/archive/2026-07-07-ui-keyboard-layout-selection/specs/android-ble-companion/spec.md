## MODIFIED Requirements

### Requirement: Minimal text demo UI
The Android reference app SHALL expose a UI containing connection/safety affordances, a dynamic host profile selection menu (OS, layout, and variant), a text input, and status/error feedback.

#### Scenario: Main activity shows layout selection
- **WHEN** the reference app main activity opens
- **THEN** it shows dropdown selectors for OS, layout, and variant populated from the available profiles in `GeneratedKeymapDatabase.profiles`, a connect control, an arm control, a kill control, a text input, and a last-status/last-error display.

#### Scenario: Text planned with user-selected profile
- **WHEN** the user inputs or pastes text into the text input
- **THEN** the app plans the text using the user's currently selected layout profile and transmits it to the device.
