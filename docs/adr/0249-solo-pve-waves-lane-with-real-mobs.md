---
status: Accepted
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0249: Solo PvE waves lane with real mobs

## Status

Accepted

## Context

A single player could not start a game: every queue lane was PvP, `kind: pve | training` (`QueueSettings.kt`,
`QueueMatchScheduler.kt`) had no lane, and `content/pve/encounters/{blazing-pit,whispering-cavern}.yaml` were
open-world zone descriptions nothing reached. The mob foundation (ADR-0085) was bookkeeping only:

- `MobSpawnerRouter` picks the first *available* `IMobAdapter`. `MythicMobsMobAdapter` is a skeleton that always
  reports `isAvailable() = false` (installed or not), so every spawn goes to `MinimalMobAdapter`.
- `MinimalMobAdapter` **never created an entity**. It stored `(handle, position, spec)` in a map; its KDoc promised a
  "Wave 6b Paper subclass" that was never written. A "spawned" mob existed nowhere in the world.
- `MobSpec.baseEntityType` is an `EntityCategory` (`MOB`), not a vanilla entity type, so even a Paper adapter would
  not have known what to spawn.

## Decision

### 1. Real bodies behind the existing router

- New outbound port `IMobBodyGateway` (Core, `spawn / remove / isAlive`). `MinimalMobAdapter` takes it as an optional
  constructor argument: with it, `spawn` creates a body and the returned `EntityHandle.id` **is the entity UUID**
  (combat addresses targets as `PlayerId(entityUuid)`, and `MobKilled` carries the same id); without it (game-sim,
  headless) the adapter stays pure bookkeeping. `MythicMobsMobAdapter` is unchanged; MythicMobs stays an optional
  soft-dep that nothing requires.
- `PaperMobBodyGateway` (Core `adapter/paper/mob/`, wired through the new `CorePlatformPorts.mobBodies`) builds a
  vanilla `Mob` of the new `MobSpec.vanillaEntity` (`vanilla_entity: minecraft:zombie` in `content/mobs/*.yaml`) and
  puts Astera's numbers on it: `mob.max_hp` -> `MAX_HEALTH`, `mob.damage` -> `ATTACK_DAMAGE`, `mob.speed` ->
  multiplier on `MOVEMENT_SPEED`, name tag from `display_name`, glow. It also removes what would make a PvE arena
  wrong: no daylight burning (the world is frozen at noon, ADR-0245), no zombie reinforcements, no vanilla drops or
  XP, never persisted, tagged `astera_managed` and `astera_pve_mob`.
- Targeting: vanilla goals find players, and a one-second sweep forces the nearest live survival / adventure player as
  target for idle bodies (wolves and other types without a player-hunting goal). `FOLLOW_RANGE` is raised to 64.
- **Damage and kills need no new code.** Astera skills hit non-player `LivingEntity` through
  `PaperEntityDamageGateway` (`entity.damage(amount, attacker)`), which gives kill credit and the vanilla death event;
  Core's `BukkitEventAdapter` turns it into `MobKilled(killerId)`. A mob hitting a player is re-routed by the same
  adapter into the Astera HP container with `finalDamage`, so `mob.damage` is what the player loses.
- `IMobAdapter.isAlive` / `IMobSpawner.isAlive` (default `true`) let an encounter notice a mob that fell out of the
  world instead of waiting for it forever.

### 2. Encounter waves and the `pve-waves` mode

- `EncounterZone` gains `waves: List<EncounterWave>` (`waves: - mobs: [{mob_type_id, count}]` in the encounter
  YAML). The two open-world zones have none. New `content/pve/encounters/proving-grounds.yaml`: 2 dark zombies,
  3 dark zombies, 3 dark zombies + 2 cave spiders.
- `PveWaveGameMode` (id `pve-waves`, `content/game-modes/pve-waves.yaml`) extends the siege skeleton (per-match run
  state, kill / tick hooks, HUD lines) but needs no objective regions; the stage only names `mob-spawn-*` regions
  (`content/stages/pve-proving-grounds.yaml`). It spawns the next wave through `IMobSpawner`, counts a kill from
  `MobKilled` of a mob it spawned, drops mobs that vanish, and despawns leftovers when the match ends.
  A wave that cannot spawn a single mob **cancels** the match (`EndReason.Cancelled("pve-spawn-failed")`) instead of
  counting as cleared.
- Win / loss: `EndReason.EncounterCleared(winner, waves)` / `EncounterFailed(opponent, wave, waves, timeUp)`.
  `MatchEnded.winningTeam` is the party's team on a win and the **empty** opposing team on a loss, so the existing
  result screen, MVP rule and `MatchSummary.outcomeOf` give VICTORY / DEFEAT without a PvE special case. The party
  shares `lives` deaths (default 2; `PlayerKilled` of a participant costs one); `time_limit_seconds` (default 600)
  also loses. The result chat names the reason with the wave counts.
- Sidebar (`MatchHudStatus`): `Wave 2/3 - 5 enemies left` (or `Wave 2/3 in 4s` during the intermission), lives,
  defeated count; en / ja.
- Rewards: no new system. `MatchTallyService.onMobKill` counts a mob kill as a kill for the killer, and
  `match-rewards.yaml` gets `modes.pve-waves` (win / lose / per_kill / mvp). The existing return-to-lobby listener
  (`TeleportToLobbyOnMatchEndedListener`) and result screen are reused unchanged.

### 3. The lane and cooperative formation

- `queue.yaml` appends `pve-waves` (`kind: pve`, `min_players: 1`, `players_per_team: 2`). It starts after the normal
  queue countdown with one player; a full lane (2) starts at once.
- `kind: pve` lanes are **cooperative**: `MatchSizeSpec.cooperative` makes `InMemoryMatchmaker` put everyone on team 0
  (capacity = `players_per_team`) and `QueueLane.capacity` follows. Without this, two queued players would be split
  red / blue and one of them would see DEFEAT on a won run. `training` lanes keep the old formation.

## Consequences

### Positive
- A player can queue alone and play a complete, rewarded run; the same machinery (`vanilla_entity`, waves, the PvE
  tally) serves later encounters and bosses.
- No new damage path: HP, damage numbers, kill credit and loot hooks are the ones PvP already uses.
- MythicMobs remains optional; the router order is unchanged.

### Negative / Trade-offs
- Mob HP is Bukkit health, not an Astera HP container, so status effects on mobs go through combat's existing
  entity path only (same limitation as training dummies and summons).
- A lane with 8 entries adds a third front row to the lobby queue island (`LobbyLanePadLayout`).
- The stage is a plain open area on the playtest superflat world; arenas (ADR-0246) can re-skin it by renaming the
  `mob-spawn-*` regions into their layout.
- `ai: stationary` only turns the mob's awareness off; `flee` / `aggressive` hints are still not distinguished.

## Alternatives Considered

- **MythicMobs as the PvE backend**: it is a soft-dep we must not require, and the adapter is a skeleton.
- **A new `IMobAdapter` (`PaperMobAdapter`) ahead of Minimal in the router**: duplicates the bookkeeping and the
  skill publishing; a body port keeps one adapter and lets headless hosts stay entity-free.
- **A separate wave content type outside `pve/encounters`**: the encounter is the natural home (it already names
  the mob pool, difficulty and death penalty); waves are one optional field.
- **Two teams, one empty, no cooperative formation**: breaks co-op outcomes (see above).

## References

- ADR-0085 (mob foundation), ADR-0099 (encounter content), ADR-0245 (no natural mobs, fixed noon), ADR-0149 (Astera HP).
- `tools/e2e/.../PveWavesPlaythroughE2eTest.kt`.
