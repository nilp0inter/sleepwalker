## Context

The experimental editor ABI is a Kotlin-owned reconciliation pipeline. `Editor` derives one LCP/LCS replacement, passes seven planning arguments to a retained Lua VM, accepts side-effecting `host.*` calls into a Kotlin `PlanBuilder`, and validates a Readline-specific `ReadlineProgramState`. Its retained and HIL evidence consequently contains `lcp`, `oldMid`, `newMid`, a host-derived caret prediction, and low-level operations.

That arrangement fixes editor policy in the generic host: target packages cannot select a different diff strategy, model mode/selection/multiple-cursor state, compare candidate plans by layout cost, or express an editing model that is not Readline-shaped. It also makes plan creation impure: emitting an action changes a Kotlin-owned builder during Lua evaluation. This change replaces that experimental ABI before production release while retaining host ABI version **1**.

The boundaries remain deliberately separated:

| Layer | Owns after this change | Must not own |
|---|---|---|
| Public `Editor` API | Serialized complete-text requests, lifecycle, transactional commit, result/error classification | caret, selection, modes, target-state meaning, diff policy |
| Kotlin core ABI adapter | Package/ABI validation, isolated Lua invocation, structural validation, symbolic-action compilation, deterministic text-cost capability | LCP/LCS, target-specific state validation, editor-plan interpretation |
| Lua target package | Initial opaque state, reconciliation strategy, internal editor model, symbolic action sequence, predicted next opaque state | Android/BLE/transport/I/O/time/randomness |
| App executor | Ordered transport execution and complete-versus-partial delivery result | editor planning or Lua interpretation |
| HIL/retained evidence | Replayable input/output state and execution evidence | a shadow editor or inferred editor semantics |

The public operation stays `setText(completeDesiredText)`. Its requested value is a **rendered-text goal**: after successful execution, the target is expected to render exactly that string. It is not a request to set opaque target state. Opaque state is an internal package prediction which may represent caret, mode, selections, undo state, multiple cursors, or any other target-private model. Kotlin neither derives a rendered-text goal from state nor requires state to contain the text.

The existing low-level HID operation and transport protocol remain the execution product and wire format. Lua instead produces an inert, inspectable symbolic plan that Kotlin validates and compiles after Lua returns.

## Goals / Non-Goals

**Goals:**

- Define a pure, single-table Lua host ABI v1 with deterministic initialization and planning functions.
- Make each planner invocation a pure transformation from current rendered text, desired rendered text, and opaque input state to symbolic actions and opaque next state.
- Keep target-specific reconciliation, including diff choice and state-only caret/selection/modal transitions, entirely in Lua.
- Permit reusable, host-bundled pure Lua modules for diffing, state transformation, action construction, sequencing, candidate selection, and cost aggregation.
- Give Lua exactly one Kotlin-backed planning capability: a read-only deterministic layout-aware text-cost query.
- Validate symbolic output in Kotlin, compile it to the existing `LowLevelOp` representation, and transactionally commit opaque state only after complete execution.
- Preserve deterministic replay evidence, including package/ABI identity, state input/output, symbolic actions, compiled operations, keyboard-layout identity, text-cost metric identity, and active F24 policy.
- Preserve `Unknown` after possible partial execution and prevent further editing until reset/reconstruction; retain prior committed state on a pre-execution failure.
- Allow F24 in production plans by default, while making its reservation an explicit Readline-conformance/HIL execution policy.

**Non-Goals:**

- A compatibility adapter for the seven-argument planner, `host.*` plan builder, `ReadlineProgramState`, or the LCP/LCS `DiffResult` contract.
- Kotlin-owned differencing, caret simulation, mode/selection/multiple-cursor semantics, or validation of named fields inside target state.
- New public APIs for caret movement, selection, modes, cursor state, action submission, or state mutation.
- Dynamic, untrusted, filesystem, or network-loaded packages; this continues to cover bundled trusted assets only.
- Lua access to Android, BLE, transport pacing, ambient I/O, clocks, randomness, native loading, reflection, or arbitrary source loading.
- A generic recovery/rollback algorithm after partial execution, a shadow editor in Kotlin/HIL, or a change to the firmware/BLE wire protocol.
- A production-only F24 feature test. Production is allowed to compile F24 unless its configured policy reserves it; conformance explicitly tests the reserved policy and sends the physical barrier separately.
- Requiring opaque state to be serializable as a target document, exposing it to public callers, or using it as the public desired value.

## Decisions

### 1. ABI v1 is a pure, returned single table

A target package main chunk evaluates to exactly one ABI table. It has no registration callback and no host-owned mutable plan builder:

```lua
return {
  abi_version = 1,
  initialize = function(current_rendered_text) -> opaque_state end,
  plan = function(current_rendered_text, desired_rendered_text, opaque_state)
    -> { actions = { symbolic_action, ... }, next_state = opaque_state }
end
```

`initialize` is required, pure, and deterministic. The host calls it with the known initial rendered text before the editor accepts requests. It returns only the initial opaque state; it cannot emit actions. `plan` receives precisely the committed current rendered text, the caller's complete desired rendered text, and committed opaque state. It returns actions and next state together. A Lua error, malformed table, unsupported ABI version, or invalid value/action is a planning failure before execution.

The return table is the ABI boundary. Host-provided facilities are implementation-private Lua bindings made available only to host-bundled modules; a target ABI table never receives a plan builder, mutable host state, low-level operation object, or transport handle. The sole facility is the pure text-cost query described in Decision 5.

**Rationale:** returning data makes planning reproducible, inspectable, and safely rejectable before any action is compiled or sent. A single table is versionable as one contract and eliminates the former split between callback arguments, host side effects, and separately returned state.

**Alternatives considered:**

- Keep the seven-argument `plan` function and make only the result immutable: rejected because the argument list embeds the obsolete Kotlin diff/caret policy.
- Use an imperative `host.emit`/`host.text_plan` builder: rejected because action emission has host-visible side effects and prevents a pure candidate-planning model.
- Split initialization and planning across several global callbacks: rejected because it leaves an ambient, harder-to-version ABI and encourages VM-resident state.

### 2. Opaque state is a bounded generic value tree, not a host editor model

Kotlin accepts and round-trips only a canonical generic value tree:

```text
Value = null | boolean | signed-64-bit integer | UTF-8 string | array<Value> | object<string, Value>
```

The adapter enforces configured ABI-wide limits for total encoded bytes, total nodes, nesting depth, object members, array length, and individual string bytes. Object keys are UTF-8 strings with unique names. Values must be acyclic and contain no Lua function, thread, userdata, metatable, non-finite number, fractional number, unsupported integer, or sparse/non-array table shape. Kotlin copies/canonicalizes the validated tree at the boundary so a Lua table cannot be retained or mutated after return.

These checks are structural and resource-safety checks only. Kotlin must not recognize, require, normalize, compare, or infer meaning from keys such as `buffer`, `point`, `mode`, or `revision`. A package may place its predicted rendered text in state, but it is neither required nor trusted as the host's current rendered text; the latter is separately retained from successful public requests.

**Rationale:** a bounded tree supports future target models without making the generic host a schema registry or exposing unbounded data through retained artifacts. Separating rendered text from target state prevents a target model from silently redefining the public whole-text contract.

**Alternatives considered:**

- Preserve `ReadlineProgramState` and add optional fields: rejected because the generic path would remain Readline-specific.
- Accept arbitrary Lua tables: rejected because cycles, metatables, functions, and unbounded graphs cannot be transactionally retained or deterministically replayed.
- Encode state as an opaque byte/string blob: rejected because the adapter must still bound, validate, canonicalize, and artifact-record it safely; a generic tree is inspectable without interpreting meaning.

### 3. Every initialization and plan call uses a fresh constrained VM

The adapter validates package assets and ABI metadata at load time, then creates a new constrained Lua VM for every `initialize` and `plan` invocation. It installs the fixed allowlisted standard-library subset, host-bundled shared modules, the package-local module resolver, and evaluates the main chunk in that VM. The adapter calls the appropriate function once, validates the returned data, then closes/discards the VM regardless of success or failure.

No target global, closure, `package.loaded` entry, module-local variable, or Lua registry value survives an invocation. The committed opaque value tree is the only state that crosses invocations. This is stronger than merely asking target authors not to retain state and makes retry/replay behavior independent of invocation history.

The runtime continues to remove I/O, OS, debug, coroutine, dynamic loading, LuaJava/JVM bridge, time/random sources, and arbitrary source/file loaders. The implementation must retain only deterministic base/table/string and integer-safe math functionality needed by the package/shared-module contract.

**Rationale:** fresh VMs make the pure ABI enforceable and avoid state leakage from a failed plan into a later one.

**Alternatives considered:**

- One VM per package with a convention against globals: rejected because conventions do not prevent hidden mutable state or cache leakage.
- Serialize only the main function environment between calls: rejected because it is complex, incomplete for registry/module state, and less auditable than VM disposal.
- Run a VM per editor forever: rejected for the same isolation reason and because it would make outcomes depend on unrecorded history.

### 4. Shared Lua modules are pure namespaces with invocation-local `require` caching

The host bundles reusable modules under the reserved `sleepwalker.*` namespace, initially covering conceptual namespaces such as `sleepwalker.diff`, `sleepwalker.state`, `sleepwalker.actions`, `sleepwalker.plan`, and `sleepwalker.cost`. Package-local modules occupy package-declared names and cannot replace any reserved `sleepwalker.*` module. Module source is immutable bundled input selected by the target package manifest/registry; no ambient filesystem or network resolution exists.

A module exports a table of pure functions. Its functions receive values and return values; they do not emit actions, mutate host state, invoke transport, retain planner state, or access nondeterministic facilities. Action construction returns plain symbolic-action values. Candidate selection and composition return plain plans and values. State-only intermediate transitions are ordinary value transformations and may be sequenced before/after action-producing transformations without becoming public requests.

Within one fresh VM, `require(name)` has normal identity-preserving cache semantics: evaluating a module once stores its returned export table, and repeated `require(name)` calls return that same table for the duration of the invocation. The resolver rejects undeclared names, duplicate module identities, resolver escape paths, and cyclic imports with a deterministic load error. The cache is discarded with the VM, so no module cache crosses initialization or planning calls.

**Rationale:** shared pure namespaces prevent every target from reimplementing basic algorithms while preserving a target-owned composition layer. Invocation-local caching preserves Lua module ergonomics without violating invocation isolation.

**Alternatives considered:**

- Expose Kotlin helper methods directly to target code: rejected because it expands ABI surface and risks returning editor semantics to Kotlin.
- A persistent process-wide shared-module cache: rejected because module-local mutation would become cross-target/cross-request state.
- Copy module source into every package: rejected because fixes and behavior would drift between packages.

### 5. `text_cost` is the only Kotlin planning oracle and has a pinned identity

Lua reaches a single read-only capability through the shared cost namespace: `sleepwalker.cost.text_cost(text)`. It asks Kotlin to determine the deterministic cost of rendering the supplied string under the configured keyboard layout and text-cost metric. It returns a scalar non-negative integer cost when the text is representable, and a deterministic unrepresentable result otherwise. It emits no symbolic actions, does not allocate sequence IDs, does not mutate editor/package state, and does not observe transport or time.

The cost is defined by the same layout-aware text compilation rules that Kotlin later uses for a symbolic `text` action. The exact metric is named and versioned (for example, count of compiled low-level operations under the selected layout), and the active keyboard-layout identity and metric identity are captured before planning and retained with every plan. A planner can combine text costs with pure Lua arithmetic to choose candidates, but cannot ask Kotlin to choose a diff or action sequence.

Compilation executes against the same pinned layout/metric snapshot used for the query. A layout or metric identity mismatch between planning and compilation is a pre-execution failure rather than a silently different plan.

**Rationale:** packages need layout-specific information to make an informed choice, but allowing a cost query rather than action emission retains pure planning and a narrow host responsibility.

**Alternatives considered:**

- Expose `text_plan`: rejected because it gives Lua low-level operations and makes Kotlin action construction part of planning.
- Give Lua a complete keyboard-layout table: rejected because it duplicates Kotlin's authoritative rendering rules and enlarges package surface.
- Let Kotlin choose the minimum-cost Lua candidate: rejected because Kotlin would again own planner policy and need to understand candidate semantics.

### 6. Lua returns inert symbolic actions; Kotlin validates then compiles

`plan.actions` is an ordered array of closed-schema plain values. The initial action vocabulary is intentionally minimal:

```text
{ kind = "tap",  usage = "USB_KEY_*" }
{ kind = "down", usage = "USB_KEY_*" }
{ kind = "up",   usage = "USB_KEY_*" }
{ kind = "text", text = "..." }
```

A symbolic action is inert data. It has no Kotlin object identity, callback, emitted operation, sequence ID, transport status, pacing instruction, or target-editor interpretation. Kotlin validates array bounds and each exact schema, checks usage names against the canonical HID registry and the active reserved-usage policy, validates text using the pinned layout/compiler, then compiles actions in order to the existing low-level operations. `text` is expanded by the existing layout-aware text compiler; key actions use the existing HID primitives. Kotlin may enforce generic keyboard safety invariants (for example, valid key names and balanced/allowed modifier transitions) but must not infer cursor, selection, diff, or target meaning.

The package may construct and concatenate arbitrary action lists using shared pure modules. A caret-only operation is a package-internal planning step: it can update opaque state and return no action, or return a normal keyboard action as part of achieving a rendered-text request. There is no public caret-only request and no public symbolic-action submission method.

**Rationale:** symbolic actions preserve cross-target inspection and reuse of the stable compiler while isolating target semantics in Lua. Schema-first validation ensures nothing reaches the executor until all output is valid.

**Alternatives considered:**

- Return `LowLevelOp`-shaped Lua tables: rejected because it leaks host compiler details and treats Lua output as already executable.
- Reuse the mutable Kotlin `PlanBuilder`: rejected because it is side-effecting and cannot represent a data-only plan result.
- Make caret movement a public action/API: rejected because it violates the complete-rendered-text-only public contract.

### 7. Planning and state commit are transactional

For a non-`Unknown` editor, one serialized `setText(desiredRenderedText)` transition is:

1. Snapshot the committed current rendered text, opaque state, package identity, layout identity, metric identity, and F24 execution policy.
2. Invoke the package in a fresh VM with that current text, desired text, and a boundary copy of the committed opaque state.
3. Structurally validate returned next state and symbolic actions; compile actions under the captured layout/policy snapshot.
4. Retain the complete candidate evidence before execution.
5. Execute the compiled operations through the existing serialized executor.
6. On complete delivery, atomically commit both `desiredRenderedText` as the new current rendered text and the returned opaque next state, then report `Synced`.

Any load, Lua, state validation, action validation, cost/layout identity, or compilation failure occurs before execution. It leaves both committed current text and committed opaque state unchanged and is recoverable. A complete no-action plan is valid only when `desiredRenderedText` equals the committed current rendered text and the returned opaque state is structurally equal to the committed input state; any no-action result claiming a rendered-text or opaque-state transition is rejected before commit. State-only caret, selection, or mode transformations remain valid internal candidate steps, but a final target-state change requires corresponding symbolic input actions.

If execution may have begun but cannot be proven completely delivered, the editor enters terminal `Unknown`. It commits neither desired text nor returned state, retains failure evidence, and rejects later `setText` calls until an explicit reset/reconstruction path supplies a known rendered text and re-runs pure initialization. The host does not attempt rollback, state repair, or target read-back.

**Rationale:** state must describe the same assumed target epoch as the committed rendered text. Committing one without the other permits later plans to run against an invented model.

**Alternatives considered:**

- Commit next state immediately after planning: rejected because a delivery failure would advance the model without advancing the target.
- Commit state on partial execution as a best guess: rejected because the host cannot prove which operations reached the target.
- Keep the old `Failed` state for partial execution: rejected because `Failed` implies the target was unchanged; partial transport makes that false.

### 8. F24 is an explicit execution policy, not an ABI reservation

The symbolic action validator receives a configured reserved-usage policy. The production Editor policy does not reserve F24, so a valid symbolic F24 tap compiles through the existing canonical raw HID key path like any other registered usage. The Readline conformance/HIL policy explicitly reserves F24 and rejects a package plan containing it before execution. The harness injects its physical F24 synchronization barrier as a separate low-level operation only after the editor plan completes.

The active policy identity is retained in diagnostics and artifacts. Canonical F24 usage registration and its fixture barrier role remain unchanged. No dedicated production F24 feature test is required; correctness is exercised by normal symbolic-action validation/compilation, while conformance proves that its explicit policy prevents package use.

**Rationale:** F24 is a test synchronization concern, not an intrinsic editor-language feature. Policy makes that distinction visible and allows production packages to use the canonical key when permitted.

**Alternatives considered:**

- Unconditionally prohibit F24 in every editor plan: rejected because it unnecessarily reserves a production-capable HID usage.
- Add a special synchronization action or firmware opcode: rejected because it changes the existing wire protocol and mixes HIL control into the ABI.
- Allow conformance packages to emit F24: rejected because it could be mistaken for the harness barrier and invalidate ordering evidence.

### 9. Retained plans and HIL artifacts become ABI replay records

Retained-plan and verification schemas record the complete boundary, not host-selected diff details. Each attempt records at least:

- host ABI version; package ID, package version, and package source/module identity;
- current rendered text input and desired rendered-text goal;
- canonical opaque input state and returned opaque output state when produced;
- ordered symbolic actions exactly as validated;
- compiled ordered low-level operations;
- keyboard-layout identity, text-cost metric identity, and active F24/reserved-usage policy identity;
- planning/validation/compilation/execution classification, delivery status, and committed versus discarded transaction outcome.

Artifacts may include target-private state because they are verification records, not the public API. Their encoding must use the same canonical bounded value-tree representation used at the ABI boundary. They remove `lcp`, `lcs`, `oldMid`, `newMid`, host-derived predicted caret, and any `ReadlineProgramState` fields. HIL compares the requested rendered-text goal with the fixture's authoritative rendered output and uses opaque state only as package-provided replay evidence; it does not reconstruct an editor model from it.

**Rationale:** replay needs the inputs and outputs actually used by the pure ABI, plus the identities required to reproduce cost and compilation. LCP/LCS evidence would falsely imply a generic host reconciliation policy.

**Alternatives considered:**

- Retain compiled operations only: rejected because replay cannot audit Lua output, state transition, or policy-dependent validation.
- Retain only symbolic actions: rejected because a layout/compiler change could produce a different physical plan.
- Keep legacy diff fields as optional compatibility evidence: rejected because their continued presence preserves an obsolete contract and encourages consumers to depend on it.

### 10. Remove the experimental ABI cleanly while host ABI remains version 1

Implementation removes the generic editor's LCP/LCS differencer and all consumption of `oldMid`/`newMid`, the side-effecting `PlanBuilder` host functions, the seven-argument callback contract, Readline-specific generic state types/validation, host-derived caret prediction, and all retained/HIL schema fields that expose those concepts. The target package loader must load declared package modules and the shared module registry under the new resolver rules.

Every current caller is migrated to the single-table ABI asset and symbolic plan path in the same change. There are no version aliases, legacy callback adapters, fallback plans, dual output formats, or compatibility re-exports. `HOST_ABI_VERSION` stays `1` because version 1 is the accepted stable ABI definition before production; package manifests declaring any other ABI are rejected.

**Rationale:** a clean cutover prevents generic Kotlin policy and experimental artifacts from becoming permanent compatibility debt.

**Alternatives considered:**

- Support both ABI shapes under host ABI v1: rejected because v1 would become ambiguous and every execution path would carry dead experimental behavior.
- Bump host ABI to v2: rejected because the experimental interface never reached production and the accepted contract explicitly remains v1.
- Leave `DiffResult`/Readline state as unused helpers: rejected because they invite accidental reuse and contradict the target-neutral boundary.

## Risks / Trade-offs

| Risk / trade-off | Consequence | Mitigation |
|---|---|---|
| Fresh VM creation adds planning latency and allocation. | More work per `setText` than a retained VM. | Keep package/module sources bundled and validated, keep value-tree bounds strict, and measure focused editor planning latency during implementation; do not weaken isolation with a persistent VM cache. |
| Opaque state can be large or maliciously shaped despite trusted packages. | Memory pressure and oversized HIL artifacts. | Enforce tree depth/node/byte/member limits at every crossing and canonicalize once at the Kotlin boundary. |
| Pure cost query and later compilation could drift. | Lua may choose a candidate under a cost different from executed output. | Pin and record layout/metric identity for the full transaction; reject identity mismatch and share rendering rules between query and compiler. |
| Symbolic key transitions can be invalid. | Stuck modifiers or unsafe HID plans. | Validate the closed action schema and generic keyboard safety before executor entry; preserve existing compiler invariants. |
| A package can return state inconsistent with the desired rendered text. | Future plans may model a target that was not reached. | The host does not interpret state, but successful execution commits the public rendered-text goal only after delivery; target package tests/HIL verify its model. No hidden host reconciliation is introduced. |
| Partial delivery remains unknowable. | The target may be different from both old and desired text. | Enter terminal `Unknown`, discard predicted state, retain evidence, and require explicit external reinitialization. |
| F24 policy may be configured incorrectly. | Conformance barriers could be consumed by a package or production could be unnecessarily restricted. | Make policy explicit, record it, assert reservation in conformance, and keep production permissive by default. |
| Removing legacy artifacts breaks consumers that parse LCP/LCS fields. | Diagnostic/HIL tooling must migrate atomically. | Update all in-repository producers and consumers in this change; no dual schema is retained. |

## Migration Plan

1. Introduce the bounded opaque-value codec, canonical retained-artifact representation, package/module registry, fresh-VM invocation path, and pure text-cost capability while keeping them internal to the editor subsystem.
2. Replace the target asset contract with the returned v1 ABI table, add deterministic `initialize`, and move Readline-specific planning/model logic into the target/shared Lua modules. Load package-declared modules through the new resolver.
3. Replace Kotlin's LCP/LCS planning entrypoint and side-effecting Lua bridge with plan-result validation and symbolic-action compilation. Remove `Diff.kt`/`DiffResult` usage, `PlanBuilder` action emission, the seven-argument ABI, `ReadlineProgramState`, and generic host caret prediction rather than adapting them.
4. Update `Editor` lifecycle to retain current rendered text separately from opaque state, invoke pure initialization from known initial text, and transactionally commit text plus state only after complete execution. Preserve `Unknown` semantics for partial delivery.
5. Migrate app diagnostics, retained plans, ADB/UI projections, HIL JSONL/replay tooling, and conformance assertions to the new evidence schema. Delete LCP/LCS and Readline-specific fields from producers and consumers.
6. Thread explicit F24 policy through Editor construction: production permissive, Readline conformance reserved. Keep barrier injection external to package plans.
7. Remove every experimental compatibility path and verify focused ABI, state-transaction, symbolic compilation, artifact, and conformance behavior. No host ABI bump or legacy fallback is permitted.

## Open Questions

None for the resolved architecture. The implementation must use the fixed decisions above: host ABI version 1; one returned ABI table; pure initializer and planner; fresh invocation VM; bounded opaque tree; inert symbolic actions; one pure layout-aware `text_cost` query; transactional state; explicit F24 policy; and no LCP/LCS or compatibility path.
