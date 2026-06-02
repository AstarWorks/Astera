---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0165: Wave 5c-N1 — Six IGameMode façades over the legacy Wave 5c modes

## Status

Accepted. Phase 1 PoC follow-up (Wave 5c-N1). Closes the long-deferred
"6 Kotlin GameMode impls" tail of ADR-0032 by lifting BattleLoyal,
BreakCore, CarryTNT, CarryMiniTNT, CaptureWool, and StealJewelry
into the [IGameMode] framework that ADR-0106 established and
NormaGameMode demonstrated. Unblocks Wave 7a-N1 (six
`content/game-modes/*.yaml` files that reference these façades via
`impl_class:`).

## Context

Wave 5c (ADR-0032) shipped the **legacy** game-mode framework: a
sealed `match.domain.mode.GameMode` config hierarchy (six data classes,
one per shipped mode) and per-mode `*Listener` classes wired in
`AsteraMatchPlugin.onEnable` as global event subscribers. Victory
evaluation flows through `VictoryConditionEvaluator` (ADR-0027).
**That layer is unchanged by this wave.**

Wave 5gamemode (ADR-0106) then added the `IGameMode` *behavioural*
framework (`IGameModeFrameworkRegistry`, `GameModeContents` YAML
loader, lifecycle hooks publishing `GameModeEvent.*` for HUD / queue
/ audit consumers, `DefaultGameModeScheduler`). At the time only
NormaGameMode (Wave 5c control-objective façade) and the Wave-5annihilation
mode (ADR-0108, state-owning Annihilation impl) shipped as `IGameMode`
implementations. **The six original Wave 5c modes had no `IGameMode`
representation**, so they were invisible to the framework registry —
HUD couldn't render their title via `MessageKey`, queue UI couldn't
list them as available modes, `content/game-modes/<id>.yaml` files
couldn't reference any `impl_class:` for them.

Two waves are blocked by this gap:

- **Wave 7a-N1**: the six new `content/game-modes/<id>.yaml` files
  (battle-loyal.yaml, break-core.yaml, …) need an `impl_class` FQN to
  reflectively instantiate via `GameModeContents.registerInto`. No
  Kotlin class → no YAML possible.
- **Phase 3 surface tools** (HUD scoreboard, `/astera-match start`
  tab-completion, queue UI) that read the framework registry to
  enumerate "available modes" see only `norma` and `annihilation`,
  not the six PoC modes that are actually playable today.

NormaGameMode is the reference shape: thin façade, four lifecycle
hooks (`onMatchStarted` / `onPhaseChanged` / `onTick` / `onMatchEnded`),
`onTick` is a no-op because the actual game logic runs in the global
listeners + `VictoryConditionEvaluator`. Six near-identical façades
slot in cleanly without disturbing the legacy stack.

## Decision

### §A — Six thin façade `IGameMode` impls

Add six classes under `plugin/match/.../application/gamemode/` (sibling
to `NormaGameMode.kt`):

- `BattleLoyalGameMode.kt`
- `BreakCoreGameMode.kt`
- `CarryTntGameMode.kt`
- `CarryMiniTntGameMode.kt`
- `CaptureWoolGameMode.kt`
- `StealJewelryGameMode.kt`

Each is structurally **identical to `NormaGameMode`** modulo three
constants (`ID`, `TITLE_KEY`, KDoc / log comment naming the wrapped
legacy listener). ~75 LOC per file including KDoc.

Constructor signature: `@JvmOverloads constructor(eventBus: IDomainEventBus? = null)`.

- No-arg branch supports reflective load by `GameModeContents.registerInto`
  (Phase 1 PoC scheme). When Wave 7a-N1 ships the YAML stubs, the
  reflective load picks up an `eventBus = null` instance that no-ops on
  hooks.
- Bus-injected branch is used in `AsteraMatchPlugin.onEnable` to replace
  the reflective instance with one that publishes framework events
  (same pattern as the existing Norma + Annihilation wiring).

### §B — `IGameMode.id()` reuses the legacy `GameModeId` value

Each façade returns the same kebab-case `GameModeId` value that the
legacy sealed config class declares:

| Façade | `GameModeId(value)` | Legacy declaration |
|---|---|---|
| `BattleLoyalGameMode` | `"battle-loyal"` | `BattleLoyal.kt:18` |
| `BreakCoreGameMode` | `"break-core"` | `BreakCore.kt:25` / `AsteraMatchPlugin.kt:427` |
| `CarryTntGameMode` | `"carry-tnt"` | `GameModeId.kt:15` (canonical doc) |
| `CarryMiniTntGameMode` | `"carry-mini-tnt"` | YAML convention |
| `CaptureWoolGameMode` | `"capture-wool"` | YAML convention |
| `StealJewelryGameMode` | `"steal-jewelry"` | YAML convention |

This ID equality is **intentional**: the legacy config and the new
façade describe the same mode from two angles (config knobs vs.
behaviour). Wave 7a-N1's YAML files will use one `id:` value to
address both. Future Phase 3 work that collapses the legacy hierarchy
into per-`IGameMode` config types (deferred by ADR-0032 §"Open
questions") can do so without an id-renaming step.

### §C — `IGameMode.titleKey()` lives at `astera.gamemode.<id>.title`

Mirrors the NormaGameMode convention (`astera.gamemode.norma.title`),
distinct from the legacy `astera.match.mode.<id>.name` keys that
describe per-mode config strings for the Wave 5c sealed `GameMode`
carriers. Two coexisting i18n trees because:

- the **legacy** strings (`astera.match.mode.battle-loyal.name`,
  `astera.match.mode.battle-loyal.description`) describe what the mode
  *does* and are read by per-mode-config UIs;
- the **framework** strings (`astera.gamemode.battle-loyal.title`)
  describe the mode's *name* as advertised by the registry and are
  read by mode-agnostic surfaces (HUD, queue, scoreboard).

`content/languages/en.yaml` and `ja.yaml` both gain six new entries
under their `astera.gamemode:` tree, one per façade. Konsist's i18n
parity test guarantees the en/ja key sets stay aligned.

### §D — `onTick` is a no-op for all six façades

Every façade's `onTick` is empty (with a comment naming the listener
that does the real work). Rationale documented in
`BattleLoyal.kt:5-13` and Wave 5c's listener-driven architecture
(BreakCoreListener subscribes to `BlockBroken`; CarryTNTListener to
`EntityPickedUpItem` + `EntityEnteredRegion`; etc.). The façade exists
to advertise the mode and publish framework lifecycle events, not to
own scoring or win detection. Phase 3 may collapse a façade into a
state-owning impl when a mode gains custom phases — until then the
two-layer split (legacy listener + new façade) is the cleanest non-
destructive integration.

### §E — Programmatic registration in `AsteraMatchPlugin.onEnable`

After the existing Annihilation wiring block (around line 506), add a
single `listOf(...).forEach { gameModeFrameworkRegistry.register(it) }`
loop registering all six bus-wired façades. Same pattern as the
existing Norma fallback + Annihilation replacement blocks.

Once Wave 7a-N1 ships YAML stubs, `GameModeContents.registerInto`
picks up reflective `eventBus = null` instances first; this loop
overwrites them with bus-wired ones (`InMemoryGameModeFrameworkRegistry.register`
is overwrite-on-collision per ADR-0106). No code change needed at
the YAML-shipping wave.

### §F — Parameterized test (`Wave5cGameModesFacadeTest`)

One test file under `plugin/match/src/test/.../application/gamemode/`
covering all six façades via JUnit 5 `@ParameterizedTest` +
`@MethodSource`. Mirrors `NormaGameModeEquivalenceTest` (seven
assertion families) without six near-identical test files:

1. `id()` / `titleKey()` return the declared values.
2. `defaultPhases()` is the canonical five-phase list.
3. `tickPeriodTicks() == 20L`.
4. `onTick` publishes nothing.
5. `onMatchStarted` / `onMatchEnded` publish exactly one event each.
6. `onPhaseChanged` publishes one event per real transition, zero
   on `oldPhase == newPhase`.
7. `eventBus = null` makes every hook a clean no-op.

Plus one cross-cutting test verifying the six IDs are distinct (a
guard against a future copy-paste bug renaming two façades to the
same id).

## Consequences

### Positive

- **Wave 7a-N1 is unblocked.** The next session can author six
  `content/game-modes/<id>.yaml` files with `impl_class:` references
  to these façades and ship without touching Kotlin.
- **HUD / queue / scoreboard / `/astera-match start` tab-completion**
  can enumerate all eight registered modes (Norma, Annihilation,
  six Wave 5c modes) via `IGameModeFrameworkRegistry.all()` without
  knowing the legacy listener layer exists.
- **ADR-0032's deferred "6 mode impls" line is finally closed**
  without disturbing the legacy stack — the per-mode `*Listener`
  classes, sealed `GameMode` config hierarchy, Star Generator,
  Anomaly, and Sudden Death pipeline all continue to work
  unchanged.
- Symmetric with how Phase 3 modes will land: drop a new YAML +
  new `IGameMode` impl + `register(it)` line; no AsteraMatchPlugin
  rewiring of legacy infra.

### Neutral / costs

- Six new files at ~75 LOC each (mostly KDoc + boilerplate). The
  parameterized test reuses one harness for all six rather than six
  near-identical test files.
- Two-layer mode model (legacy config + new façade) persists. ADR-0032
  noted this would collapse "once two non-Norma implementations exist
  to inform the right shape." Annihilation is one such impl; these
  six façades **do not** add new shape — they preserve the legacy split
  so this collapse stays cleanly Phase 3 work, not a forced rush.
- The two i18n trees (`astera.match.mode.*` legacy + `astera.gamemode.*`
  framework) are not deduped. Cheap to consolidate later; for now they
  serve genuinely different consumers.

### Negative

- The six façades are *thin* — their entire value is registry
  presence + framework event publication. If a future contributor
  reads one without context, the "why does this class exist if every
  hook is no-op-or-publish?" question is non-obvious. KDoc on each
  façade points back to NormaGameMode + the legacy listener to give
  the answer, and this ADR is the canonical write-up.

## Verification

- `./gradlew :plugin:match:compileKotlin` — BUILD SUCCESSFUL.
- `./gradlew :plugin:match:test` — `Wave5cGameModesFacadeTest`
  passes (six modes × seven assertion families + the id-distinctness
  cross-check).
- `./gradlew :tools:architecture-test:test` — 58 Konsist rules green.
  i18n parity test green (en / ja both gain the same six new
  `astera.gamemode.*.title` keys).
- `./gradlew check` — BUILD SUCCESSFUL.
- Plugin boot log line `Wave 5gamemode wiring complete: ... populated
  with N mode(s)` reports `N=8` (Norma + Annihilation + six new façades).

## Alternatives Considered

- **State-owning impls (Annihilation pattern)**: would absorb each
  `*Listener` into the façade. Rejected because (a) the listeners
  work, (b) collapsing them would break test fixtures depending on
  the legacy `IGameModeRegistry` (the *legacy* one, not the
  framework `IGameModeFrameworkRegistry`), and (c) ADR-0032 explicitly
  deferred this collapse. Phase 3 is the right time.
- **Supersede ADR-0032**: ADR-0032 is immutable per ADR discipline.
  This ADR *complements* it — ADR-0032's "6 mode impls" line is
  fulfilled under the framework that ADR-0106 introduced.
- **Wait for Wave 7a-N1 to ship Kotlin + YAML together**: would
  conflate framework integration with content authoring. Splitting
  keeps each wave's diff readable and lets Phase 3 surface tools
  pick up the registry presence as soon as this wave merges.

## References

- [[adr/0027-victory-condition-evaluator]] — the score-threshold path
  the legacy listeners flow through for win evaluation.
- [[adr/0032-game-mode-framework-and-generator-anomaly]] — original
  Wave 5c framework (sealed config + per-mode listeners + Star
  Generator + Anomaly). This ADR closes its deferred "6 Kotlin
  GameMode impls" tail under ADR-0106's framework.
- [[adr/0106-igamemode-framework]] — `IGameMode` framework foundation;
  NormaGameMode reference impl this ADR clones six times.
- [[adr/0108-astera-annihilation-mode-foundation]] — state-owning
  Annihilation impl (alternative pattern this ADR explicitly does
  not adopt).
- [[adr/0159-sibling-paper-adapters-allowlist-closure]] —
  `RegionTransitionRelayListener` / `PaperItemPickupAdapter` ship the
  match-domain events (`EntityEnteredRegion`, `EntityPickedUpItem`)
  the wrapped legacy listeners subscribe to.
- `plugin/match/src/main/kotlin/com/astarworks/astera/match/application/gamemode/NormaGameMode.kt` —
  template structurally cloned by the six new façades.
