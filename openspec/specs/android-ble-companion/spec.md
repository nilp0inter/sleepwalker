## Purpose
Android companion reference application demonstrating Sleepwalker library integrations, user-facing control UI, and background BLE communication.
## Requirements
### Requirement: Reference app delegates to library
The Android reference app SHALL demonstrate and exercise public `sleepwalker-core` library behavior. It SHALL NOT own protocol encoding, keymap rendering, or HID semantic logic that belongs in the reusable library.

#### Scenario: App injects key through library
- **WHEN** the reference app or ADB command path requests a key injection
- **THEN** the command is encoded through `sleepwalker-core` library behavior rather than app-local protocol construction

### Requirement: ADB command path uses public surfaces
ADB-driven commands SHALL exercise the same library/session behavior available to normal library consumers.

#### Scenario: ADB mouse command uses library path
- **WHEN** HIL sends an ADB command for relative mouse movement or button click
- **THEN** the app delegates to the public library/session path and records structured diagnostics for that command sequence

### Requirement: Single BLE session ownership
BLE scan, connect, GATT write, MTU handling, and status notification parsing SHALL be owned by one session/service path rather than duplicated across app entry points.

#### Scenario: Receiver delegates long-running BLE work
- **WHEN** the ADB receiver receives a command requiring BLE I/O
- **THEN** it delegates to the service/session owner instead of performing independent scan/connect/write logic

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

### Requirement: Text input rejects invalid characters without approximating
The reference app SHALL not send HID operations for characters that the selected host profile cannot represent.

#### Scenario: Invalid character inserted
- **WHEN** the user inserts a character that the fixed host profile cannot represent
- **THEN** the app displays a structured error and sends no HID operations for that insertion

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

### Requirement: Companion app uses tap scripts for text streaming
The Android companion app UI and ADB text commands SHALL compile input text plans into keyboard tap script frames and transmit them in batches. The app SHALL pace inter-batch transmission at 390 ms — matched to the firmware drain time for a 32-tap batch (32 × 12 ms = 384 ms) plus a 6 ms margin — so the next batch arrives just as the current one finishes, keeping the firmware's 16-deep `hid_bridge` queue at approximately one item without overflow.

#### Scenario: Text streaming uses tap script with drain-rate pacing
- **WHEN** the app is connected and armed and the user streams text or sends an ADB type-text command
- **THEN** the app compiles the planned keystrokes into `KEYBOARD_TAP_SCRIPT` frames and sends them to the device with a 390 ms inter-batch delay, without app-side sleep delays between individual characters within a batch

### Requirement: Lossless encoded ADB text command
The Android companion ADB command path SHALL accept an encoded text payload for high-level text commands so generated property-test strings reach `sleepwalker-core` unchanged despite shell-sensitive characters.

#### Scenario: Encoded text decoded before planning
- **WHEN** an ADB text command includes an encoded UTF-8 text payload
- **THEN** the Android command receiver decodes it exactly once and passes the decoded string to the existing `sleepwalker-core` text planning and tap-script compilation path

#### Scenario: Shell-sensitive printable text preserved
- **WHEN** the encoded payload represents printable text containing spaces, quotes, backslashes, punctuation, or shell metacharacters
- **THEN** the Android command receiver observes the same decoded text that the HIL generated

#### Scenario: Existing plain text command remains available
- **WHEN** an existing smoke or caller sends the current plain text extra for a text command
- **THEN** the app continues to process that text through the existing public library path

### Requirement: Encoded text diagnostics
The Android companion SHALL emit structured diagnostics for encoded text commands sufficient for HIL identity artifacts to distinguish input corruption from downstream typing failures.

#### Scenario: Encoded command logs decoded input metadata
- **WHEN** the Android command receiver accepts an encoded text command
- **THEN** diagnostics include command identity, command sequence, decoded text length, and enough encoded or escaped text metadata for the HIL artifact to compare generated input with Android-received input

#### Scenario: Invalid encoded text rejected clearly
- **WHEN** an encoded text command contains invalid encoding or invalid UTF-8 for the selected payload format
- **THEN** the app reports a structured command failure and sends no HID operations for that command


### Requirement: Encoded Editor complete-text ADB command path
The Android companion ADB command path SHALL accept an encoded complete-text payload for Editor `setText` commands so generated snapshot-sequence strings reach `sleepwalker-core` unchanged despite shell-sensitive characters. The command path SHALL decode the encoded payload exactly once and pass the decoded complete snapshot to the `sleepwalker-core` Editor reconciliation API. The existing plain text and encoded append-only text command paths SHALL remain available.

#### Scenario: Encoded Editor text decoded before reconciliation
- **WHEN** an ADB Editor command includes an encoded UTF-8 complete-text payload
- **THEN** the Android command receiver decodes it exactly once and passes the decoded complete snapshot to the `sleepwalker-core` Editor `setText` path

#### Scenario: Shell-sensitive complete text preserved
- **WHEN** the encoded Editor payload represents complete text containing spaces, quotes, backslashes, punctuation, or shell metacharacters
- **THEN** the Android command receiver observes the same decoded text that the HIL generated

#### Scenario: Existing append-only text commands remain available
- **WHEN** an existing smoke or caller sends the current plain or encoded append-only text command
- **THEN** the app continues to process that text through the existing public library append-only text path

### Requirement: Editor execution through shared BLE session
The Android companion SHALL exercise the Editor through the same shared BLE session and service path that owns scan, connect, GATT write, MTU handling, and status notification parsing. Editor reconciliation plans SHALL be executed through the serialized executor and emitted as low-level keyboard operations over the shared BLE session. The app SHALL NOT duplicate BLE session logic for Editor commands.

#### Scenario: Editor plan uses shared BLE session
- **WHEN** an Editor `setText` command is processed
- **THEN** the reconciliation plan is executed through the serialized executor and its low-level operations are sent over the shared BLE session owned by the service path

#### Scenario: No duplicated BLE logic for Editor
- **WHEN** the ADB receiver receives an Editor command requiring BLE I/O
- **THEN** it delegates to the service/session owner instead of performing independent scan/connect/write logic

### Requirement: Serialized status-aware Editor execution
The Android companion SHALL execute Editor reconciliation plans in a serialized, status-aware manner. The executor SHALL correlate each emitted low-level operation with its firmware status notification using the command sequence identifier and SHALL NOT begin the next `setText` call until the current plan completes. The executor SHALL detect armed/disarmed/kill states and SHALL NOT emit Editor HID operations while the firmware is disarmed or killed.

#### Scenario: Editor operations correlated with status
- **WHEN** the executor emits a low-level operation from an Editor plan
- **THEN** it correlates the operation with the firmware status notification carrying the same sequence identifier before proceeding

#### Scenario: Editor execution serialized across calls
- **WHEN** multiple Editor `setText` commands arrive while one is executing
- **THEN** the executor completes the current plan before beginning the next and does not interleave them

#### Scenario: Disarmed firmware blocks Editor execution
- **WHEN** an Editor plan is ready to execute but the firmware is disarmed or killed
- **THEN** the executor does not emit Editor HID operations and reports a structured safety-state failure

### Requirement: Editor diagnostics
The Android companion SHALL emit structured diagnostics for Editor commands sufficient for HIL artifacts to distinguish input corruption from reconciliation and typing failures. Diagnostics SHALL include command identity, command sequence, decoded complete snapshot length, target package identity, host ABI version, plan operation count, and per-operation status correlation.

#### Scenario: Editor command logs reconciliation metadata
- **WHEN** the Android command receiver accepts an Editor `setText` command
- **THEN** diagnostics include command identity, command sequence, decoded snapshot length, target package identity, host ABI version, and plan operation count

#### Scenario: Invalid encoded Editor text rejected clearly
- **WHEN** an encoded Editor command contains invalid encoding or invalid UTF-8 for the selected payload format
- **THEN** the app reports a structured command failure and sends no HID operations for that command


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

