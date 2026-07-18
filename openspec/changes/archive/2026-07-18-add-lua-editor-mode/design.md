## Context

Sleepwalker currently exposes a **stateless append-only text stream**: `TextPlanner` turns a string into an inspectable list of `LowLevelOp` keystrokes (modifier-aware, host-profile-keyed), `TapScriptCompiler` compiles those into batched `KEYBOARD_TAP_SCRIPT` frames, and the Android app streams them over a single BLE session to the ESP32-S3, which emits USB HID reports. The firmware is deliberately **layout-unaware** — it only decodes opcodes and emits HID. The observer ISO captures evdev events and, for text identity HIL, runs a raw-mode Linux console text sink that records the bytes the console input path delivered.

What the project cannot do today: **reconcile a previously rendered target field with a new complete text snapshot.** There is no notion of "the target currently holds text *X* with the caret at position *n*; make it hold text *Y*." The `type-text` ADB command and the UI text field both stream appended characters and explicitly do **not** mirror deletions or clearing (see `android-ble-companion` spec: "Text input is not a remote editor"). The Hypothesis identity property only proves that a *fresh* printable string renders byte-identically — it never edits an already-populated field.

This change introduces a stateful **Editor** that turns repeated `setText(completeDesiredText)` calls into target-specific HID edit sequences, modeled by **bundled trusted Lua target programs** that know how their target edits text. The first target is a pinned **GNU Readline Emacs-mode, single-line ASCII** environment, verified against a **real Readline fixture** built into the observer ISO.

**Stakeholders / boundaries (preserved unchanged):**

| Layer | Owns | Does NOT own |
|-------|------|--------------|
| `sleepwalker-core` (Kotlin) | Public API, text/diff/model, Lua host ABI, target package loading, plan inspection | BLE transport, Android services |
| `sleepwalker-app` (Android) | Single BLE session, ADB command intake, status correlation, diagnostics | Protocol encoding, keymap rendering, editor semantics |
| ESP32-S3 firmware | Frame decode, safety state machine, HID report emission | Text, layout, caret, target semantics |
| Observer ISO (NixOS) | evdev reporting, text sink, **target fixtures + control plane** | Editor logic, plan generation |
| `shared-hid-protocol` | Cross-language opcode/usage registries + parity fixtures | Dispatch semantics |

**Constraints carried forward from the living specs:**
- High-level text composes low-level primitives; every planned op is a public `LowLevelHid` product (`sleepwalker-core`).
- The app delegates command construction to the library; the app owns one BLE session (`android-ble-companion`).
- Firmware rejects any text-like payload it does not recognize; it never interprets layouts (`esp32-s3-hid-firmware`).
- Status notifications are correlated by sequence id; opaque context bytes are preserved (`shared-hid-protocol`).
- HIL artifacts separate evidence by layer and classify flaky/non-reproducible hardware failures distinctly (`agent-operated-hil`).

## Goals / Non-Goals

**Goals:**
- A stateful Editor whose **only public text mutation is `setText(completeDesiredText)`**; callers never manage caret, selection, or cursor mechanics.
- Internal tracking of the assumed target document, caret, target-program state, revision, and synchronization lifecycle — all hidden from public callers.
- Deterministic snapshot differencing producing **one contiguous replacement region** via longest-common-prefix/suffix, validated into an inspectable candidate plan of public `LowLevelOp`s.
- A **serialized executor boundary** injected into the Editor (not owned by core), with per-op status semantics and structured results.
- A **constrained, deterministic Lua 5.4 host ABI** for bundled trusted target packages; target programs produce inspectable plans and cannot access BLE, Android services, ambient I/O, time, or transport pacing.
- A **versioned package identity** model and the first target package for pinned GNU Readline Emacs-mode single-line ASCII, using Emacs control chords (C-a/C-e/C-b/C-f/C-d, backward-delete) composed from existing modifier usages.
- A **versioned target-fixture control contract** providing description, deterministic reset, physical-input barrier, authoritative snapshot, health, and shutdown.
- The **real GNU Readline fixture** in the observer environment exposing its authoritative line buffer and hidden editing state — **no shadow editor**.
- A **physical synchronization barrier** (symbolic usage F24, the sole new symbolic usage) consumed by the fixture from its own input stream, not inferred from evdev.
- A **Hypothesis snapshot-sequence property** that generates related complete-text sequences, calls `setText` repeatedly, and compares requested, predicted, and observed state after every synchronized call.
- Structured artifacts and a **failure taxonomy** that classifies semantic mismatches separately from planning, fixture, synchronization, transport, environment, and non-reproducible failures.

**Non-Goals (initial scope — explicitly excluded):**
- Dynamic or untrusted packages; sandboxing untrusted Lua.
- Unicode/grapheme clusters, normalization, or multi-byte rendering.
- Active selections (selection is tracked internally only as an editing-model detail, never exposed or relied upon by callers).
- Vi mode, history, completion, or submission (Enter) behaviors.
- Wordwise optimization, multiple disjoint edits per `setText`, or minimal-keystroke optimization.
- Concurrent execution or call coalescing; concurrently submitted calls are queued in arrival order and execute one at a time.
- Generic recovery after unknown target state (partial execution is terminal).
- Firmware opcode, frame-layout, dispatch, or symbolic-registry changes; the existing generic keyboard path already accepts raw F24 usage 0x73.
- Shadow/simulated editor inside the library or HIL.

## Decisions

### Decision 1: Editor public surface — single `setText`, hidden caret/selection, assumed target state

**Choice.** A new `io.sleepwalker.core.editor.Editor` class exposes exactly one public text-mutating method:

```
class Editor(
    val target: TargetPackage,        // bundled Lua target program
    val executor: EditorExecutor,     // injected serialized executor
    val hid: LowLevelHid,             // low-level primitive factory (existing)
) {
    fun setText(text: String): EditorResult
    fun state(): EditorState          // read-only public state name
}
```

`EditorResult` is a sealed type: `Synced(requestedDocument, plan)` or a structured `EditorFailure(requestedDocument, classification, plan?)`. `EditorState` is a public enum of state **names** only (`Uninitialized`, `Synced`, `Planning`, `Executing`, `Failed`, `Unknown`); neither result nor state exposes caret, selection, revision, assumed document, or target-program state.

**Rationale.**
- Callers today cannot express "make the field equal *Y*"; exposing caret mechanics would leak a target-specific concept (Readline point ≠ a raw console cursor) and violate the "callers do not manage cursor mechanics" requirement.
- A single `setText` mirrors how a source-of-truth text field is updated (a whole-value write), making the property test a clean snapshot-sequence comparison.
- Keeping caret/selection/internal document inside the Editor and out of the public type signature means future target packages with different editing models do not change the public API.

**Assumed-state contract.** After a successful `setText`, the Editor records an assumed target document and predicted hidden state (caret point, revision, and target-program state) internally. It does not claim the target was independently observed. The result carries the requested document and inspectable plan; HIL uses internal verification artifacts, not the public result type, to compare the prediction with a later authoritative fixture snapshot (Decision 11).

**Alternatives considered.**
- *Expose `moveCaret`/`insertAt`/`deleteRange`.* Rejected: these are target-specific, force callers to learn Readline semantics, and make snapshot-reconciliation properties impossible to state cleanly. Also explicitly out of scope ("active selections", "cursor mechanics").
- *Return the caret in `EditorResult`.* Rejected: it tempts callers to reason about position, and the predicted caret is only meaningful relative to a specific target's editing model. It is retained internally and in the HIL artifact for verification, never in the public type.

### Decision 2: Editor state machine — terminal Unknown on partial execution, no generic recovery

**Choice.** The Editor is a strict state machine:

```
Uninitialized ──setText──▶ Planning ──plan ok──▶ Executing ──all ops acked──▶ Synced
                                │                      │
                                │ plan/abi fail        │ partial ack / infra fault
                                ▼                      ▼
                              Failed                Unknown (terminal)
```

- **Planning**: the Lua target produces and validates a candidate plan (Decision 3). Failure here (e.g., unrepresentable glyph, ABI mismatch, diff rejected) → `Failed` with a structured reason; the Editor is **recoverable** (a subsequent `setText` may succeed because nothing was executed).
- **Executing**: the serialized executor runs the plan (Decision 4). If **every** batch receives a positive terminal status (`SENT_TO_USB`) the Editor advances to `Synced` and adopts the assumed state.
- **Unknown (terminal)**: if execution aborts partway — a batch receives `DISARMED`, `QUEUE_FULL`, `USB_NOT_MOUNTED`, `KILLED`, a BLE write times out, or status for a batch never arrives within the bounded window — the Editor **cannot know how much of the plan reached USB**. It transitions to `Unknown` and **rejects all further `setText` calls**. Recovery requires constructing a new `Editor` (optionally seeded with an independently observed document) or an explicit external re-sync; generic auto-recovery is a non-goal.

**Rationale.**
- The firmware drains a 16-deep `hid_bridge` queue at 12 ms/tap; a mid-plan fault leaves an unknown suffix of keystrokes either queued, in-flight, or dropped. Re-deriving the target state from first principles is impossible without observation, and guessing would silently corrupt the assumed document — the exact failure mode this change exists to eliminate.
- Making `Unknown` terminal and explicit forces the HIL harness and any caller to treat divergence as a hard stop, not a retry, which keeps artifacts honest.
- `Failed` (pre-execution) is kept distinct from `Unknown` (post-execution) because a pre-execution failure leaves the target untouched and the previous `Synced` assumption still valid, whereas `Unknown` invalidates it.

**Alternatives considered.**
- *Auto-resync by querying the target.* Rejected: there is no general read-back channel (the library must not assume a fixture), and partial execution may leave the target in a state the Editor cannot derive. Out of scope.
- *Best-effort rollback (re-type the whole document).* Rejected: without knowing what executed, rollback itself can corrupt further; also a form of generic recovery, which is a non-goal.
- *Treat partial execution as `Failed`.* Rejected: `Failed` implies "target unchanged," which is false after partial execution. Conflating them hides divergence.

### Decision 3: Snapshot differencing — deterministic LCP/LCS single contiguous replacement

**Choice.** The Editor computes the transition between the **assumed** document `current` and the `desired` text using longest-common-prefix and longest-common-suffix, producing **exactly one contiguous replacement region**:

```
lcp   = common prefix length of (current, desired)
lcs   = common suffix length, capped so lcp + lcs <= min(len current, len desired)
oldMid = current[lcp .. len(current) - lcs)      // chars to remove
newMid = desired[lcp .. len(desired) - lcs)      // chars to insert
```

The Lua target program is handed `(current, desired, lcp, oldMid, newMid, predictedPoint)` and emits an ordered `LowLevelOp` plan that:
1. Navigates to the replacement start using **Emacs chords** (Decision 8): `C-a` (home) then `C-f` forward `lcp` times, or `C-e` (end) then `C-b` backward — a single deterministic strategy is pinned (home-then-forward) so plans are reproducible; choosing the shorter route is a later optimization.
2. Deletes `oldMid.length` characters forward with `C-d`.
3. Types `newMid` by delegating to the existing **`TextPlanner`** (host ABI `host.text_plan(newMid)`), which yields modifier-aware printable `LowLevelOp`s for the selected host profile.

The plan is **inspectable** before execution — a `List<LowLevelOp>`, the same public type `TextPlanner` already returns — so the "retain inspectable low-level plans" requirement is satisfied by composition, not a new plan type.

**Candidate validation.** Before returning, the Editor validates:
- `newMid` (and thus `desired`) is fully representable by the host profile (the `TextPlanner` call surfaces `TextRenderingFailure`); on failure the Editor returns `Failed` with that structured reason and executes nothing.
- The predicted post-state equals `desired` with the predicted caret at `lcp + newMid.length` (or the target's natural post-insert point). If the target program's model disagrees with `desired`, the plan is rejected as `Failed(InconsistentPrediction)`.

**Rationale.**
- A single contiguous replacement is the smallest vertical slice that handles the common cases (pure append, pure prefix/suffix change, in-place middle edit) while remaining trivially deterministic and testable.
- Reusing `TextPlanner` for the printable portion means all existing keymap, modifier, and representability logic — and its tests — apply unchanged; the Editor only adds *editing* ops around it.
- Longest-common-prefix/suffix is O(n) and allocation-light (two index scans), avoiding a full diff/Levenshtein library.

**Alternatives considered.**
- *Full Myers/Levenshtein diff with multiple disjoint edits.* Rejected for initial scope: it requires navigation between disjoint regions (more Readline movement, more failure surface) and is explicitly listed as a non-goal ("multiple disjoint edits"). Reserved for later.
- *Always clear-and-retype the whole field.* Rejected: it discards the assumed-state model's value, emits far more HID traffic (slower, more drift exposure), and cannot exercise caret movement — defeating the purpose of modeling the target.
- *Operational transform / CRDT.* Rejected: massive overkill for a single-writer, single-target, serialized editor with no concurrency.

### Decision 4: Injected serialized executor boundary and status semantics

**Choice.** Execution is behind an injected interface, not owned by `sleepwalker-core`:

```
interface EditorExecutor {
    // Runs one compiled plan end-to-end; returns per-batch terminal statuses.
    fun execute(plan: List<LowLevelOp>): ExecutionOutcome
}
sealed class ExecutionOutcome {
    data class Delivered(val statuses: List<SessionStatus>) : ExecutionOutcome()
    data class Partial(val delivered: List<SessionStatus>, val reason: EditorFailure) : ExecutionOutcome()
}
```

- **Serialization**: the Editor owns a single ordered command lane; concurrent `setText` calls wait and enter that lane in arrival order. Calls are never coalesced, and plan computation or execution never interleaves.
- **Status semantics**: the executor correlates each batch's `seqId` (allocated via the existing `LowLevelHid`/`SleepwalkerCommands` sequence allocator) with `SessionStatus` notifications parsed by the existing `SessionStatusParser`. A batch is "delivered" only when its terminal status is `SENT_TO_USB`. Any `DISARMED`/`QUEUE_FULL`/`USB_NOT_MOUNTED`/`KILLED`/timeout yields `Partial` → the Editor goes `Unknown` (Decision 2).
- **App injection**: `sleepwalker-app` provides a `BleEditorExecutor` backed by the existing single `SleepwalkerBleService` session (same scan/connect/write/MTU path, same 390 ms inter-batch drain pacing). The library never touches Android BLE APIs.
- **Test/HIL integration**: a `RecordingEditorExecutor` captures plans without hardware. The HIL runner sends the reserved synchronization tap as a separate low-level test operation only after the Editor plan completes, then reads the fixture snapshot.

**Rationale.**
- Mirrors the existing boundary exactly: `TextPlanner` takes a `LowLevelHid` and produces bytes; the app owns transport. The Editor takes an `EditorExecutor` and produces results; the app still owns transport. No BLE logic moves into core.
- Per-batch `SessionStatus` correlation reuses `SessionStatusParser` and the existing sequence-id correlation invariant; no new status codes.
- `Delivered` vs `Partial` gives the Editor a crisp, observable basis for `Synced` vs `Unknown` without guessing.

**Alternatives considered.**
- *Editor calls `SleepwalkerBleService` directly.* Rejected: it would couple the Kotlin library to Android BLE (it is currently pure JVM) and duplicate the session-ownership invariant.
- *Fire-and-forget with no status correlation.* Rejected: the Editor could never distinguish `Synced` from `Unknown`, making the assumed-state model and the failure taxonomy meaningless.
- *Coalesce concurrent `setText` calls.* Rejected: explicitly a non-goal; serialization is simpler and preserves snapshot-sequence semantics for the property test.

### Decision 5: Constrained, deterministic Lua 5.4 host ABI

**Choice.** Target programs run inside a **bundled, trusted Lua 5.4 runtime** hosted by `sleepwalker-core`, constrained to a fixed host ABI. Runtime initialization opens only explicitly allowlisted Lua libraries through LuaJava: selected base, table, string, and integer-safe math operations. `io`, `os`, `debug`, coroutine scheduling, dynamic native loading, and the LuaJava `java` bridge are never installed. Base functions that load arbitrary chunks or access files (`load`, `dofile`, `loadfile`) are removed. `require` is replaced by a loader that resolves only package-declared modules from the same trusted app bundle. The host ABI is exposed as a single global table `host`:

| `host.*` | Signature | Purpose |
|----------|-----------|---------|
| `text_plan(text)` | `→ {op,...}` | Delegates printable text to the existing `TextPlanner` for the selected host profile; returns inspectable ops. |
| `key_tap(name)` / `key_down(name)` / `key_up(name)` | `→ op` | Wraps allowed `LowLevelHid` primitives by symbolic usage name; reserved synchronization usages such as F24 are rejected and are unavailable to target packages. |
| `ctrl(name)` | `→ {op,...}` | Convenience: `KEY_DOWN(LEFTCTRL); KEY_TAP(name); KEY_UP(LEFTCTRL)` (and releases only the ctrl bit, preserving the existing modifier-transition style). |
| `describe()` | `→ table` | Static target description (name, constraints, single-line/ASCII). |
| `abi_version()` | `→ int` | Constant the host checks against its supported ABI version. |

The ABI deliberately exposes no time, I/O, random source, coroutine scheduling, transport, or pacing control; pacing stays in the executor and app. This makes target programs deterministic: identical explicit program state and `(current, desired, assumedPoint)` inputs produce identical plans and predicted next state.

**Rationale.**
- Determinism is load-bearing: the property test compares the *predicted* post-state (computed by the Lua model) against the *observed* fixture state. If the model could read a clock or random source, predicted and observed could diverge for non-semantic reasons, poisoning the failure taxonomy.
- A small, explicit `host` table is auditable: every capability a target can exercise is listed. "Cannot access BLE/Android/I/O" becomes a reviewable property of the ABI surface, not a hope about sandboxing.
- Reusing `TextPlanner` via `host.text_plan` keeps one source of truth for printable rendering and its representability rules.

**Runtime packaging.** Use the maintained LuaJava Lua 5.4 binding and its Android native artifact from Maven Central rather than maintaining a project-local JNI bridge. The Gradle module packages only the required Android ABIs (`arm64-v8a` for the commissioned device and `x86_64` for emulator/CI). Initialization opens only the allowlisted Lua libraries and does not install LuaJava's general `java` module, so target packages cannot reflect into JVM or Android APIs.

**Alternatives considered.**
- *luaj (pure-Java Lua 5.1).* Rejected: the proposal pins Lua 5.4; luaj implements 5.1 semantics (different integer/`goto`/`goto`-scope behavior), and the determinism story is weaker (it reimplements the VM). Native 5.4 is the canonical implementation.
- *Interpret Lua in Kotlin from scratch.* Rejected: large, error-prone, and unnecessary.
- *Run Lua on the ESP32-S3.* Rejected: violates the layout-unaware firmware boundary and the BLE/core split; also infeasible given firmware RAM constraints.
- *Full standard library + trust the author.* Rejected: "cannot access ambient I/O/time/transport" is a hard requirement; an allowlist ABI is enforceable, a blacklist is not.

### Decision 6: Explicit Lua program state and predicted hidden state

**Choice.** Each target package defines a deterministic transition over an **explicit, host-owned program-state value** modeling the target's hidden editing state. The Lua VM itself is not the source of truth and target packages may not retain mutable state between invocations. For the Readline target the explicit state is at minimum:

```
state = {
  buffer = "",        -- predicted line buffer (string)
  point  = 0,         -- predicted Readline point (0-based index into buffer)
  revision = 0,       -- monotonic edit count
}
```

For each planning call, the host deep-copies the Editor's committed program state into an invocation-local Lua table. The target returns both its plan and `nextProgramState`. The Editor validates both outputs, executes the plan, and commits `nextProgramState` only after complete execution; a pre-execution failure discards it and a partial execution moves the Editor to `Unknown`. Replay therefore starts from recorded explicit state rather than from an opaque long-lived VM.

Every ABI primitive the program emits also mutates the invocation-local state so the prediction stays in lockstep with the plan: `ctrl("A")` sets `point = 0`; `ctrl("F")` increments `point` (clamped); `ctrl("D")` removes the character at `point`; backward-delete (`ctrl("H")`) removes the character before `point` and decrements it; `host.text_plan(s)` inserts `s` into `buffer` at `point` and advances `point` by `#s`. After producing a plan, the program asserts `state.buffer == desired`; if not, it signals `InconsistentPrediction` and the Editor rejects the plan (Decision 3).

The predicted hidden state (`point`, `revision`) is carried in the HIL artifact (Decision 13) so conformance can compare predicted and observed hidden state, but it is never part of the public `EditorResult` (Decision 1).

**Rationale.**
- The property must verify not just visible text but the target's *hidden* editing state (caret point). The model must predict it; the fixture must observe it; the comparison is the property.
- Driving the model from the *same* primitives the plan emits guarantees the prediction is a faithful simulation of the emitted keystrokes — there is no separate "simulation" that could drift from the actual ops.
- Keeping it in Lua (not Kotlin) means target-specific editing rules live with the target, not in the core library.

**Alternatives considered.**
- *Track state in Kotlin.* Rejected: couples core to Readline semantics and breaks the "target packages own their behavior" model.
- *Don't model point; only verify text.* Rejected: the proposal explicitly requires verifying hidden editing state; a caret that lands wrong is a real Readline bug even if text matches.
- *Derive point post-hoc from the plan.* Rejected: fragile and duplicates the editing model; the model should be authoritative.

### Decision 7: Package identity and versioning

**Choice.** Each target package is a versioned bundle (Lua script(s) + manifest) loaded by a `TargetPackageLoader` in `sleepwalker-core`. The manifest is a small declarative table:

```
{
  id            = "readline-emacs-ascii",
  version       = "1.0.0",          -- semver of the package itself
  host_abi      = 1,                -- required host ABI version
  target        = "gnu-readline",
  target_pin    = "8.2",            -- pinned target implementation
  mode          = "emacs",
  charset       = "ascii-printable",
  line_model    = "single-line",
  describe      = { name = "GNU Readline 8.2 Emacs ASCII single-line", ... },
}
```

- **`id`** is the stable lookup key (`Editor(target = TargetPackages.READLINE_EMACS_ASCII)`).
- **`host_abi`** is checked against `host.abi_version()` at load time; a mismatch yields a structured `AbiMismatch` failure and the package is refused.
- **`target_pin`/`mode`/`charset`/`line_model`** are opaque-to-core metadata the fixture control plane (Decision 11) and HIL harness use to select and verify the matching physical fixture; core never interprets them.
- Packages are **app-bundled resources** (read-only assets), never loaded from the network or filesystem at runtime. The loader exposes a fixed registry; dynamic/untrusted packages are a non-goal.

**Rationale.**
- Versioning both the package and the ABI lets the host refuse incompatible packages structurally rather than failing mid-plan.
- Pinning the target implementation (`gnu-readline 8.2`, `emacs`) makes the editing model and the fixture reproducible: a future Readline version with different Emacs bindings is a *new package version* with its own pin, not a silent behavior change.
- Keeping packages as bundled resources preserves the hermetic-build invariant (no runtime network/filesystem fetch), consistent with the existing bundled keymap database boundary.

**Alternatives considered.**
- *Unversioned single script.* Rejected: no way to evolve the ABI or the target pin safely; breaks reproducibility.
- *Filesystem/network-loaded packages.* Rejected: explicitly a non-goal ("trusted app-bundled packages").
- *Version the package only, not the ABI.* Rejected: a host update could silently change `host.*` semantics for an existing package; an explicit `host_abi` check is the guard.

### Decision 8: Readline Emacs-mode target — navigation via existing control chords

**Choice.** The `readline-emacs-ascii` package drives GNU Readline in Emacs mode using only **existing** symbolic usages composed as control chords, plus printable text via `host.text_plan`:

| Readline command | Binding | Plan ops (reuse existing usages) |
|------------------|---------|----------------------------------|
| `beginning-of-line` | `C-a` | `KEY_DOWN(USB_KEY_LEFTCTRL)`, `KEY_TAP(USB_KEY_A)`, `KEY_UP(USB_KEY_LEFTCTRL)` |
| `end-of-line` | `C-e` | ctrl around `USB_KEY_E` |
| `forward-char` | `C-f` | ctrl around `USB_KEY_F` |
| `backward-char` | `C-b` | ctrl around `USB_KEY_B` |
| `delete-char` | `C-d` | ctrl around `USB_KEY_D` |
| `backward-delete-char` | `C-h` (Rubout) | ctrl around `USB_KEY_H` |

`USB_KEY_LEFTCTRL` (0xE0), and `USB_KEY_A/E/F/B/D/H` are already in the Kotlin and Python `Usages` registries; firmware accepts their raw usage bytes generically. The `ctrl()` ABI helper emits the same modifier-down/tap/modifier-up shape `TextPlanner` already uses, so `TapScriptCompiler` folds the modifier into each tap record unchanged.

**Why `C-h` for backward-delete.** GNU Readline binds `backward-delete-char` to both the Backspace key (USB usage 0x2A, not currently registered) and `\C-h`. Using `C-h` keeps **F24 as the only new symbolic usage** (Decision 9) and avoids registering a Backspace usage that the Kotlin/Python registries, parity fixtures, and observer diagnostics would need to understand. If a future target genuinely requires a dedicated Backspace usage, it can be added then as a separate, motivated change.

**Rationale.**
- Emacs chords are Readline's native, stable, well-documented editing vocabulary; they are far less likely to shift between Readline builds than raw keycodes.
- Reusing existing usages means zero firmware changes and zero new navigation parity fixtures; the only protocol-surface addition is the Kotlin/Python F24 constant and its parity fixture.
- Single-line ASCII scope means no multi-line, no Unicode, no completion/history — exactly the bindings above suffice for LCP/LCS single-region edits.

**Alternatives considered.**
- *Arrow-key navigation (Left/Right/Home/End usages).* Rejected: would require registering four or more new symbolic usages across Kotlin and Python plus observer coverage, against the "F24 only" constraint; it is also less canonical for Readline Emacs mode.
- *Dedicated Backspace usage (0x2A).* Rejected: adds a second new symbolic usage for no functional gain over `C-h` in this scope.
- *Clear-line (`C-u`) + full retype.* Rejected: discards the contiguous-replacement model and emits more traffic; reserved as a fallback only if LCP/LCS ever proves insufficient (not needed in scope).

### Decision 9: Symbolic usage addition — F24 only (the synchronization barrier)

**Choice.** Exactly one new symbolic usage is added to the Kotlin `Usages` and Python `usages.py` registries. Firmware remains symbolically unaware and needs no source change because its existing `KEY_TAP` path accepts any raw USB keyboard usage:

```
USB_KEY_F24 : HidUsage("USB_KEY_F24", usbUsage = 0x73, evdevCode = 194)
```

USB HID keyboard usage 0x73 is F24 (function row key 24); Linux evdev `KEY_F24` is 194. It is chosen because:
- The fixture can bind the pinned Linux VT terminal sequence for F24 to a dedicated Readline handler, producing no text or editing side effect; fixture health verifies the exact sequence and zero buffer mutation.
- It is a single physical keypress, so it traverses the same ordered HID path as the preceding edits before the target-side handler acknowledges it (Decision 10).

The parity fixtures gain a `valid_usb_key_f24.bin` golden frame so `sleepwalker-protocol-check` enforces Kotlin/Python agreement and validates the raw wire value. Firmware remains unchanged: a `KEY_TAP` payload containing usage 0x73 is already accepted and emitted by the generic keyboard path.

**Alternatives considered.**
- *A new dedicated "sync" opcode.* Rejected: requires firmware frame/dispatch changes (explicitly out of scope; "firmware frame layout and opcodes remain unchanged") and a new status path. A key tap reuses existing dispatch entirely.
- *ESC or another existing control key.* Rejected: Readline binds ESC (meta prefix) and most controls; using one would corrupt editing state.
- *A fixed sleep/duration barrier.* Rejected: the proposal explicitly replaces fixed sleeps with a symbolic synchronization key; sleeps cannot prove consumption.
- *Multiple new usages (e.g., F23/F24 pair).* Rejected: one sentinel suffices; minimizing the protocol surface is a stated constraint.

### Decision 10: Physical barrier consumed by the fixture — not inferred from evdev

**Choice.** The F24 tap is test-only synchronization evidence and is never part of an Editor reconciliation plan or exposed through the Lua host ABI. After each Editor `setText` plan completes, the HIL runner sends one F24 tap through the existing low-level key-injection path. The synchronization contract is:

1. The HIL runner waits for the Editor plan's transport completion, then sends F24 through the **same** physical path as the edits: app → BLE → ESP32-S3 → USB HID → observer host VT → **Readline fixture process**.
2. The **fixture** consumes F24 from its own active Linux virtual-terminal input stream, not from the evdev observer. On receiving the bound F24 terminal sequence, the fixture knows by stream ordering that every preceding keystroke has been delivered to the Readline process, and it then snapshots Readline's authoritative state (Decision 11).
3. The non-grabbing evdev observer (Decision 12) may record the F24 event as diagnostics only; it is never used as the synchronization signal, because evdev delivery does not prove Readline consumption.

**Rationale (and the Main steering constraint).** The barrier must prove the target consumed the input without contaminating production Editor plans. An evdev reader runs in parallel to the target process and cannot observe whether Readline processed the bytes; under load the Readline process may lag behind evdev. Making the fixture consume the separately emitted F24 terminal sequence closes that gap while keeping test instrumentation out of the target program and public Editor behavior.

**Alternatives considered.**
- *Synchronize on evdev F24 sighting.* Rejected: evdev delivery to the observer is not delivery to Readline; a race would let the snapshot read a stale buffer. Explicitly disallowed by the steering constraint.
- *Fixed sleep after edits.* Rejected: non-deterministic, slow, and cannot prove consumption.
- *Poll the Readline buffer until stable.* Rejected: "stable" is ambiguous under repeated identical edits and cannot distinguish "done" from "stuck."

### Decision 11: Versioned target-fixture control contract and authoritative Readline snapshot (no shadow editor)

**Choice.** A versioned `TargetFixture` control contract is exposed over SSH from the observer ISO, implemented per target. Operations:

| Operation | Semantics |
|-----------|-----------|
| `describe()` | Returns the fixture's target/mode/charset/line-model identity and control-ABI version (matched against the package manifest at HIL setup). |
| `reset()` | Deterministically resets the target to a known empty/neutral state (e.g., restarts the Readline process, clears the line buffer, homes the point). |
| `await_barrier()` | Blocks until the fixture consumes an F24 from its input stream (Decision 10). |
| `snapshot()` | Returns the **authoritative** target state: the real Readline `rl_line_buffer` string, `rl_point`, and selected hidden state, read directly from the live Readline process — **not** a reimplementation. |
| `health()` | Liveness/readiness check (process alive on the active VT, console keymap and terminal identity pinned, F24 sequence binding verified). |
| `shutdown()` | Clean teardown. |

**Readline fixture implementation.** The fixture is a small C program, built into the observer ISO by the flake (alongside the existing `text-sink` and `observer-helper`), that links against **libreadline** (GNU Readline, pinned to the package's `target_pin`, e.g. 8.2) and:
- Runs as the foreground application on the active Linux virtual terminal in Readline Emacs single-line mode, so ESP32-S3 USB HID input follows the real console input path directly into the fixture. The unix control socket is separate from standard input.
- Feeds bytes to `rl_callback_read_char()` in a loop.
- Binds the Linux VT's F24 terminal key sequence with `rl_bind_keyseq` to a handler that captures `rl_line_buffer`, `rl_point`, and modeled hidden state, then publishes the snapshot over the control channel.
- Because it calls into the **actual** libreadline internals for the snapshot, it is the **authoritative** source — the HIL never trusts a library-side simulation of Readline.

**No shadow editor.** The library's Lua program (Decision 6) is a *predictor*; the fixture is the *oracle*. The HIL compares predictor vs. oracle. There is no second implementation of Readline inside the library or HIL that could agree-with-itself while diverging from reality.

**Alternatives considered.**
- *Use the existing raw-mode `text-sink` as the oracle.* Rejected: the text sink captures only the *bytes the console delivered* (raw input echo); it does not expose Readline's hidden state (point, kill ring) and cannot model editing commands. It is a byte-identity oracle, not an editing-state oracle.
- *Shadow-editor inside the library, verified against itself.* Rejected: explicitly prohibited ("without implementing a shadow editor"); a self-consistent simulation proves nothing about real Readline.
- *Drive a real terminal emulator and OCR/screen-scrape.* Rejected: fragile, slow, non-deterministic, and cannot read hidden state like `rl_point`.
- *evdev as oracle.* Rejected: evdev reports key events, not the resulting buffer/point; also disallowed as the sync source (Decision 10).

### Decision 12: Non-grabbing evdev observer is diagnostics-only in conformance runs

**Choice.** In the Editor conformance scenario, the HID observer is started **without** `--grab` so that injected HID events are delivered to the Linux VT / Readline fixture. Any evdev JSONL collected is classified strictly as **diagnostics** (e.g., confirming the F24 and editing chords were emitted on the wire, and cross-correlating sequence ids). It is never used to determine synchronization or to read back target state.

This is the same non-grabbing mode the text-identity scenario already uses (`hid-observer-nixos-iso`: "Non-exclusive evdev diagnostics for text identity"). Existing grab-based smokes (keyboard/mouse/composite) continue to grab exclusively, unchanged.

**Rationale.**
- An exclusive grab on the keyboard evdev node would **steal** the input before it reaches the Readline fixture, breaking the conformance property entirely. The steering constraint makes this explicit: normal HID input must reach the VT/Readline fixture.
- Keeping evdev as optional diagnostics preserves cross-layer sequence correlation (a key invariant) without making it load-bearing for correctness.

**Alternatives considered.**
- *Exclusive grab + pipe events to the fixture.* Rejected: convoluted, fragile, and re-introduces the "evdev as sync source" anti-pattern; also the observer helper is not in the delivery path to the console.
- *No evdev at all in conformance.* Rejected: diagnostics are valuable for classifying transport vs. semantic failures (Decision 14); keeping them as optional, non-authoritative evidence is strictly better.

### Decision 13: Hypothesis snapshot-sequence property model

**Choice.** A new HIL scenario generates **related complete-text snapshot sequences** and asserts, after every synchronized `setText`:

```
predicted_state  ==  fixture.snapshot()   AND
desired_text     ==  fixture.snapshot().buffer
```

The generation strategy models a user editing one field over time, not random independent strings:
- Start from an empty (or seeded) document.
- Each step applies one of a bounded set of **single-region edits** derived from LCP/LCS-friendly transforms: append a printable run; insert in the middle; replace a middle run; delete a prefix/suffix/middle run; truncate. All draws come from the ASCII-printable seed alphabet (excluding ESC and controls without stable identity, matching the existing identity alphabet).
- The sequence length and per-step size are bounded by the quick/deep profiles (reusing the existing `quick`/`deep` Hypothesis profile pattern and session-scoped setup).

For each step, the HIL:
1. Sends `setText(desired)` through the ADB → app → Editor → BLE → firmware → USB path and waits for its transport result.
2. Sends a separate low-level F24 tap through the HIL key-injection path.
3. Waits for the fixture `await_barrier()` confirmation that F24 was consumed.
4. Reads `fixture.snapshot()`.
5. Compares the Editor's predicted state and `desired` against the snapshot.
6. On mismatch, shrinks the generated sequence and records a full artifact (Decision 14).

**Session-scoped execution** reuses the existing pattern: bench validate, ESP reset, captures, fixture `reset()` + `health()`, BLE connect + arm once; per-example isolation is the fixture `reset()` + per-example plan/snapshot, not a full bench rebuild.

**Alternatives considered.**
- *Independent random strings per step (no relation).* Rejected: it never exercises *editing* an existing field — it would be the existing identity property in disguise. The value is in transitions between related snapshots.
- *Replay a fixed script.* Rejected: not a property; cannot find edge cases; defeats the purpose of Hypothesis.
- *Reset the whole bench per example.* Rejected: the existing session-scoped pattern already amortizes setup; per-example isolation is the fixture reset, not the bench.

### Decision 14: Artifact, replay, and failure-classification model

**Choice.** The conformance scenario writes structured artifacts under `artifacts/run_editor_conformance_<ts>/`, extending the existing artifact schema with an Editor-specific section:

```
summary.json (excerpt):
  scenario: "editor_conformance"
  status: "pass" | "fail"
  profile: "quick" | "deep"
  target_package: { id, version, host_abi, target_pin, mode, charset }
  fixture: { identity, control_abi_version, health }
  hypothesis: { settings, examples, seed, duration }
  steps: [
    { seq, desired, lcp, old_mid, new_mid,
      plan_ops, predicted: { buffer, point, revision },
      observed: { buffer, point },
      barrier_consumed: true|false,
      match: true|false, classification: "<class>" }
  ]
  classification_totals: { semantic, planning, fixture, sync, transport, environment, non_reproducible }
```

Plus per-run JSONL: `android_logcat.jsonl`, `esp_uart.jsonl`, `hid_observer.jsonl` (non-grabbing diagnostics), and the fixture's snapshot log.

**Failure taxonomy (mutually exclusive classes):**
- **`semantic`** — plan delivered and barrier consumed, but `observed != predicted` or `observed != desired`. This is a real target/editing-model bug (the property's counterexample).
- **`planning`** — the Editor rejected the plan pre-execution (unrepresentable glyph, inconsistent prediction, ABI mismatch). No HID emitted.
- **`fixture`** — the fixture itself misbehaved (health fail, reset fail, snapshot read fail, identity/control-ABI mismatch).
- **`sync`** — the barrier was not consumed within the bounded window (F24 never reached the fixture), but transport layers look healthy.
- **`transport`** — BLE/firmware fault (DISARMED/QUEUE_FULL/USB_NOT_MOUNTED/KILLED, BLE timeout, missing status). Maps to Editor `Unknown`.
- **`environment`** — console keymap wrong, device not enumerated, SSH/observer unreachable.
- **`non_reproducible`** — a step failed once but a same-input replay succeeds (flaky hardware/timing), mirroring the existing identity-scenario classification.

**Replay.** Each failing step records `desired`, the computed `lcp/old_mid/new_mid`, the full `plan_ops`, the predicted state, and the Hypothesis shrunk-seed/representation, so the exact example can be replayed standalone (single `setText` + barrier + snapshot) without re-running the whole property.

**Rationale.**
- Separating `semantic` from infrastructure classes is an explicit requirement: a Readline-model bug must not be hidden behind a BLE hiccup, and vice-versa.
- Reusing the existing artifact layout and classification discipline (per-layer evidence, flaky-vs-deterministic split) keeps the HIL consistent and the verification skill applicable.

**Alternatives considered.**
- *Single pass/fail with no classification.* Rejected: the proposal explicitly requires separating semantic mismatches from infrastructure failures.
- *Re-run the whole property to reproduce.* Rejected: slow and non-hermetic; single-step replay is far cheaper and deterministic.

## Risks / Trade-offs

### Risk: LCP/LCS single-region diff cannot express some real edits
**Risk.** If a caller's `desired` differs from `current` in two disjoint regions, LCP/LCS collapses them into one large replacement spanning the gap, emitting more keystrokes than necessary (and more `C-d`/typing).
**Mitigation.** This is accepted for initial scope; the result is still *correct* (the field ends at `desired`), just not minimal. Multiple disjoint edits and wordwise optimization are explicit non-goals. The deterministic strategy is documented so behavior is predictable.
**Trade-off.** Correctness and simplicity over keystroke minimality.

### Risk: Lua model and real Readline diverge (the core property risk)
**Risk.** The whole value of the Editor rests on the Lua predictor matching real Readline. A binding mismatch, a Readline version difference, or a missed hidden-state update would produce `semantic` failures.
**Mitigation.** (1) The fixture uses the *real* libreadline pinned to the package's `target_pin`, so the oracle is authoritative. (2) The property test is exactly the divergence detector. (3) The model is driven by the same primitives it emits, reducing drift. (4) Hidden state (`rl_point`) is verified, not just text.
**Trade-off.** We accept that the first target package will likely surface real divergences during HIL bring-up; that is the intended outcome, not a defect.

### Risk: LuaJava adds native Android packaging and JNI risk to `sleepwalker-core`
**Risk.** LuaJava packages native Lua per Android ABI and executes through JNI, increasing APK contents and introducing a native crash surface.
**Mitigation.** Use the maintained LuaJava release rather than a custom JNI bridge, package only the required ABIs, omit the general Java bridge module, and constrain the exposed Lua environment to the versioned host ABI.
**Trade-off.** Native packaging complexity in exchange for canonical Lua 5.4 semantics and substantially less project-owned native integration code.

### Risk: F24 not consumed / fixture misses the barrier
**Risk.** If the Linux VT emits an unexpected F24 terminal sequence, Readline consumes part of it before the fixture binding matches, or another binding overrides it, `await_barrier()` times out or the target buffer is contaminated.
**Mitigation.** Pin the Linux console keymap and Readline version, bind the observed F24 sequence explicitly with `rl_bind_keyseq`, and make `health()` verify both barrier delivery and zero buffer mutation before each conformance run. Bound failures are classified as synchronization or fixture failures, not semantic mismatches.

### Risk: Assumed-state drift if a caller ignores an `Unknown` result
**Risk.** A caller that ignores a terminal `Unknown` and constructs assumptions from stale state will be wrong.
**Mitigation.** `Unknown` is terminal: the Editor **rejects** further `setText`. The public `state()` returns `Unknown`. The HIL never proceeds past it.

### Trade-off: No generic recovery after partial execution
**Trade-off.** Terminal `Unknown` means any transport hiccup mid-plan ends the Editor session. Accepted because guessing the residual state is worse than failing loud, and generic recovery is an explicit non-goal.

### Trade-off: Serialized, non-coalesced calls
**Trade-off.** Serializing `setText` reduces throughput versus coalescing. Accepted because concurrent execution and coalescing are non-goals, while ordered, individually verified snapshot calls make replay and state ownership deterministic.

## Migration Plan

The change is additive; no existing public API is removed or renamed. Migration is staged so each step is independently verifiable with the no-hardware checks (`sleepwalker-protocol-check`, `sleepwalker-fw-build`, `sleepwalker-apk-build`) before any HIL run.

1. **Protocol surface (firmware unchanged).** Add `USB_KEY_F24` to Kotlin `Usages` and Python `usages.py`; add the `valid_usb_key_f24.bin` golden fixture; verify `sleepwalker-protocol-check` covers symbolic parity and raw usage 0x73. Confirm the existing generic firmware `KEY_TAP` path accepts the frame without adding a C symbolic registry or dispatch branch.

2. **Lua runtime + host ABI in `sleepwalker-core`.** Add the pinned LuaJava Lua 5.4 binding and Android native artifact to Gradle/Nix dependency inputs; implement the constrained `host` ABI and `TargetPackageLoader` with manifest/version checks. Unit-test the ABI with a `RecordingEditorExecutor` (no hardware): given known `(current, desired)` inputs, assert the plan and predicted state.

3. **Editor core.** Add `Editor`, `EditorExecutor`, `EditorResult`/`EditorFailure`, and the LCP/LCS differ + candidate validation. Unit-test the state machine transitions, including the terminal `Unknown` path and pre-execution `Failed` path, with the recording executor.

4. **First target package.** Author `readline-emacs-ascii` (Lua) with the Emacs-chord primitives (Decision 8) and the explicit state model (Decision 6). Verify determinism: identical inputs → identical plans/predicted state, via the unit harness.

5. **App integration.** Add `BleEditorExecutor` backed by the existing single `SleepwalkerBleService` session; add an ADB `set-text` command path (encoded payload, mirroring the existing lossless encoded `type-text` path) and structured diagnostics. The existing `type-text` stream path and UI remain unchanged (non-editor mode).

6. **Observer fixture.** Build the GNU Readline fixture (C, linked against pinned libreadline) into the observer ISO via the flake; implement the `TargetFixture` control contract (`describe`/`reset`/`await_barrier`/`snapshot`/`health`/`shutdown`) over SSH.

7. **HIL conformance scenario.** Add the Hypothesis snapshot-sequence runner (quick/deep, session-scoped, non-grabbing evdev diagnostics, F24 barrier, fixture snapshot comparison, full artifact + classification schema). Extend the verification skill/artifact docs.

8. **No cutover migration of existing callers.** The append-only `type-text`/UI path is untouched; the Editor is an additional capability. Existing smokes (keyboard/mouse/composite/text-identity) continue to pass unchanged.

## Open Questions

All questions below are **resolved by repository convention or an explicit scope decision** except where marked `[DEFERRED]` (genuinely out of initial scope and safe to defer).

1. **Which Readline version to pin?** Resolved: GNU Readline **8.2** (current stable, widely packaged in nixpkgs, deterministic Emacs bindings). The package `target_pin = "8.2"` and the fixture links the same version. A future Readline release with binding changes becomes a new package version.

2. **arm64-v8a only, or also armeabi-v7a/x86_64 for the Lua native lib?** Resolved: ship **arm64-v8a** (matches the Android reference device class) plus **x86_64** (emulator/CI). armeabi-v7a can be added later if a physical 32-bit target appears; not needed now.

3. **Should the Editor expose a "seed from observed state" entry point for recovery?** Resolved for scope: **no** public seeding API. Recovery after `Unknown` is out of scope (non-goal). Internally, a new `Editor` can be constructed with an initial assumed document; this is an internal/test affordance, not a public recovery surface. `[DEFERRED]` a public `resync(observed)` if fixture-backed recovery is later required.

4. **Backward navigation strategy: always `C-a`+`C-f`, or pick shorter of home/edge?** Resolved: pin **`C-a` then forward `C-f`** for determinism in the vertical slice. Choosing the shorter route is a later optimization (non-goal). `[DEFERRED]` minimal-movement planning.

5. **Fixture control-channel transport: file vs. unix socket vs. SSH stdout?** Resolved: a **unix socket** on the observer host, read over the existing SSH control connection used by the other observer helpers. Consistent with `sleepwalker-hid-observe`/`text-sink-ctl` SSH patterns; no new network surface.

6. **Should the non-grabbing evdev observer run by default in conformance, or be opt-in?** Resolved: run by **default** as diagnostics (it cannot break delivery when non-grabbing, and its sequence correlation is valuable for classification), but mark its output `diagnostics_only` in the artifact so no consumer treats it as authoritative.

7. **Multiple target packages now, or just Readline?** Resolved: **only `readline-emacs-ascii`** in this change. The package/ABI/fixture contracts are generic so a second target (e.g., a different single-line editor) can be added later without core changes. `[DEFERRED]`.

8. **Does the F24 barrier need to be stripped from the Readline buffer?** Resolved: **no** — the fixture binds the Linux VT's F24 terminal sequence with `rl_bind_keyseq` to a test handler, so it advances the fixture barrier without inserting bytes into `rl_line_buffer`. The fixture health check verifies this binding before conformance begins.

9. **Editor result for an identical `setText(current)` (no-op)?** Resolved: the LCP/LCS diff yields an empty `oldMid`/`newMid`, so the Editor reconciliation plan is empty and the result is `Synced` with unchanged assumed state. During HIL conformance the runner still sends its separate F24 tap, giving the fixture a synchronization point without contaminating the Editor plan.

10. **Where does the Lua package source live?** Resolved: bundled as a read-only app resource under `android/sleepwalker-core/src/main/assets/targets/readline-emacs-ascii/` (manifest + Lua sources), loaded by `TargetPackageLoader`. Matches the bundled-resource convention used by `JsonKeymapDatabase`.
