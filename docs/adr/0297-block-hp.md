---
status: Accepted
date: 2026-10-28
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0297: One sparse block-HP model for terrain and cover

## Status

Accepted (MODE-ARS6, founder task 2026-10-28). Replaces MODE-ARS5 tool work/progress and the HP-storage/recovery portions of ADR-0090, ADR-0156, ADR-0160, ADR-0166 and ADR-0280. Their placement, loot, arena journal and skill opt-in decisions remain applicable; accepted ADR files are unchanged.

## Context

The founder requested accumulated block damage: a bore should destroy soft blocks but only crack hard blocks, with bounded memory and idle damage forgotten like garbage collection. MODE-ARS5 computed `ceil(30 * hardness / speed)`, contributed at most 24 work ticks per cast, and kept unfinished work for 200 ticks. Cover instead had its own HP tracker, six damage portions per cast, tool matrix and persistent durability journal. Two clocks, two stores and two crack sources made the same cast behave differently against terrain and cover.

## Decision

Use one application-layer `BlockHpStore` per active `IBlockStateGateway`. It holds only integer-cell `world/pos -> (damage, lastHitTick)` entries. Terrain and cover add to the same entries, across casts, players, weapon chips and tool swings. Full and broken blocks have no HP entry. Vanilla maximum HP is `max(1, ceil(hardness * 100))`; custom definitions override it with `base_durability`. Damage is transient and is neither journaled nor hydrated. Retain world/placement journals for arena repair. `CoverBlockService` coordinates cover selection, placement, feedback and rebuild; it no longer implies a distinct HP store.

### Cast damage and authored content

`break_area.block_damage` is the only accepted damage field, an integer in 0..2000. Delete `cover_damage`, the old `power` gate, work ticks, work cap and per-cell expiry tasks. All shipped bore/shatter/fell/excavate skills now have base damage 100. Geometry, cooldowns, arena guards, the 30-tick centre-out ripple, material particles/sounds and held-tool/stand-in loot remain.

Vanilla preferred-class multipliers by tier: NONE 0.5, WOOD 0.75, STONE 1, IRON 1.5, DIAMOND 2, NETHERITE 2.5. A wrong class multiplies by 0.1. Surfaces of hardness <=0.8 need no preferred class: dirt, sand and wool remain useful targets of a pickaxe bore. Hardness >=50 multiplies damage from tiers below diamond by 0.01. Positive base damage rounds down with a minimum of 1 HP; zero damage stays zero. Negative hardness, protected technical blocks, bedrock/barrier and ore nodes remain excluded.

Custom blocks use `tool_effectiveness` percentages, or the global `damage_matrix` on a missing cell: damage = floor(base damage * percentage / 100). Matrix zero means immunity to that tool. Flat weapon chips retain their authored opt-in `block_damage` amount and share the store; they do not gain tool multipliers.

Table: damage per 100-base bore with pickaxes, followed by casts to break (continuous hits within the idle window).

| Block | Max HP | Stone damage / casts | Iron damage / casts | Diamond damage / casts |
|---|---:|---:|---:|---:|
| Dirt / sand | 50 | 100 / 1 | 150 / 1 | 200 / 1 |
| White wool | 80 | 100 / 1 | 150 / 1 | 200 / 1 |
| Plain stone | 150 | 100 / 2 | 150 / 1 | 200 / 1 |
| Cobblestone | 200 | 100 / 2 | 150 / 2 | 200 / 1 |
| Iron block | 500 | 100 / 5 | 150 / 4 | 200 / 3 |
| Stone bricks (shipped stone-bulwark) | 600 | 80 / 8 | 120 / 5 | 200 / 3 |
| Glass (shipped tempered-glass) | 250 | 60 / 5 | 100 / 3 | 160 / 2 |
| Obsidian (shipped obsidian-plate) | 1500 | 1 / 1500 | 30 / 50 | 150 / 10 |
| Hardness-50 terrain without a custom override | 5000 | 1 / 5000 | 1 / 5000 | 200 / 25 |

`OBSIDIAN`, `STONE_BRICKS` and `GLASS` already identify custom blocks globally by unique base material. Their YAML HP overrides vanilla hardness; the simulator now resolves these base materials too. Explicit WOOD/STONE 1% entries on obsidian-plate prevent the missing-cell global matrix from making stone tools effective. This is why actual shipped obsidian differs from the pure hardness-50 row.

### Idle GC and lifecycle

`content/balance/block-durability.yaml` authors `idle_ticks: 200`. A 20-tick periodic sweep over all worlds with damage drops entries idle for >=200 ticks. Thus actual recovery is within 200..219 ticks after the latest hit. No per-block heartbeat, healing increments, retained full-HP records or scheduled task per damaged cell. Work is O(damaged cells) per second. Removing the last entry also removes its empty world map.

Every expired position clears its overlay on the region scheduler, with an O(1) check that a newer hit has not claimed it. Match-end cover rebuild and arena reset clear all damage for that world, including terrain. This deliberately follows the requested world-wide reset: another match sharing that world loses its partial damage too. Cover placement identities / broken-geometry metadata in the simulator and temporary-placement keys are world geometry bookkeeping, distinct from the sparse HP store.

Paper break/place/multi-place/explosion/burn/entity-change/piston events clear damage and overlays. World unload drops the store. Skill replacement and revert also clear damage. Vanilla hand-mining of terrain remains vanilla. A broken cover block stays absent until arena repair; idle GC never rebuilds absent geometry.

### Presentation and limitations

Both terrain and cover use Core's animation gateway, with `stage = clamp(floor(damage / maxHP * 10), 0, 9)` and a stable world/coordinate source id passed to `sendBlockDamage`. A nonbreaking hit sends four block particles plus the material hit sound; a breaking hit keeps material break effects and drops. Custom matrix-denied hits clink. Block drops still respect `GameRules.BLOCK_DROPS`.

Cracks are sent to current viewers within 48 blocks. No movement resubscription is added; a player arriving after a hit sees the overlay on the next hit. Vanilla clients may age an overlay before server GC. Plugins/commands that change blocks without Bukkit events must call the clear API (same-material `/setblock` cannot be detected from block data). Region-safe world I/O remains at the Paper edge; sparse state follows the project's existing single-thread mutation contract, not concurrent Folia match simulation. The simulator uses representative vanilla hardness values; production always reads `Material.hardness`.

## Consequences

### Positive

- One bounded damage representation, shared across damage sources and players, with no Bukkit dependency in domain/application.
- Hardness and custom HP visibly separate soft terrain, ordinary stone and cover.
- Removal of work ticks, per-cell work tasks, pseudo healing and HP journal writes reduces state and scheduling.

### Negative / Trade-offs

- Idle recovery is discontinuous and sweep-granular.
- All damage in a world is reset together at match end or arena repair.
- Late-arriving viewers and event-free external block edits have the limits above.
- Old HP journal records are intentionally ignored; no compatibility decoder or migration bridge.

### Mitigations

- Generated block/skill/balance schemas and contentLint cover the authored values.
- E2e and real-wiring game-sim scenarios cover accumulation, two players, expiry, custom cover, obsidian and empty-hand stand-in drops.
- Manager runs behavioural tests in the integration window; this worker only compiles and runs architecture/content checks.

## Alternatives Considered

- **Keep work ticks plus independent cover HP**: duplicates clocks and produces incomparable damage budgets.
- **Store HP on every arena block**: memory scales with arena volume even when untouched.
- **Incremental per-tick regeneration or one expiry task per cell**: retains unnecessary work and scheduling proportional to all damaged positions.
- **Keep the old power ceiling**: stone tools could never scratch obsidian; remove it and let HP/factors express resistance.

## References

- Related ADRs: [[adr/0090-custom-block-extension-two-axis-tool-postgres-durability]], [[adr/0280-cover-damage-tools-vs-weapons-and-match-end-rebuild]].
- Area: [[vision/areas/modes-combat]]. Content authoring: `content/blocks/README.md`.
- `MatchArsenalE2eTest`, `ArsenalToolsScenarioTest`, `ToolSkillCustomBlockScenarioTest`, `SkillCoverDamageScenarioTest`, `CustomBlockToolScenarioTest` (compiled; behavioural execution deferred).
