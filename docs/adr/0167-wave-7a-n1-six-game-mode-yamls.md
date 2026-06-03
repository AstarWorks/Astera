---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0167: Wave 7a-N1 — Six game-mode YAML files for the Wave 5c-N1 façades

## Status

Accepted. Content-only follow-up wave that completes the YAML side of
the IGameMode framework integration begun by Wave 5c-N1 (ADR-0165).
Closes the last open work item from the Wave 5c lineage.

## Context

[ADR-0165][adr-0165] (Wave 5c-N1, this session) added six thin-façade
`IGameMode` implementations (`BattleLoyalGameMode`, `BreakCoreGameMode`,
`CarryTntGameMode`, `CarryMiniTntGameMode`, `CaptureWoolGameMode`,
`StealJewelryGameMode`) and registered them programmatically in
`AsteraMatchPlugin.onEnable` so the framework registry would advertise
them even before any YAML existed. That filled the Kotlin gap; this
wave fills the YAML gap.

Without per-mode YAML, `GameModeContents.bootstrap(...)` reports only
two loaded specs (norma.yaml + annihilation.yaml). The programmatic
registration kept the registry populated, but the framework's
**configured-vs-fallback** boundary remained blurry: surfaces that read
the YAML spec list (Phase 3 queue UI, mode picker) saw two configured
modes plus six "phantom" registry entries with no spec to drive
default_config overlays from.

With six YAMLs in place, `GameModeContents.bootstrap(...)` reports
**8 loaded specs**, every registry entry has a parsed spec backing
it, and Phase 3 surfaces have one consistent enumeration source.

## Decision

### §A — One YAML per façade, all minimal

Six files under `content/game-modes/`:

- `battle-loyal.yaml`
- `break-core.yaml`
- `carry-tnt.yaml`
- `carry-mini-tnt.yaml`
- `capture-wool.yaml`
- `steal-jewelry.yaml`

Each follows the [GameModeYamlConfig][yaml-config] schema:

```yaml
id: <kebab-id>                                  # matches façade.id().value
title_key: astera.gamemode.<kebab-id>.title     # matches façade.titleKey()
impl_class: com.astarworks.astera.match.application.gamemode.<Name>GameMode
tick_period_ticks: 20                           # explicit; default would match
default_config: {}                              # empty (see §B)
```

### §B — `default_config: {}` is intentional, not a placeholder

The legacy `match.domain.mode.GameMode` sealed config classes
(`BattleLoyal.scoreThreshold`, `BreakCore.coreBlockId`,
`CarryTNT.deliveryRegionIds`, etc.) are **stage-YAML scoped, not
game-mode-YAML scoped**. A single match's per-stage `stage.yaml`
(e.g. `siege-arena.yaml` shipped by Wave 7a) carries the runtime
config; the game-mode YAML describes the mode framework-wide.

The empty `default_config` here means "no framework-level overrides";
the legacy listener still pulls per-match tunables from the stage YAML
via the existing config-binding path. This separation is consistent
with how `norma.yaml` and `annihilation.yaml` behave (Norma has empty
default_config; Annihilation has framework-level pillar HP / phase
ticks because those *are* framework-wide and not per-stage).

### §C — `tick_period_ticks: 20` written explicitly

The schema defaults to 20L, so `tick_period_ticks: 20` is redundant.
Written explicitly anyway to make the cadence visible at the YAML
level — a future maintainer changing the framework cadence per mode
won't have to read the Kotlin façade to learn the current value. Same
posture as `norma.yaml`.

### §D — Validation: `id` ↔ `impl.id()` round-trip

`GameModeYamlConfig.toSpec()` calls `require(implClass.isNotBlank())`
but does NOT directly check `id == loadedImpl.id().value`. The
`GameModeContents.registerInto(...)` path verifies the round-trip
during YAML load — if the YAML's `id: battle-loyal` resolves to an
`impl_class` whose `id()` returns something else, the loader logs a
WARN and skips the mode. Wave 5c-N1's façades all return the
matching kebab-case `GameModeId`, so the six YAMLs load clean.

### §E — Registry overwrite ordering preserved

`AsteraMatchPlugin.onEnable` flow (post-Wave 5c-N1, unchanged here):

1. `gameModeContents.bootstrap(contentDir)` parses the 8 YAML files.
2. `gameModeContents.registerInto(registry)` reflectively instantiates
   each `impl_class` via no-arg constructor (eventBus = null), then
   `registry.register(instance)`.
3. `registry.register(BattleLoyalGameMode(eventBus = eventBus))` ×6
   (Wave 5c-N1 wiring) **overwrites** the reflective instances with
   bus-wired ones so framework events publish in production.

Same overwrite pattern as the Norma/Annihilation programmatic
registration. No code change required for this wave — the YAML loads
into the slot, the programmatic loop overwrites it. Reported by the
existing `Wave 5c-N1 wiring: 6 IGameMode façades ... registered`
log line.

## Consequences

### Positive

- **`GameModeContents.bootstrap(...)` log line now reports 8 loaded
  specs** (was 2). Visible end-to-end alignment between YAML, registry,
  and façades.
- Phase 3 surface tools (mode picker, queue UI, `/astera-match start`
  tab-completion) can enumerate all 8 modes from the parsed spec list
  rather than walking the registry post-hoc.
- The Wave 5c lineage (legacy listener + Wave 5gamemode framework +
  Wave 5c-N1 façades + this wave's YAMLs) is now fully closed. No
  remaining "spec for this mode" gaps.

### Neutral / costs

- Six trivial YAML files (~22 lines each including KDoc). Pure content.
- No Kotlin change, no test change, no Konsist change.
- `tick_period_ticks: 20` is redundant verbosity (see §C rationale).

### Negative

- None.

## Verification

- `./gradlew check` BUILD SUCCESSFUL.
- 58 Konsist rules green (no rule introduced).
- `GameModeContentsTest` still passes (loads from a test fixture dir,
  not `content/game-modes/`, so the new files do not affect it).
- Manual smoke (Phase 1 dev tooling): boot the plugin, confirm log line
  `Loaded 8 game-mode spec(s) from content/game-modes/ (failed: 0)`
  and `Wave 5gamemode wiring complete: IGameModeFrameworkRegistry
  populated with 8 mode(s) (YAML-resolved: 8). DefaultGameModeScheduler
  registered.`

## Alternatives Considered

- **Inline per-mode default_config**: would let stage YAML omit some
  runtime config and have the mode YAML supply defaults. Rejected
  because the legacy listeners already pull all tunables from stage
  YAML; introducing a second source would split the source of truth.
  Phase 3 may re-evaluate when typed config carriers land.
- **Wait for typed `GameModeConfig` hierarchy**: ADR-0106 deferred
  typed configs to Phase 3. Until then `Map<String, String>` is the
  schema; six files use the same schema as Norma + Annihilation.
- **Bundle YAMLs into Wave 5c-N1's commit**: would conflate Kotlin
  + content into one diff. Splitting keeps the framework integration
  (5c-N1) and content authoring (this wave) reviewable independently.

## References

- [[adr/0032-game-mode-framework-and-generator-anomaly]] — Wave 5c
  original framework whose "6 mode impls" tail this lineage closes.
- [[adr/0106-igamemode-framework]] — IGameMode framework + YAML
  schema this wave instantiates.
- [[adr/0165-wave-5c-finish-six-game-mode-facades]] — Wave 5c-N1
  façades these YAMLs reference via `impl_class`.
- `plugin/match/src/main/kotlin/com/astarworks/astera/match/application/config/gamemode/GameModeYamlConfig.kt`
  — schema this wave's YAMLs conform to.
- `content/game-modes/norma.yaml` + `annihilation.yaml` — existing
  YAML templates this wave clones the shape from.

[adr-0165]: ./0165-wave-5c-finish-six-game-mode-facades.md
[yaml-config]: ../../plugin/match/src/main/kotlin/com/astarworks/astera/match/application/config/gamemode/GameModeYamlConfig.kt
