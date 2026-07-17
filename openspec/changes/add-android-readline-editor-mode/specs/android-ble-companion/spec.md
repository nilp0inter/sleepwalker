## MODIFIED Requirements

### Requirement: Reference app exposes capability demos
The Android reference app SHALL expose demo/debug surfaces for connection, arm/disarm/kill, low-level keyboard, low-level relative mouse, append-only high-level text planning, and stateful GNU Readline text reconciliation once supported by the library.

#### Scenario: Mouse demo command available
- **WHEN** the app is installed for HIL or manual demonstration
- **THEN** a caller can request a relative mouse click or movement through an explicit command surface

#### Scenario: Stateful Editor demo available
- **WHEN** the app is installed for manual demonstration or UI-driven HIL
- **THEN** a user can select a GNU Readline editor mode that exercises complete-snapshot reconciliation through the public `sleepwalker-core` Editor API

### Requirement: Minimal text demo UI
The Android reference app SHALL expose a minimal UI containing connection/safety affordances, a host profile selector, a text-semantics mode selector, a text input, Editor session/reset guidance, and status/error feedback.

#### Scenario: Main activity shows text demos
- **WHEN** the reference app main activity opens
- **THEN** it shows host profile selection, append-only and Readline editor mode selection, connect control, arm control, kill control, text input, and last-status or last-error display

### Requirement: Text input streams inserted valid characters
In append-only mode, the reference app text input SHALL stream inserted valid characters through the `sleepwalker-core` high-level text planner and low-level execution path.

#### Scenario: Valid character inserted in append-only mode
- **WHEN** the app is connected and armed, append-only mode is selected, and the user inserts a valid character into the text input
- **THEN** the app plans that inserted character through `sleepwalker-core` and sends the resulting low-level operations to the device

#### Scenario: Paste valid text in append-only mode
- **WHEN** the app is connected and armed, append-only mode is selected, and the user pastes valid text into the text input
- **THEN** the app plans and sends only the inserted pasted substring in character order

### Requirement: Text input is not a remote editor
The reference app SHALL treat text input in append-only mode as a keystroke stream rather than a synchronized remote text field. Deletions and local field clearing in append-only mode SHALL NOT be mirrored to the target host.

#### Scenario: Local deletion not mirrored in append-only mode
- **WHEN** the user deletes text from the Android text input while append-only mode is selected
- **THEN** the app updates the local field without sending backspace or delete HID operations to the device

### Requirement: Demo UI uses public library path
The reference app UI SHALL use `sleepwalker-core` text planning for append-only mode and the public `sleepwalker-core` Editor API for Readline editor mode. Both modes SHALL use the shared app/session send path rather than constructing protocol frames or owning BLE transport in UI code.

#### Scenario: Append-only UI text command uses library path
- **WHEN** the UI sends inserted text in append-only mode
- **THEN** command construction is delegated to `sleepwalker-core` text planning and BLE sending uses the shared app/session path

#### Scenario: Readline UI snapshot uses Editor path
- **WHEN** the UI submits a complete snapshot in Readline editor mode
- **THEN** reconciliation is delegated to the public `sleepwalker-core` Editor and its plan is executed by the shared service-owned BLE executor

## ADDED Requirements

### Requirement: Readline editor mode reconciles every text change
The Android reference app SHALL provide a Readline editor mode for the bundled `readline-emacs-ascii` target. While that mode is active, every accepted user-visible text change SHALL submit the text area's complete current value to `Editor.setText`, including insertion, deletion, replacement, paste, and clearing.

#### Scenario: Inserted text submits complete snapshot
- **WHEN** a user inserts text while Readline editor mode is active
- **THEN** the app submits the complete resulting text-area value to `Editor.setText` rather than only the inserted substring

#### Scenario: Deleted text submits complete snapshot
- **WHEN** a user deletes text while Readline editor mode is active
- **THEN** the app submits the complete resulting text-area value to `Editor.setText`

#### Scenario: Replacement or paste submits complete snapshot
- **WHEN** a user replaces a range or pastes text while Readline editor mode is active
- **THEN** the app submits the complete resulting text-area value to `Editor.setText` once for that text change

#### Scenario: Clearing submits empty snapshot
- **WHEN** a user clears the text area while Readline editor mode is active
- **THEN** the app submits the empty complete snapshot to `Editor.setText`

### Requirement: Readline UI changes preserve callback order
The Android reference app SHALL admit Readline editor snapshots to one FIFO command lane in the same order as the corresponding text-change callbacks. It SHALL NOT coalesce, debounce, reorder, or concurrently execute those snapshots, and SHALL NOT block the Android main thread while reconciliation waits for transport acknowledgements.

#### Scenario: Rapid changes remain ordered
- **WHEN** multiple text changes occur before the first Editor reconciliation completes
- **THEN** every complete snapshot is executed once in callback order without interleaved Editor plans

#### Scenario: Reconciliation does not block UI thread
- **WHEN** `Editor.setText` waits for one or more BLE operation acknowledgements
- **THEN** the Android main thread remains available for UI work

### Requirement: Readline mode enforces session isolation
The Android reference app SHALL prevent append-only target mutations and Editor-managed target mutations from being mixed within one assumed Readline session. After the first target-mutating operation, changing text semantics SHALL require an explicit session reset and an independently restored empty target.

#### Scenario: Mode locked after target mutation
- **WHEN** the selected text mode has emitted a target-mutating operation
- **THEN** the app prevents selecting the other text mode until the session is explicitly reset

#### Scenario: Reset discloses empty-target precondition
- **WHEN** a user requests a new Readline editor session or recovery reset
- **THEN** the UI states that the physical Readline target must already be empty and does not claim that `Editor.reset()` cleared or observed the target

### Requirement: Readline mode exposes target constraints
The Android reference app SHALL identify the Editor target as bundled `readline-emacs-ascii` with GNU Readline 8.2 Emacs-mode, printable-ASCII, single-line constraints. Unsupported complete snapshots SHALL be rejected through the Editor's structured planning result without emitting HID operations.

#### Scenario: Supported single-line ASCII snapshot
- **WHEN** Readline editor mode submits a complete snapshot containing only printable ASCII on one line
- **THEN** the app allows the Editor to plan and execute reconciliation for that snapshot

#### Scenario: Unsupported snapshot rejected
- **WHEN** Readline editor mode submits a snapshot containing an unsupported character or line break
- **THEN** the app reports the Editor planning failure and emits no HID operations for that snapshot

### Requirement: Readline mode reflects connection and Editor state
The Android reference app SHALL enable Readline editor input only while the shared BLE session is connected and the firmware safety state is armed. It SHALL report synchronized snapshots and structured Editor failures through the UI. If execution leaves the Editor in `Unknown`, the app SHALL disable further Readline edits until an explicit reset.

#### Scenario: Input disabled before safe connection
- **WHEN** Readline editor mode is selected while BLE is disconnected or firmware safety state is not armed
- **THEN** the text input is disabled and no Editor snapshot is submitted

#### Scenario: Planning failure remains recoverable
- **WHEN** `Editor.setText` returns a pre-execution planning failure
- **THEN** the UI reports the failure and permits a later valid snapshot because the prior assumed target state remains valid

#### Scenario: Unknown state blocks further edits
- **WHEN** a partial execution leaves the Editor state `Unknown`
- **THEN** the UI reports unknown target state, disables further Readline input, and requires explicit reset before another snapshot can be submitted

#### Scenario: Successful snapshot reports synchronization
- **WHEN** `Editor.setText` returns `Synced`
- **THEN** the UI identifies the requested complete snapshot as synchronized
