---
status: Accepted
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0245: Server-owned world baseline (no natural mobs, fixed daytime)

## Status

Accepted

## Context

On the live stack both backends reported `spawn_mobs = true`, difficulty Normal and a running day cycle. A PvP player
could be killed by zombies, skeletons and slimes, and the e2e harness only stayed green because each suite switched
spawning off over RCON (`docs/bug-hunts/e2e-2026-10.md` Follow-up 2/3). Only the lobby sky island applied its own rules
(ADR-0180); the overworld, which is also the match arena, had none.

## Decision

- `content/world-rules/baseline.yaml` (`WorldBaselineYamlConfig`) holds `mob_spawning: false`, `time_of_day: 6000`,
  `time_frozen: true`. A missing file falls back to the same values.
- `PaperWorldBaseline` (Core, every server role) applies it through `IWorldRulesGateway` / `IWorldTimeGateway` to every
  loaded world: at Core enable, on each `WorldLoadEvent`, on `ServerLoadEvent` and on the next tick (Core enables at
  STARTUP, before the overworld exists). The lobby world keeps its own, identical rules from `lobby.yaml`.
- Paper 26.x API: the gateways use `GameRules.SPAWN_MOBS` (`spawn_mobs`) and `GameRules.ADVANCE_TIME` (`advance_time`)
  instead of the legacy `DO_MOB_SPAWNING` / `DO_DAYLIGHT_CYCLE` aliases. Time is set with `World.setTime`;
  `time query daytime` is not used.
- **PvE is unaffected.** PvE mobs are spawned only by Astera: `MobSpawnerRouter` -> `IMobAdapter`
  (`MythicMobsMobAdapter` when MythicMobs is present, else the in-memory `MinimalMobAdapter`). Nothing relies on vanilla
  natural spawning, and plugin spawns (`World.spawn`, MythicMobs) are not governed by the `spawn_mobs` rule.
- **Difficulty stays Normal** (`server.properties` / compose `DIFFICULTY`; Astera does not change it outside the
  lobby). Peaceful would break the energy bar: the food bar is the energy bar (ADR-0194), and in Peaceful vanilla
  refills food directly each tick (no `FoodLevelChangeEvent` to cancel). With `spawn_mobs` off, Peaceful buys nothing.
- The e2e harness no longer sets these rules. `E2eBase.quietWorld` asserts `spawn_mobs` and `advance_time` are `false`
  and only clears mobs left in the persisted world.

## Consequences

### Positive
- A PvP player is never attacked by natural mobs, with no manual RCON step; the e2e flake source is gone.
- Rules are content YAML.

### Negative / Trade-offs
- Vanilla night/day and mob-based gameplay do not exist on any world. A future survival-style world needs a per-world
  override in the baseline schema.
- Mobs already saved in a persisted world are not removed by the rule.

## Alternatives Considered

- **Peaceful difficulty**: moves the energy bar (see above).
- **Per-role env var**: content YAML is the established place for world rules (ADR-0180).
- **Keep the harness workaround only**: leaves the product exposed.

## References

- ADR-0180 (lobby world), ADR-0194 (food bar = energy bar), `docs/bug-hunts/e2e-2026-10.md`
