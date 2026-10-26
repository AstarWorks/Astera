---
status: Accepted
date: 2026-10-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0280: Cover damage: tools break, weapons chip, the match end rebuilds

## Status

Accepted. Extends ADR-0090 (field tools and cover blocks).

## Context

ADR-0090 made cover blocks (reinforced wall, barricade, sandbags, hardened soil) that field tools break with left-click. Two things were left open: guns and skills could not touch cover at all, and nothing said what happens to broken cover when the match is over. The founder decided on 2026-10-27:

1. The tools stay free-loaned to every participant.
2. Broken cover is not regenerated while the match runs. It is rebuilt when the match ends, so the next match on the stage starts intact.
3. Guns and skills may damage cover, per skill, through a YAML field. A skill without the field does no block damage. Weapons only chip.
4. Large-scale breaking belongs to the tools: each field tool gets its own skill.

## Decision

**One door.** All cover damage and cover placement goes through `CoverBlockService` (`core/feature-gameplay`): `strikeCell` (one cell, used by the tool swing in `HitCustomBlockUseCase`), `strikeBall`, `strikeRay`, `strikeLine`, `placeWall`, `rebuild`. The block gateway (`IBlockStateGateway`) stays the single owner of HP.

**Tools vs weapons.**
- Tools damage with the (tool, material) matrix: `block_damage.tool` makes a skill count as that tool, and `amount` is the matrix base (100 = one swing). The right tool is 100, the wrong one 25 or 0, so a pickaxe skill is strong on stone and useless on sandbags.
- Weapons use a flat chip: `block_damage.amount` HP regardless of material (`BlockDamageProfile.flat`). The numbers are small against 200 to 500 HP cover (pistol 12, rifle 20, shotgun 30, fire 35, frost nova 20, lightning 25, frost shard 10), so no gun or spell opens a wall alone.

**Per-skill opt-in.** `SkillSpec.blockDamage` (YAML `block_damage: { amount, radius, tool, reach, shape }`) and `SkillSpec.blockPlace` (YAML `block_place: { block, distance, width, height }`) are the only way a skill reaches cover. Absent means none. Projectile skills strike where the projectile lands (`ProjectileSkillBinding.blockDamage`, applied in `ProjectileImpactResolver`, shared by Paper and the sim); AOE skills strike around the cast point; other archetypes strike along the caster's look ray (`shape: line` cuts a tube of `reach` blocks). Caps keep a skill from clearing a map: radius <= 6, reach <= 16, 48 cells per strike.

**Tool skills (right-click on the held field tool).**
- Pickaxe, Breach Strike: 300 matrix damage on every cover cell within radius 2 of where the look ray (5 blocks) meets cover, cooldown 160 ticks. A 400 HP stone wall falls in two strikes.
- Axe, Cleave: 300 matrix damage along a 6-block line, radius 1, cooldown 120 ticks. A 300 HP barricade falls in one cleave; stone only takes 75.
- Shovel, Dig In: a temporary 3 wide x 2 tall sandbag wall (200 HP each) 2 blocks ahead, only into empty cells, cooldown 400 ticks. The blocks are ordinary tracked cover (every tool can break them) and are marked temporary.

**Match-end rebuild.** `RestoreBlocksOnMatchEndedUseCase` (already first to handle `MatchEnded`) calls `CoverBlockService.rebuild` for the match's world, bounded by the stage slot (+-512 blocks around the stage origin): every cover block returns to full HP, temporary blocks are removed, crack animations cleared. The existing journal replay and arena repair re-place the world blocks that were broken. Nothing heals cover during the match; `BlockRecoveryScheduler`'s slow pseudo-heal of damaged blocks is unchanged (see Consequences).

## Consequences

### Positive
- One code path for tool swings, spells, guns, tool skills and the rebuild; a rule change (cap, sound, animation) is made once.
- Authors tune cover damage in skill YAML with no Kotlin; the schema (`content/schemas/skill.schema.json`) documents it.
- The next match always starts on intact cover.

### Negative / Trade-offs
- Flat chips ignore material, so a pistol chips stone as well as wood. Accepted: the numbers are small.
- `BlockRecoveryScheduler` still adds back about 5 HP per 10 s to damaged blocks, which contradicts "not regenerated over time". It was left alone because it also serves non-cover blocks; the founder should decide whether to disable it for cover.

### Mitigations
- Game-sim `SkillCoverDamageScenarioTest` covers chip, no-field, breach, cleave, dig-in and the rebuild.

## Alternatives Considered

- **A second service for skills**: rejected; two damage paths drift apart.
- **All weapons damage cover by default**: rejected by the founder; opt-in per skill keeps balance explicit.
- **Regenerate cover over time**: rejected by the founder; rebuild at match end only.

## References

- ADR-0090 (field tools and cover), `docs/contributing/add-a-skill-yaml.md`, `docs/vision/areas/modes-combat.md`
