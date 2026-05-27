---
status: Accepted
date: 2026-05-27
accepted_date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0115: DisplayEntity transition system + per-type hot-update API

## Status

Accepted (Wave 5transitions landed 2026-05-27)

## Context

Wave 5h (ADR-0079) shipped the `Hologram` aggregate + `IHologramGateway`
port with field-for-field Bukkit Display parity (12 Display base + 8 Text
+ 2 Item + 1 Block = 23 fields, plus the visibility scope).
[Subsequent waves audit](.claude/plans/wave-5g-core-hardening-giggly-sunset.md)
revealed **two structural gaps** before the Wave 6b Paper sweep:

- **No per-type updater surface** — the gateway exposed only `updateKind`
  (full payload swap). Per-field updates required reading the live
  hologram, rebuilding the entire kind variant, and writing it back via
  `updateKind` — semantically wasteful (the Paper adapter would need to
  re-render every field of the Display entity on every update) and
  awkward to compose for tween animations (callers couldn't drive
  individual fields per tick).
- **No transition system** — Wave 5h's `Easing` enum (31 curves) is the
  shared animation vocabulary; Wave 5cinematic (ADR-0110) and Wave
  5gui-depth (ADR-0114) hook it into camera / slot animations, but
  **holograms had no integration**. Per-field tween for `Transform`
  (translation + rotation + scale), `Brightness`, `GlowColor`, etc.
  required ad-hoc per-tick re-implementation at every call site, and
  there was no domain vocabulary for "composite parallel tween" or
  "sequential timeline with loop" — both of which Hypixel-tier UX needs.

A 2026-05-27 user review identified **Option 1: Hologram-specific
transition system** as the cleanest design — a domain transition value
that knows it animates a hologram, not a generic animation framework
that's never used for anything else. The 3 hologram kind variants
(Item / Block / Text) take distinct per-type parameters; a generic
framework would either constrain itself to the LCM or expose per-kind
casts at every call. A hologram-specific system gives the sealed
hierarchy room to grow per-kind without bleeding into other use cases.

**Design constraints**:
- Bukkit family imports forbidden in domain (Konsist `core domain stays
  pure (ADR-0115)` + ADR-0002).
- `org.joml.*` allowed for the rotation interpolator (`feedback_joml_in_domain`
  memory + Wave 5h-fix precedent — JOML is pure-math).
- `IDomainEventBus` / `IBroadcaster` / `IScheduler` / `IHologramGateway`
  forbidden in `core.domain.hologram.transition.*` (those are
  application-layer concerns; the controller in `core.application.service.hologram.*`
  holds them, the transition value does not).
- Wave 5h `Easing` enum, `HologramKind`, `HologramTransform`,
  `HologramColor`, `HologramBrightness`, `BillboardMode`,
  `TextAlignment`, `ItemDisplayMode` MUST be reused — no parallel
  vocabulary.
- Phase 1 PoC closing posture (CLAUDE.md §Pre-release posture) — no
  fallback shim, no `@Deprecated`, no dual decoder. Add the 16 new
  gateway methods as a forward-only break.
- Existing `IHologramGateway` methods (10) MUST remain source-compatible
  — only **additions** to the surface.
- No DB migration. Transitions are ephemeral (no persistence). main
  HEAD's highest migration is V43; Wave 5transitions adds none.
- Sub-agent harness constraints (orchestrator-solo Phase B, manual
  rtm-sync, manual review).

## Decision

Wave 5transitions adds **(A) 16 per-type updater methods on
`IHologramGateway`**, **(B) a `core.domain.hologram.transition.*`
sealed hierarchy** for declarative tween + sequence + composite,
**(C) `HologramTimeline` + `LoopMode`** for sequenced loops, **(D)
`HologramRotationInterpolator` + `HologramColorInterpolator`** as
pure-math helpers, **(E) `IHologramTransitionController`** as the
outbound port + scheduled-execution InMemory impl, **(F)
`InMemoryHologramTransitionController`** driven by a single per-tick
scheduler task, **(G) three new domain events**, **(H) +1 Konsist
rule** pinning the domain purity invariant, and **(I) test fixtures +
tests** covering every variant.

### A. Per-type updater expansion (`IHologramGateway` +16 methods, +1 read accessor)

The gateway port grew by 16 write methods (5 Display base + 8 Text + 2
Item + 1 Block) plus 1 new read accessor `snapshot(handle): Hologram?`
needed by the transition controller for kind-validation + tween
from-state capture:

- **Display base** (apply to every kind variant): `updateShadowRadius`,
  `updateShadowStrength`, `updateViewRange`, `updateBillboard`,
  `updateDisplayDimensions`.
- **Text-specific**: `updateTextContent`, `updateTextLineWidth`,
  `updateTextBackgroundColor`, `updateTextOpacity`,
  `updateTextAlignment`, `updateTextSeeThrough`, `updateTextShadowed`,
  `updateTextDefaultBackground`.
- **Item-specific**: `updateItemId`, `updateItemDisplayMode`.
- **Block-specific**: `updateBlockData`.

Per-type methods validate the live kind via the new `snapshot(handle)`
accessor and return `HologramError.TypeMismatch(expected, actual)` —
a new sealed variant carrying `KClass<out HologramKind>` for both sides
so callers can pattern-match. Existing 10 gateway methods (`spawn /
despawn / updateTransform / updateKind / teleport / updateBrightness /
updateInterpolation / updateGlowColor / updateVisibility / viewers /
all`) are unchanged.

### B. Transition domain (`core.domain.hologram.transition.*`, 5 files)

`HologramTransition` sealed interface with **19 variants**:

- **Smooth (interpolable, `easing: Easing` required)** — `Transform`,
  `Brightness`, `GlowColor`, `ShadowRadius`, `ShadowStrength`,
  `ViewRange`, `TextOpacity`, `TextBackgroundColor`, `TextLineWidth`
  (9 variants). Each holds `targetValue` + `durationTicks: Int` +
  `easing: Easing`. `durationTicks` validated `>= 0` at construction.
- **Categorical (instant set with delay)** — `TextContent`,
  `TextAlignmentSet`, `TextSeeThrough`, `TextShadowed`,
  `TextDefaultBackground`, `ItemSwap`, `ItemDisplayModeSet`,
  `BlockData`, `Billboard` (9 variants). Each holds `targetValue` +
  `delayTicks: Int = 0`. `delayTicks` validated `>= 0`.
- **Structural** — `Composite(transitions: List<HologramTransition>)`
  for parallel execution. Empty list rejected at construction.

### C. Timeline + LoopMode

`HologramTimeline(transitions: List<HologramTransition>, loopMode:
LoopMode)`. Sequential execution — next transition starts after
previous completes. Empty `transitions` rejected.

`LoopMode` sealed with 3 variants:
- `PlayOnce` — default; complete after the final transition.
- `Loop(times: Int?)` — re-run the whole transition list. `null` =
  infinite. `times = N` = N total iterations.
- `PingPong(times: Int?)` — alternate forward + reverse traversals.
  `times = N` = N total cycles (each cycle = 1 forward + 1 backward).

`times` rejects `<= 0` (use `PlayOnce` for one-shot semantics).

### D. Math interpolators (domain — JOML direct import OK)

- `HologramRotationInterpolator.slerp(from, to, factor)` — wraps JOML
  `Quaternionf.slerp(other, factor)` non-destructively. Pure-math, no
  Minecraft coupling.
- `HologramColorInterpolator.lerp(from, to, factor)` — RGBA channel-wise
  byte tween with `roundToInt().coerceIn(-128, 127)` and `null`
  start/end treated as transparent black for fade-in/out semantics.

### E. Outbound port (`IHologramTransitionController`, 5 methods)

```kotlin
fun play(handle, transition): Result<HologramTransitionHandle, HologramTransitionError>
fun play(handle, timeline): Result<HologramTransitionHandle, HologramTransitionError>
fun cancel(transitionHandle): Result<Unit, HologramTransitionError>
fun cancelAll(handle): Result<Unit, HologramTransitionError>
fun active(handle): Collection<HologramTransitionHandle>
```

`HologramTransitionError` sealed: `UnknownHologram(handle) /
TypeMismatch(expected, actual) / TransitionAlreadyCancelled(transitionHandle)
/ SchedulerUnavailable`.

Validation at play time: per-type transitions check `transition.kind`
against the live `hologram.kind` and fail synchronously if mismatched.
Cancellation is idempotent — unknown handles return `Success` with no
event publish.

### F. InMemory impl (`InMemoryHologramTransitionController`)

Single `IScheduler.runRepeatingTicks(initialDelay = 1, period = 1)`
task drives every active transition. Per tick:
- **Smooth**: `factor = easing.apply((tick - startTick) / durationTicks)`
  → lerp from-state → target → write through corresponding per-type
  gateway updater.
- **Categorical**: wait `delayTicks` then fire single set.
- **Composite**: open every child at the same start tick; complete
  parent when every child completes.
- **Timeline**: open one child at a time; advance per `LoopMode` + ping-
  pong traversal direction at each child completion.

`shutdown()` cancels the tick task + publishes
`HologramTransitionCancelled(reasonKind = "scheduler_shutdown")` for
every in-flight transition. Idempotent.

### G. Domain events (3 files in `core.domain.event.*`)

- `HologramTransitionStarted(handle, transitionHandle, atTick)`
- `HologramTransitionCompleted(handle, transitionHandle, atTick)`
- `HologramTransitionCancelled(handle, transitionHandle, reasonKind, atTick)`
  — `reasonKind` snake_case (`manual / hologram_despawned /
  timeline_completed / scheduler_shutdown`), matching `CinematicEnded` /
  `SlotAnimationCancelled` precedent.

### H. Konsist +1 rule (49 → 50 total)

`hologram transition domain stays pure (ADR-0115)` — forbids in
`core.domain.hologram.transition.*` the Bukkit family +
kotlinx.serialization + Exposed / Postgres / SQL / Lettuce + Koin +
`IDomainEventBus` / `IBroadcaster` / `IScheduler` / `IHologramGateway`.
JOML deliberately allowed (Wave 5h-fix precedent + `feedback_joml_in_domain`
memory).

### I. Plugin wiring + service registration

`AsteraCorePlugin.onEnable` adds:
- `services.register<IHologramGateway>(InMemoryHologramGateway(), priority = 0)`
  — Wave 5h shipped the impl but never wired it. Wave 6b lands
  `PaperHologramGateway` over `org.bukkit.entity.Display` at higher
  priority.
- `services.register<IHologramTransitionController>(InMemoryHologramTransitionController(...), priority = 0)`
  — constructor-injected `IHologramGateway` + `IScheduler` +
  `IDomainEventBus`. Reference retained as a private field so
  `onDisable` can call `shutdown()`.

`SERVICE_NAMES` companion list gets 2 new entries.

### Test fixtures + tests

- `FakeHologramTransitionController` (record + manual-advance API, no
  scheduler dependency — for use case tests).
- 7 test classes / 71 tests covering: 19 sealed transition variants
  (exhaustive `when`), `HologramTimeline` + `LoopMode` validation,
  `HologramRotationInterpolator` JOML slerp identity, `HologramColorInterpolator`
  RGBA channel-wise + clamp, `InMemoryHologramTransitionController`
  play / cancel / type-mismatch / composite parallel / timeline
  sequential / loop modes, all 31 `Easing` curves hookup,
  `IHologramGateway` 16 new updaters + TypeMismatch error matrix,
  3 event variant publish.

## Consequences

### Positive

- **Per-field tween becomes idiomatic** — `controller.play(handle,
  Transition.ShadowRadius(2f, 20, Easing.EASE_OUT_BACK))` replaces the
  former "read live state → rebuild full kind → updateKind" workaround.
- **Animation curve vocabulary unified** — 31 `Easing` curves shared
  across camera / GUI animation / hologram transition without parallel
  enums.
- **Composite + Timeline + LoopMode** unlock Hypixel-tier UX
  declaratively — fade-in + slide-up + rotate combos in one `Composite`,
  ambient pulse loops via `Timeline(loopMode = Loop(null))`.
- **Type-safety at play time** — kind-mismatch transitions fail
  synchronously with `TypeMismatch(expected, actual)` carrying KClass
  for log / GUI rendering. The type system can't enforce this without
  per-kind-typed handles (and the value-class handle stays simple), so
  validation lives in the controller.
- **Sealed exhaustiveness across 19 variants** — adding a 20th variant
  in a future wave forces controller + tests to be updated in lock-step
  (build fails on the missing `when` branch).
- **Foundation for Wave 6b's Paper adapter** — a future
  `PaperHologramGateway` may register a higher-priority controller that
  drives `Display.interpolationDuration` directly (vanilla
  interpolation, server-side render), or stay with the per-tick approach
  if the vanilla one proves jitter-prone. Either way, the port + domain
  vocabulary is fixed.
- **No DB migration** — transitions are ephemeral; cross-server
  reproducibility is a Wave 7+ concern. Phase 1 PoC closing posture
  preserved (forward-only commits, no schema bridge).
- **Konsist 50 rules** — domain purity for transition vocabulary
  pinned at the CI layer.

### Negative / Trade-offs

- **`IHologramGateway` surface jumped from 11 to 28 methods.** Every
  impl (production InMemory + test Fake + future Paper adapter) must
  implement all 28. Wave 6b's Paper adapter is the most expensive — it
  needs 17 new Bukkit `Display` setter dispatches. Mitigation: 16 of
  those follow a regular pattern (`live.put(handle, copy(field = x))`
  in memory; `setX(...)` on the bukkit entity in the adapter), so the
  cost is mechanical.
- **`snapshot(handle)` read accessor** was added to satisfy the
  transition controller's from-state needs, but it sets a precedent for
  callers reading hologram state through the gateway. Wave 5h
  deliberately shipped the gateway as write-only. Mitigation: KDoc
  explicitly notes the snapshot is for **internal controller use** and
  callers should treat it as "best-effort live state" — visibility-scope
  resolution still goes through `viewers(handle)`.
- **`InMemoryHologramTransitionController` uses one repeating task at
  1-tick period** — for a server with 50+ active transitions this is
  still O(N) work per tick, but each step is O(1) per transition.
  Folia / multi-region servers may want per-region tick drivers in
  Wave 6, but that's a controller-impl swap, not a port change.
- **`Composite` does not nest** — a `Composite(List<Composite(...)>)`
  is rejected at runtime by the controller (returns false from the
  apply step). Nested composites would force the controller to recurse
  into arbitrary depth, complicating cancellation cascade. Sequential
  composition uses `HologramTimeline` instead.
- **Easing curve count** referenced as "32" in the handoff plan but
  the live enum has 31 (LINEAR + 30 named ease variants from
  easings.net). Test asserts `>= 31` so adding a future 32nd curve
  doesn't immediately break.
- **PingPong semantics** — `times = N` counts cycles (1 cycle = 1
  forward + 1 backward), not traversals. `times = 1` = 2 traversals,
  not 1. Tested + documented but easy to mis-read; KDoc carries an
  explicit example.

### Mitigations

- Wave 6b Paper adapter can register a higher-priority `PaperHologramGateway`
  that uses Bukkit's native `Display.interpolationDuration /
  interpolationDelay` for smooth transitions, bypassing the per-tick
  step entirely. The transition vocabulary in domain stays the same
  — only the dispatch path swaps.
- `IHologramGateway.snapshot` is technically required only by the
  controller; a future ADR could split it into a separate
  `IHologramSnapshotReader` SPI if the read precedent proves
  problematic. For Wave 5transitions we keep it on the main port for
  ergonomic simplicity (one gateway = one place to look).
- `Composite` nesting could be supported in a future wave by wrapping
  the apply step in a depth-aware dispatcher; for Phase 1 we accept
  the single-level constraint as a forcing function toward
  `HologramTimeline` (which IS hierarchical via sequential composition
  of `Composite` parents).

## Alternatives Considered

- **Alt A — Generic animation framework, hologram-agnostic**: a unified
  `IAnimationController<T>` that tweens any Display field type.
  Rejected because the 3 hologram kind variants take per-kind parameters
  that don't compose into a single generic surface (an `ItemDisplay`'s
  `displayMode` has nothing to do with a `TextDisplay`'s `lineWidth`);
  exposing both via casts at every call would defeat the type-safety
  win. A hologram-specific system lets each kind grow its own per-type
  variants.
- **Alt B — Bake transitions into `Hologram` aggregate as
  `transitions: List<HologramTransition>`**: makes the data class
  mutable in semantics even if structurally immutable. Pure state ↔
  pure animation separation is cleaner — `Hologram` holds the *current*
  state; transitions are out-of-band scheduled work.
- **Alt C — Per-transition scheduler task (N tasks for N
  transitions)**: simpler to reason about (each transition self-
  schedules its completion callback) but pays N × Bukkit-scheduler
  bookkeeping overhead. The single-task driver iterates O(N) entries
  per tick but with O(1) work per entry, which scales better and
  centralises the type-mismatch / cancellation logic.
- **Alt D — Lambda completion callbacks** instead of domain events:
  `controller.play(handle, transition) { /* on complete */ }`. Lambdas
  defeat the `IDomainEventBus` pattern (silly-knuth §5 — events route
  through the bus for cross-plugin subscription); the bus also lets a
  Phase 4 analytics layer count transition completions without code
  changes to the controller. Rejected.
- **Alt E — Per-kind handles (`TextHologramHandle / ItemHologramHandle
  / BlockHologramHandle`)** so the type system enforces per-type
  updater compatibility at compile time: forces every callback to be
  parameterised, breaks the existing single-handle API across the rest
  of the gateway. Rejected — runtime `TypeMismatch` is acceptable for
  Phase 1 (the cost of getting it wrong is a clear sealed error type,
  not a silent failure).
- **Alt F — Reuse `IGuiAnimationController` for hologram tweens**:
  scope mismatch — GUI animation operates on `SlotIndex` value classes
  inside a `GuiId`-keyed container; hologram tween operates on
  `HologramHandle` against world-space Display entities. The port
  signatures would diverge so much that the unified controller becomes
  a switch statement on "is this a GUI or a hologram?". Cleaner to
  keep them parallel.
- **Alt G — Forbid JOML in the rotation interpolator and write a
  pure-Kotlin slerp**: re-implements Hamilton-product quaternion math
  that JOML already provides correctly, increases the test surface
  (we'd need our own correctness tests for the slerp). The
  `feedback_joml_in_domain` memory + Wave 5h-fix `HologramTransform`
  precedent make JOML-in-domain the established pattern; using it for
  the interpolator is symmetric. Rejected.

## References

- 関連 ADR:
  - ADR-0002 (Bukkit-free domain — the foundation rule this ADR
    inherits)
  - ADR-0022 (Multi-plugin layout — Core owns the hologram vocabulary)
  - ADR-0030 (Forward-only commits — no fallback shim policy)
  - ADR-0079 (Wave 5h hologram foundation; this ADR extends its
    `Hologram` aggregate + `IHologramGateway` port)
  - ADR-0083 / ADR-0090 (per-viewer state + packet interception —
    Wave 6b's `PaperHologramGateway` will share that pipeline for
    `viewers()` resolution)
  - ADR-0110 (Wave 5cinematic; `Easing` reuse policy + in-memory
    controller pattern precedent)
  - ADR-0113 (Wave 5animation; per-tick step driver precedent for
    Player + Entity + Block animations)
  - ADR-0114 (Wave 5gui-depth; `IGuiAnimationController` design pattern
    + `Easing` reuse + sealed `when` enforcement)
- 関連 docs:
  - [[architecture/principles]]
  - [[RTM-PARITY-TRACKER]] (Display entity transition row)
  - `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 7
  - `.claude/rules/coding-posture.md` §Pre-release breaking changes
  - `.claude/memory/feedback_joml_in_domain.md` (JOML-in-domain
    allowance)
