---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0175: Annihilation pillars are stage structures that combat can target

## Status

Accepted. Narrows two items of ADR-0108 (the `INexusEntity` port and the
`content/nexus-templates` loader were never consumed and are deleted).

## Context

ADR-0108 shipped the Annihilation state machine (3 pillars per team, 4 phases,
win rule) but nothing connected it to the world: pillars lived only as tracker
state, `recordDamage` was only called by the sudden-death DoT, pillar positions
were `pillar-<team>-<tier>` stage *region names* that the domain `Stage` dropped
on load (`Region` has no name), and `nexus-templates` + `INexusEntity` described
a Phase 6 Paper adapter that was never written.

## Decision

1. **Pillars are a proper stage field.** `Stage.pillars: List<StagePillar>`
   (`team`, `tier`, block `position`, `block` type, optional `hp`), YAML key
   `pillars:`. The `pillar-*` regions, `content/nexus-templates/`,
   `NexusTemplateYamlConfig` and `INexusEntity` are deleted: absolute positions in
   the stage are simpler than offsets from a spawn plus a template id, and a stage
   is already the per-world hand-built asset.
2. **Pillars are combat targets, not a parallel damage path.** Core port
   `IStructureTargets` (impl: Match `AnnihilationPillarTargets`) exposes pillars
   under a stable synthetic `PlayerId` - the same trick mobs use. Combat wraps
   `INearbyTargetQuery` and `IEntityDamageGateway` (`StructureAware*`) so AoE,
   `damage` mechanics, projectile `hit_entity` and melee reach pillars through the
   normal skill pipeline; `ProjectileImpactResolver.onHitBlock` redirects a landing
   on a pillar block into an entity hit. Match registers the port after Combat
   wires; Combat resolves it lazily per call (Combat must not depend on Match).
3. **Rules live with the pillar side, not in combat.** Attacker must be in the
   pillar's match; own team cannot damage its pillars; Build-phase / unopened
   pillars reject damage (`NOT_VULNERABLE`). Everything ends in
   `AnnihilationMatchTracker.recordDamage` (events, elimination, win check).
4. **World presence is host-neutral.** `AnnihilationPillarWorld` places the block
   at match start (`IWorldGateway.placeBlock`), removes it on `NexusDestroyed` with
   effects + a match broadcast, and clears the rest at `MatchEnded`. The only
   Bukkit piece is `PaperPillarBlockListener`: an enemy block break on a pillar is
   cancelled and deals `AnnihilationConfig.pillarBreakDamage`; explosions never
   take a pillar block.
5. **Tier ordering.** Phases keep gating tiers (Tier 1 in Phase 2, Tier 2 + 3 in
   Phase 3). Added: destroying a team's Tier 1 opens *that team's* Tier 2 at once.
   Before this the "team lost both lower tiers -> Phase 3" cascade could never fire
   (Tier 2 was immune until Phase 3).
6. **Win rule.** `MatchEnded.winningTeam` could not carry an Annihilation result
   (`EndMatchUseCase` only knew score thresholds). New `EndReason.TeamVictory(winner)`
   carries the mode's decision (null = draw). A last-team-standing win needs >= 2
   teams in the match; a single-team match (solo playtest) never wins by elimination
   and ends at sudden-death expiry without a winner.
7. **Phase clock.** `AnnihilationGameMode` stamps phase entry with the scheduler
   tick (it stamped `0` while `onTick` receives the absolute server tick, so on any
   server up for 5+ minutes the Build phase expired immediately).
   `MatchPlatformPorts.annihilationConfig` lets a host (the simulator) pick the
   tunables; `default_config` in `content/game-modes/annihilation.yaml` is still not
   wired in.

## Consequences

- Skills damage pillars with no per-skill work; a pillar is hit by an AoE even if it
  is not in line of sight (same as any other target).
- A pillar entity id is derived from the nexus id; `isStructure` is a linear scan
  over tracked matches (a handful of pillars).
- Eliminated teams' players still respawn until the match ends (a per-match life
  ledger / spectator conversion is not built yet).
- In-game verification still needed for the Paper-only pieces (block-break
  cancel/damage, block placement/removal, effects).
