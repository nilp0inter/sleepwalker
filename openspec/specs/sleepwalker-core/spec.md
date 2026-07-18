## Purpose
Core Sleepwalker library API and behaviors, including low-level HID primitives, relative mouse support, host profile matching, and high-level text planning.

## Requirements

### Requirement: Public two-level library API
The `sleepwalker-core` Kotlin library SHALL expose a public two-level API consisting of low-level HID primitives and high-level text planning. The high-level API SHALL compose low-level primitives rather than bypassing them. The library SHALL additionally expose a serialized stateful Editor reconciliation surface whose only public text mutation is `setText(completeDesiredText)` and that composes low-level primitives while retaining inspectable plan evidence. The Editor and executor SHALL NOT bypass the low-level keyboard API or expose caret, cursor, selection, mode, or package-state fields to public callers.

#### Scenario: High-level text uses low-level plan
- **WHEN** a caller requests high-level text input for a host profile
- **THEN** the library produces a sequence of low-level keyboard operations that can be inspected before execution

#### Scenario: Editor reconciliation uses low-level plan
- **WHEN** a caller invokes `setText` with a complete desired text snapshot
- **THEN** the Editor validates and compiles its package-returned symbolic actions into an inspectable sequence of existing low-level keyboard operations rather than bypassing the low-level keyboard API

#### Scenario: Editor public surface is text-only
- **WHEN** a caller inspects the Editor API and result types
- **THEN** `setText(completeDesiredText)` is the only public text-mutating Editor operation and no public type exposes caret, cursor, selection, mode, or opaque package-state fields

#### Scenario: Serialized executor preserves inspectable plans
- **WHEN** the serialized executor executes an Editor reconciliation plan
- **THEN** the inspectable plan remains available to callers and verification before and after execution
- **AND** the executor emits only public low-level keyboard operations from that plan
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

### Requirement: Text plan tap script compilation
The library SHALL support compiling a planned sequence of keyboard operations into one or more compact keyboard tap scripts suitable for batch transmission, chunking the script if the number of taps exceeds a safe batch size limit.

#### Scenario: Long text plan chunked into scripts
- **WHEN** a caller requests text plan compilation for a text plan containing 100 keyboard taps and the safe batch size is set to 32
- **THEN** the library compiles the plan into 4 keyboard tap scripts (three with 32 taps and one with 4 taps)

### Requirement: JSON Resource Keymap Parser

The system MUST provide a `JsonKeymapDatabase` class that implements `KeymapDatabase` by parsing bundled JSON resources at runtime.

#### Scenario: Parse a single layout JSON file

- **WHEN** a JSON file from the OmniKeymap database is loaded
- **THEN** the parser MUST extract `metadata.platform` as the OS, `metadata.layout_name` as the layout, and the variant from the filename (`<layout>+<variant>.json`)
- **AND** each character mapping MUST be converted to `KeymapEntry` with `KeymapTap` objects containing USB HID usage IDs and modifier bitmasks

#### Scenario: Map X11 key names to USB HID usages

- **WHEN** the parser encounters a key name like "KeyW" or "BracketLeft" in the JSON
- **THEN** it MUST map it to the corresponding USB HID usage ID using an X11-to-USB translation table
- **AND** modifier names like "Shift", "Control" MUST be mapped to their bit flag values

#### Scenario: Build a lookup map from all bundled JSON files

- **WHEN** `JsonKeymapDatabase` is initialized
- **THEN** it MUST scan the `res/raw/keymaps/` resource directory for all `*.json` files
- **AND** build a `Map<HostProfile, List<KeymapEntry>>` from all valid layouts
- **AND** skip entries with multi-character strings that cannot fit in a `Char`

### Requirement: Hermetic Layout Generation

The `sleepwalker-core` library MUST generate Kotlin layout classes from the OmniKeymap database during the Nix build process, ensuring hermetic builds without external network or filesystem dependencies.

#### Scenario: Nix Flake Input Fetches OmniKeymap Database

- **WHEN** a Nix build is initiated (e.g., `nix build .#sleepwalker-apk`)
- **THEN** the Nix flake input `omni-keymap` MUST automatically fetch the OmniKeymap database from `github:nilp0inter/OmniKeymap` at the pinned revision
- **AND** the database MUST be available in the Nix store at evaluation time

#### Scenario: Layout Generation Derivation Executes

- **WHEN** the Nix derivation `sleepwalker-keymap-gen` is built
- **THEN** it MUST execute `protocol/src/sleepwalker_protocol/generator.py` with `--db-path` pointing to the OmniKeymap flake input
- **AND** it MUST produce Kotlin layout classes in the output directory
- **AND** the derivation MUST be reproducible (same input → same output)

#### Scenario: Generated Layouts Are Copied Before APK Build

- **WHEN** `sleepwalker-apk-build` is invoked
- **THEN** it MUST copy the Nix-generated Kotlin layout classes into `android/sleepwalker-core/src/main/kotlin/` before executing Gradle compilation
- **AND** the copied files MUST overwrite any existing layout classes

#### Scenario: Local Override Supports Development

- **WHEN** a developer runs `nix build --override-input omni-keymap path:/local/OmniKeymap`
- **THEN** the build MUST use the local OmniKeymap directory instead of the GitHub input
- **AND** the layout generation MUST succeed with the overridden database path

#### Scenario: Build Fails Gracefully on Missing Database

- **WHEN** the OmniKeymap flake input is unreachable (e.g., network failure during fetch)
- **THEN** the Nix build MUST fail with a clear error message indicating the missing input
- **AND** the failure MUST occur before layout generation attempts

### Requirement: Single-File Data-Driven Keymap Generation

The `sleepwalker-core` keymap generator MUST emit a single Kotlin file containing all layout data as inline data structures, rather than one file per layout.

#### Scenario: Generator produces a single file

- **WHEN** the keymap generation derivation is built
- **THEN** the output directory MUST contain exactly one Kotlin file (`GeneratedKeymaps.kt`)
- **AND** that file MUST contain all 595 layouts as inline `List<KeymapEntry>` data
- **AND** the file MUST compile without exceeding 2GB of JVM heap

#### Scenario: Runtime lookup preserves existing behavior

- **WHEN** `GeneratedKeymapDatabase.lookup(profile)` is called with a `HostProfile`
- **THEN** it MUST return the same `List<KeymapEntry>` result as the previous per-object approach
- **AND** the lookup MUST use a `Map<HostProfile, List<KeymapEntry>>` built at initialization

#### Scenario: No per-layout Kotlin objects generated

- **WHEN** the generator processes the OmniKeymap database
- **THEN** it MUST NOT emit individual `<OS><Layout>Keymap.kt` files
- **AND** it MUST NOT emit individual Kotlin `object` declarations per layout

### Requirement: Stateful Editor reconciliation API
The `sleepwalker-core` library SHALL expose a stateful Editor reconciliation API whose only public text mutation is `setText(completeDesiredText)`. The Editor SHALL track assumed target document, caret, selection, target-program state, revision, and synchronization lifecycle internally and SHALL NOT expose caret or selection state to public callers. The Editor SHALL compute internal text transitions between successive complete snapshots as a minimal vertical slice using longest-common-prefix and longest-common-suffix yielding exactly one contiguous replacement, validate candidate reconciliations through a loaded target behavior package, and execute them through a serialized executor boundary. The Editor SHALL preserve the exact desired text and predicted hidden target state.

#### Scenario: Editor setText produces inspectable plan
- **WHEN** a caller invokes `setText("help")` against an Editor with assumed target `"hello"`
- **THEN** the Editor produces an inspectable plan of low-level keyboard operations that reconciles `"hello"` to `"help"` via one contiguous replacement before execution

#### Scenario: Editor hides caret from caller
- **WHEN** a caller receives an Editor result
- **THEN** the result exposes the requested complete snapshot and the structured outcome but does not expose caret position, selection anchor, or target-program internal state

#### Scenario: Editor starts from empty known state
- **WHEN** a caller creates a new Editor and invokes `setText("abc")`
- **THEN** the Editor computes the transition from an empty assumed target document to `"abc"`

#### Scenario: Single contiguous replacement only
- **WHEN** the Editor computes a transition between two snapshots
- **THEN** it produces exactly one contiguous replacement region via longest-common-prefix and longest-common-suffix and does not produce multiple disjoint edits or wordwise operations

### Requirement: Serialized Editor executor
The `sleepwalker-core` library SHALL provide a serialized executor that processes one Editor `setText` call to completion before beginning the next. The executor SHALL serialize concurrent calls in arrival order, SHALL NOT coalesce calls, and SHALL NOT interleave plan computation or execution. The executor SHALL retain BLE ownership outside `sleepwalker-core` by emitting operations through the public low-level keyboard API.

#### Scenario: Concurrent calls serialized
- **WHEN** two `setText` calls are issued concurrently to the Editor
- **THEN** the executor completes the first call before beginning the second and the two plans do not interleave

#### Scenario: Executor delegates to low-level API
- **WHEN** the executor executes an Editor plan
- **THEN** every emitted operation is a public low-level keyboard operation and the executor does not construct protocol frames directly

### Requirement: Bundled Lua host adapter
The `sleepwalker-core` library SHALL provide a bundled Lua 5.4 host adapter that loads trusted app-bundled target behavior packages through a versioned constrained host ABI. The adapter SHALL NOT expose BLE access, Android services, ambient I/O, filesystem access, real time, random sources, or transport pacing to target packages. The adapter SHALL record the host ABI version with every plan. It SHALL pass target-program state explicitly into each planning invocation, receive predicted next state explicitly, and SHALL NOT allow packages to depend on retained mutable Lua VM state between invocations.

#### Scenario: Trusted bundled package loaded
- **WHEN** the Editor initializes the Lua host adapter with the bundled GNU Readline target package
- **THEN** the adapter loads the package from trusted app-bundled assets and records the host ABI version

#### Scenario: Forbidden capabilities unavailable
- **WHEN** a target package attempts to access BLE, Android services, ambient I/O, filesystem, time, random sources, or transport pacing
- **THEN** the host adapter does not expose those capabilities and the attempt fails without side effects

#### Scenario: Lua state is explicit and transactional
- **WHEN** a target package computes a plan from an explicit assumed program state
- **THEN** the adapter returns the predicted next program state as an explicit output and does not retain it as hidden Lua VM state
- **AND** the Editor commits that next state only after complete plan execution

### Requirement: Editor structured failures
The `sleepwalker-core` library SHALL report structured Editor failures that classify semantic failures separately from infrastructure failures. Semantic failures include unsupported target behavior, unrepresentable content, and impossible transitions. Infrastructure failures include fixture malfunction, synchronization failure, transport failure, environment failure, and non-reproducible hardware failure. The library SHALL NOT present an infrastructure failure as a semantic reconciliation counterexample.

#### Scenario: Semantic failure structured
- **WHEN** a target behavior package reports an impossible transition
- **THEN** the Editor returns a structured semantic failure and emits no HID operations for that request

#### Scenario: Infrastructure failure classified separately
- **WHEN** an Editor plan execution fails due to a transport or fixture cause
- **THEN** the Editor returns a structured failure classified under the matching infrastructure class rather than as a semantic counterexample

### Requirement: Assumed and observed Editor state
The `sleepwalker-core` library SHALL treat post-USB target state as assumed unless it is independently observed by an authoritative fixture snapshot. After executing a plan, the Editor SHALL update its assumed state from the target package prediction and SHALL mark that state as assumed, not observed. Partial execution SHALL be terminal: the Editor SHALL mark the assumed state as Unknown and SHALL require an authoritative snapshot or explicit reset before accepting further `setText` calls against the same target.

#### Scenario: Predicted state marked assumed
- **WHEN** the Editor executes a reconciliation plan
- **THEN** it updates its assumed target state from the target package prediction and marks it assumed, not observed

#### Scenario: Partial execution marks state Unknown
- **WHEN** a reconciliation plan begins executing but does not complete
- **THEN** the Editor marks the assumed target state as Unknown and returns a structured failure without attempting generic recovery

#### Scenario: Unknown state rejects further calls
- **WHEN** the assumed target state is Unknown
- **THEN** the Editor rejects further `setText` calls against that target until an authoritative snapshot confirms the state or the Editor is explicitly reset to empty known state


### Requirement: Lua planning invocation and validation boundary
The Editor SHALL invoke the selected Lua package using host ABI version 1 with current rendered text, desired rendered text, and opaque committed state, then validate the complete returned constrained value graph and symbolic action sequence before assigning sequence identifiers, compiling, or executing any operation. Kotlin SHALL not derive a text diff, choose reconciliation policy, predict caret behavior, or interpret package state.

#### Scenario: Invalid plan fails before execution
- **WHEN** Lua returns a malformed ABI value or unsupported symbolic action
- **THEN** the Editor returns a structured pre-execution failure, emits no low-level keyboard operation, and retains the previously committed rendered text and opaque state

### Requirement: Opaque transactional Editor state
The Editor SHALL obtain opaque initial state from the package's deterministic initializer for the known initial rendered text. It SHALL commit returned rendered text and opaque next state only after every compiled operation completes. If failure occurs before execution begins, it SHALL retain the prior committed values; if execution may have partially occurred, it SHALL mark the Editor state Unknown and reject further `setText` calls until authoritative recovery or explicit reset.

#### Scenario: Complete execution commits opaque state
- **WHEN** a valid package plan completes all compiled operations
- **THEN** the Editor commits the returned desired rendered text and opaque next state without interpreting its contents

#### Scenario: Possible partial execution becomes Unknown
- **WHEN** execution begins and an operation fails or completion cannot be established
- **THEN** the Editor does not commit the returned opaque next state, marks its session Unknown, and rejects subsequent `setText` calls pending authoritative recovery or explicit reset

#### Scenario: No-action result cannot advance target state
- **WHEN** Lua returns no symbolic actions but either the desired rendered text differs from the committed rendered text or the opaque next state differs structurally from the committed opaque state
- **THEN** the Editor rejects the result before commit and retains the previously committed rendered text and opaque state

### Requirement: Kotlin symbolic action compilation and execution
Kotlin SHALL validate symbolic actions solely against the ABI action schema, compile valid actions to existing public low-level keyboard operations, and execute the compiled operations through the serialized executor. Kotlin SHALL not attach target-specific editor meaning to an action or require package state to have named editor fields.

#### Scenario: Symbolic action compiles through existing path
- **WHEN** a validated package plan contains supported symbolic keyboard actions
- **THEN** Kotlin compiles them to existing public low-level keyboard operations and executes them through the same keyboard API used by other library consumers

### Requirement: Clean experimental ABI cutover at host ABI version 1
The implementation SHALL replace the experimental seven-argument planning function, side-effecting `host.*` plan builder, Kotlin LCP/LCS reconciliation, host-derived caret prediction, `ReadlineProgramState`, and generic target-specific state validation with the version-1 pure data ABI. It SHALL provide no compatibility shim, alias, dual invocation path, or fallback to the experimental ABI while host ABI version remains 1.

#### Scenario: Experimental ABI is not invocable
- **WHEN** a bundled package or host component attempts to use the experimental planning signature or side-effecting host plan builder
- **THEN** the invocation is unavailable and no plan is produced through a compatibility path

### Requirement: Retained planning evidence
For every attempted or executed Editor request, retained plan evidence SHALL record package identity, host ABI version, current and desired rendered text, opaque input and output state values, symbolic actions, compiled low-level operations, keyboard-layout identity, text-cost metric identity, execution outcome, and whether state was committed or became Unknown. It SHALL not record host-selected LCP/LCS replacement fields as planning evidence.

#### Scenario: Retained evidence supports ABI replay
- **WHEN** an Editor request produces a retained plan
- **THEN** the evidence contains the opaque state round-trip, symbolic action sequence, compiled operations, package and ABI identity, layout and metric identities, and transaction outcome needed to replay or diagnose the request
