---
status: Proposed
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0033: Stage Geometry — Block Tracking, Durability, Barrier, Schematic, MatchWorld, Restoration

## Status

Proposed

## Context

Wave 5c (`e9808a2`) landed Game Modes + Star Generator + Anomaly, but every
row in RTM-PARITY-TRACKER Category 3 (World / Stage / Geometry) was still
`❌ nothing` aside from the foundational Stage / Region / WorldId types:

| RTM feature | Pre-Wave-5d status |
|---|---|
| Block tracking domain (`BlockPlaced` / `BlockBroken` + per-match journal) | ❌ |
| Block durability (max 100 HP + tool × material matrix + NBT pseudo-recovery per RTM `BlockState.md`) | ❌ |
| Barrier system (RTM issue #8) | ❌ stub `BarrierId` only |
| Custom machines (RTM issue #10) | ❌ |
| Capture zone / KOTH | ❌ |
| Schematic loader (WorldEdit `.schem`) | ❌ |
| Per-match world isolation | ❌ |
| Block restoration on match end | ❌ |

The BreakCore game mode (Wave 5c) already subscribes to a `BlockBroken`
stub event but no real producer exists — the listener fires only from test
code. To unblock Wave 6 Paper integration and Wave 7 Siege content, Wave 5d
needs the **complete domain + application + Fake/in-memory adapter layer**
for all eight rows, so the only remaining gap on the way to Phase 1 PoC
end-to-end is Bukkit-side wiring (Wave 6).

RTM's `BlockState.md` Writerside documents an "NBT-backed pseudo-recovery"
scheme: blocks track 0..100 HP, take tool-dependent damage, and slowly
regenerate when not under attack. Astera's vendor-neutral analog keeps
the durability state in `IBlockStateGateway` (in-memory Phase 1, NBT-backed
Wave 6) and runs the recovery scheduler over per-match worlds.

Wave 5a-fix ([[adr/0058]]) committed Astera to a Universal Attribute
Container. The §5 rollout schedule originally listed Wave 5d for
`IMobAttributeContainer`. **This wave does not add mob domain** (mobs are
out of scope until Phase 4+ per RTM issue #16); the ADR-0058 Wave-5d row
is deferred to a later wave without amending ADR-0058's body (immutable).

## Decision

Wave 5d ships these six pieces, in this shape:

1. **`BlockState` is a dedicated immutable record** (`BlockState(blockType,
   durability, lastDamagedTick, damagedBy?)` with `MAX_DURABILITY = 100`),
   **not** an `IAttributeContainer`. Durability is a singular state, not a
   base-plus-modifier overlay; `lastDamagedTick` / `damagedBy` are metadata,
   not numeric modifiers. ADR-0058 §5 deliberately excludes blocks. A
   future ADR can revisit if cross-system block modifiers (e.g. "barriers
   grant 1.5× HP to enclosed blocks") become a requirement.

2. **Tool × Material damage matrix** sourced from
   `content/balance/block-durability.yaml` (registered with `schema-gen` as
   `block-durability.schema.json`). Rock-paper-scissors three-way:
   `ToolType { NONE, WOOD, STONE, IRON, DIAMOND }` × `BlockMaterial { WOOD,
   STONE, SOIL, OTHER }`. Unknown blocks default to `BlockMaterial.OTHER`
   and use the OTHER row of the matrix; missing matrix cells fall back to
   `BlockDurabilityRule.DEFAULT_DAMAGE = 25`. The loader returns
   `Result<BlockDurabilityRule, ConfigError>` and fails soft to
   `defaultMatrix()` on missing / malformed YAML.

3. **`Barrier` aggregate lives in `plugin/match`**; `IBarrierGateway` is a
   Match-local port. **`BarrierId` was promoted from `plugin/combat` to
   `plugin/core/.../domain/barrier/`** in Phase A so both Combat
   (`DamageSource.Barrier(barrierId)`) and Match (`Barrier.id`) can
   reference the value class without an inter-feature-plugin dependency.
   `BarrierStatus` is sealed (`Active` / `Disabled` / `Destroyed`); a
   heartbeat `BarrierStatusScheduler` runs at 1 Hz (no-op behaviour today;
   reserved for Wave 6+ Anomaly / sabotage drivers).

4. **`MatchWorld` is externalized via `IMatchWorldRepository`**, not added
   as a field on `core.domain.match.Match`. Core stays Match-plugin-agnostic
   — Wave 5d's per-match world concept is owned by Match. `MatchWorld`
   carries `sourceSchematicPath: String` (Konsist forbids `java.nio.*` in
   `*.domain.*`); the application layer wraps with `Path.of(...)` at the
   I/O boundary.

5. **`ISchematicLoader` is a Core port** (vendor-neutral; future
   `PaperSchematicLoader` lands in Wave 6 as a FAWE adapter per ADR-0015
   D-path). Wave 5d ships `InMemorySchematicLoader` only, with sealed
   error hierarchies `SchematicLoadError` (NotFound / InvalidVersion /
   Malformed) and `SchematicPlaceError` (UnknownWorld / WriteFailed).

6. **Block restoration via per-match `BlockChangeJournal`** — an in-memory
   bounded `ArrayDeque<BlockChange>` per match (capacity **5000**, oldest
   evicted) subscribed to `BlockPlaced` / `BlockBroken`. On `MatchEnded`,
   `RestoreBlocksOnMatchEndedUseCase` iterates the journal in reverse and
   calls `IBlockStateGateway.restore(world, pos)` for each change.

Three Tier 0 promotions land in the same wave commit to set up the new
shapes:

- `plugin/combat/.../domain/damage/BarrierId.kt` → `plugin/core/.../domain/barrier/BarrierId.kt`
- `plugin/match/.../domain/event/BlockBroken.kt` → `plugin/core/.../domain/event/BlockBroken.kt` (companion to new `BlockPlaced` event)
- `plugin/match/.../application/config/ConfigError.kt` → `plugin/core/.../application/config/ConfigError.kt` (so `BlockDurabilityYamlConfig` in Core can use it; message keys renamed `astera.match.config.error.*` → `astera.config.error.*`)

## Consequences

### Positive

- **End-to-end Wave 5d surface**: domain + application + Fake adapters +
  unit tests for every Category 3 row. Wave 6 only needs to swap in Paper
  impls at priority 100.
- **Single mental model for block state**: every consumer reads
  `gateway.getDurability(world, pos)` and writes via
  `gateway.applyDamage(world, pos, amount, tool)`; the Wave 6 NBT-backed
  impl is a drop-in replacement.
- **Per-match world isolation**: matches start in their own `WorldId`
  derived from `MatchId`, persisted via `IMatchWorldRepository`, cleaned
  up on `MatchEnded`. Block tracking + barrier placement target the
  match-local world without coupling to Core's `Match` aggregate.
- **Author-tunable balance**: `block-durability.yaml` round-trips through
  `schema-gen`; balance changes don't need code edits.
- **`ConfigError` consolidation**: Core now owns the canonical sealed
  `ConfigError`, used by both `GeneratorYamlConfig` (Match) and
  `BlockDurabilityYamlConfig` (Core).

### Negative / Trade-offs

- **`BlockChangeJournal` memory growth**: 5000-entry cap per active match
  is a Phase 1 PoC limitation. A long-running BreakCore match with
  constant digging can lose oldest entries (those positions will not be
  restored). Wave 7+ may upgrade to a Postgres-backed journal.
- **`InMemoryBlockStateGateway` placeholder `blockType`**: when an
  untracked position takes its first damage, the gateway has no way to
  query the underlying block (no `IWorldGateway.blockAt` lookup in the
  apply path) and stamps the tracked entry with `BlockType("astera:unknown")`.
  Wave 6 `PaperBlockStateGateway` reads the real `BlockData` and replaces
  the placeholder; in-memory tests should `track(world, pos, state)`
  explicitly to set the correct `blockType`.
- **`BarrierStatusScheduler` is a no-op**: the scheduler entry point exists
  so Wave 6+ can attach Anomaly-driven `Active → Disabled` transitions and
  sabotage timers without re-wiring `AsteraMatchPlugin`. This is a small
  amount of dead compute (one iteration per second per running server)
  documented as intentional.
- **Custom machine domain is P2 minimal**: `SkillCoreMachine` /
  `TrapMachine` ship as data classes only — no use case, no port, no
  scheduler. Wave 6+ adds the activation pipeline once
  `IMatchTeamLookup`-like machinery exists for non-player entities.
- **`Match` ← `Combat` import cycle blocked SkillId direct reference**:
  Weapons currently `compileOnly`-depends on Match (Wave 4.5
  `MatchStartLoadoutApplyListener`), Match would need Combat for
  `SkillId`, Combat already `compileOnly`-depends on Weapons →
  `Weapons → Match → Combat → Weapons` cycle. `SkillCoreMachine.skillId`
  therefore uses `String` (with a kebab-case `require`) instead of
  `combat.SkillId`. A future cleanup could promote `SkillId` to Core
  alongside the existing `MatchId` / `PlayerId` / `WeaponId` moves.

### Mitigations

- **Journal cap warning**: `BlockChangeJournal` logs at WARN on first
  eviction per match so ops monitoring surfaces the issue before silent
  data loss accumulates.
- **`BlockDurabilityLoader` fall-soft**: a missing or malformed
  `block-durability.yaml` logs at WARN and the server boots with
  `BlockDurabilityRule.defaultMatrix()`. Wave 7c (balance iteration)
  may tighten to fail-fast once the YAML is mandatory.
- **`schematicLoader.load()` soft-fail in `CreateMatchWorldUseCase`**:
  missing schematic logs INFO + still records the `MatchWorld` (so block
  tracking / barriers have a target world). Wave 6+ may tighten to
  cancel-match on missing schematic once stage authoring is robust.

## Alternatives Considered

- **`BlockState` backed by `IAttributeContainer`** — rejected per user
  decision (`/clear`-replayed plan-mode Q&A) and ADR-0058 §5. HP isn't a
  base-plus-modifier overlay; the container's mental model is wrong shape
  for a singular metadata-bearing record.
- **`worldId` field on `core.domain.match.Match`** — rejected. Wave 5d's
  per-match world is a Match-plugin concept; Core/Match plugin layering
  must stay clean (Konsist `core must not import any feature plugin`
  catches the reverse, but the cleaner pattern is "Core defines `Match`,
  Match plugin owns `MatchWorld`").
- **Per-plugin `ConfigError` duplication** — considered (Worker β's
  `BlockDurabilityYamlConfig` lives in Core; Core can't import Match's
  ConfigError) but rejected as future-tax. Promoted to Core in Phase A
  Tier 0 prep.
- **Region as a sealed `RegionType` hierarchy** (Protected / Buildable /
  Spawn / NoBuild) per RTM Region variants — deferred. Wave 5d keeps
  `Region(min, max)` plain; type tagging is RTM-PARITY-TRACKER Category 3
  row left at ❌ and re-tasked in a future content wave.
- **In-memory `BlockChangeJournal` as `Map<MatchId, List<BlockChange>>`
  unbounded** — rejected. Heavy-digging matches could OOM. Bounded
  `ArrayDeque(5000)` is the Phase 1 PoC compromise.
- **`PaperSchematicLoader` in this wave** — rejected per handoff
  "NOT in scope" section. FAWE adapter is Wave 6's responsibility per
  ADR-0015 D-path governance.
- **`SkillId` promotion to Core for `SkillCoreMachine` type-safety** —
  considered, deferred. The promotion is a small but cross-cutting
  change (touches every Combat skill site); the Phase 1 PoC closing
  schedule prioritizes Wave 5d → 5e → 5f progression over the cleanup.

## References

- [[adr/0001]] Clean Architecture with Minecraft as edge
- [[adr/0002]] Vendor-neutral adapter API
- [[adr/0015]] External plugin governance (D-path: self-build over Bukkit family)
- [[adr/0022]] Multi-plugin portable architecture (sibling-isolation matrix)
- [[adr/0029]] RTM Empirical Catalog (Category 3 row count)
- [[adr/0030]] Combat Depth — damage path consolidation precedent
- [[adr/0032]] Game Mode framework + Star Generator + Anomaly (Wave 5c)
- [[adr/0058]] Universal Attribute Container — §5 rollout schedule, Wave-5d row deferred
- [[../IMPLEMENTATION-ROADMAP]] Wave 5d row + Tier 4 World Geometry
- [[../RTM-PARITY-TRACKER]] Category 3 (World / Stage / Geometry)
- RTM Writerside `BlockState.md` (max 100 HP NBT pseudo-recovery, source of durability semantics)
- RTM issues #8 (Barrier), #9 (Block tracking), #10 (Custom machines)
