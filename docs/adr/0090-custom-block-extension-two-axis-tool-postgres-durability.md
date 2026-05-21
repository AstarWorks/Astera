---
status: Accepted
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0090: Custom block extension — two-axis ToolType, per-block maxHp, content/blocks/ registry, Postgres durability

## Status

Accepted

## Context

Wave 5d (ADR-0033) shipped the first cut of Astera's block-damage substrate:
`BlockState` / `BlockMaterial` / `ToolType` (single-axis enum: `NONE / WOOD
/ STONE / IRON / DIAMOND`) / `BlockDurabilityRule` / `IBlockStateGateway` /
`BlockChangeJournal` (match-scoped, 5000-entry `ArrayDeque`, in-memory).
Wave 5m (ADR-0088) extended `DamageSource.Environment` with explosion AOE.
Both stopped short of the "skill→block damage + custom block YAML + place
handler" loop the user named as Astera's core block vision:

- **Vision**: obsidian-grade hardened soil / wood / stone that authors can
  declare in YAML, paired with a pickaxe/axe/shovel **rock-paper-scissors**
  damage matrix and a vanilla-equivalent tier ladder
  (wood/stone/iron/diamond/netherite) — i.e. an **Annihilation-style** mining
  axis bolted onto Astera's PvP / PvE / generative-world surface.
- **Gap inventory** (audit 2026-05-21):
  1. `ToolType` was single-axis, collapsing kind (pickaxe vs axe vs shovel)
     with tier (wood vs iron). The matrix could not encode "PICKAXE excels at
     STONE / AXE excels at WOOD / SHOVEL excels at SOIL".
  2. `BlockState.MAX_DURABILITY = 100` was a global constant. Custom blocks
     could not declare per-block max HP (no "5000 HP hardened soil").
  3. `content/blocks/` did not exist. Custom block types were hard-coded
     (`astera:core_block`, `astera:barrier_block`) — adding a new block
     required Kotlin.
  4. `ItemSpec` had no placement handle. An item could not be flagged "place
     me as block X" without a per-feature listener.
  5. `IBlockStateGateway` was in-memory only. Custom blocks placed in the
     open world were lost on server restart — direct conflict with the
     Wave _persist-1 (ADR-0068) cross-server persistence commitment.
  6. There was no skill→block damage entrypoint. The Wave 5d
     `applyDamage(world, pos, amount, tool)` path was tied to the BreakCore
     listener; the skill engine had nowhere to call.

Wave 5x closes all six gaps in one wave so the Annihilation-grade block
substrate ships before the Wave 6 Paper sweep starts wiring listeners.

## Decision

### 1. `ToolType` becomes a two-axis data class (`kind: ToolKind × tier: ToolTier`)

Per CLAUDE.md §Pre-release posture, forward-only — the Wave 5d enum is
replaced outright (no `@Deprecated` alias, no `typealias`):

```kotlin
public data class ToolType(val kind: ToolKind, val tier: ToolTier) {
    public companion object {
        public val NONE: ToolType = ToolType(ToolKind.NONE, ToolTier.NONE)
        public val WOODEN_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.WOOD)
        public val IRON_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.IRON)
        public val NETHERITE_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.NETHERITE)
        // … 15 named singletons + NONE
        public fun fromSerialKey(raw: String): ToolType?
    }
}
public enum class ToolKind { NONE, PICKAXE, AXE, SHOVEL }
public enum class ToolTier { NONE, WOOD, STONE, IRON, DIAMOND, NETHERITE }
```

Invariant: `kind == NONE` iff `tier == NONE` (only the empty-hand sentinel
is "both NONE"). Konsist enforces the two-axis shape (test:
`ToolType must be a two-axis data class (Wave 5x ADR-0090)`).

### 2. `BlockDurabilityRule` matrix is keyed by `(ToolKind, ToolTier, BlockMaterial)`

Matrix grows from 5 × 4 = 20 to 4 × 5 × 4 = 80 cells (with the empty-hand
`NONE × NONE` row contributing one slice). Default matrix encodes the
three-way commitment:

```
                  WOOD   STONE   SOIL   OTHER
PICKAXE × any    weak   strong   0      strong
AXE     × any   strong   weak    0      strong
SHOVEL  × any     0       0    strong   weak
NONE             5       0     10       5
```

Within each non-NONE kind, tier multiplies effectiveness following the
vanilla ladder:

| tier      | strong cell | weak cell |
|-----------|-------------|-----------|
| WOOD      | 25          | 5         |
| STONE     | 50          | 10        |
| IRON      | 100         | 25        |
| DIAMOND   | 200         | 50        |
| NETHERITE | 400         | 100       |

`content/balance/block-durability.yaml` adopts the
`"KIND/TIER"` serial key (`"PICKAXE/IRON": { stone: 100 }`); `"NONE"` is
the empty-hand sentinel.

### 3. `BlockState` carries per-instance `maxHp`

```kotlin
public data class BlockState(
    val blockType: BlockType,
    val durability: Int,
    val lastDamagedTick: Long,
    val damagedBy: PlayerId?,
    val maxHp: Int = DEFAULT_MAX_DURABILITY,  // Wave 5x
) {
    init {
        require(maxHp >= 1)
        require(durability in 0..maxHp)
    }
}
```

`DEFAULT_MAX_DURABILITY = 100` preserves Wave 5d semantics; custom blocks
set their own (e.g. 5000 for hardened soil). The `BlockStateAutoRecoveryUseCase`
regen path now caps against `state.maxHp` per entry instead of a global
constant. `restore(pos)` returns to `state.maxHp` (custom blocks restore to
their own tank value, not a fixed 100).

### 4. `content/blocks/<id>.yaml` registry + `ICustomBlockRegistry` port

```kotlin
public data class CustomBlockSpec(
    val id: BlockType,
    val displayName: LocalizedText,           // inline en/ja (ADR-0073 flavor)
    val baseBukkitMaterial: String,           // vanilla Material name for paper render
    val material: BlockMaterial,
    val baseDurability: Int,
    val toolEffectiveness: Map<ToolType, Int>? = null,  // per-block matrix override
    val drops: List<BlockDrop> = emptyList(),
    val placedFrom: ItemId? = null,
    val immuneTo: Set<BlockMaterial> = emptySet(),
    val texture: String? = null,              // opaque HUD glyph slug (Wave 5j stitch)
)
```

`ICustomBlockRegistry` extends `Contents<BlockType, CustomBlockSpec> +
ReloadableContents` and adds `resolveByItem(itemId)` (place-handler inverse).
Reload via `/astera reload blocks` (Wave _infra-2b ADR-0065 dispatcher).

Sample (`content/blocks/hardened-soil.yaml`):
```yaml
id: "astera:hardened_soil"
base_bukkit_material: "DIRT"
material: "SOIL"
base_durability: 5000
tool_effectiveness:
  "PICKAXE/WOOD": 1
  "PICKAXE/IRON": 50
  "PICKAXE/DIAMOND": 200
drops:
  - item_id: "hardened-soil-block"
    requires_tool: "PICKAXE/IRON"
placed_from: "hardened-soil-block"
```

### 5. `ItemSpec.placeableAs: BlockType?`

Adds one nullable field to `ItemSpec` (ADR-0073). Wave 6b's Paper
place-handler resolves item → spec via
`ICustomBlockRegistry.resolveByItem`. `null` = item is not a placement
source (vanilla behavior).

### 6. Skill→block damage entrypoint

```kotlin
public fun IBlockStateGateway.applyDamageFromSkill(
    world: WorldId,
    pos: Vec3,
    skillId: SkillId,
    attackerId: PlayerId,
    profile: BlockDamageProfile,
): Result<BlockDamageOutcome, BlockGatewayError>

public data class BlockDamageProfile(
    val baseAmount: Int,
    val effectiveTool: ToolType,
    val materialOverride: BlockMaterial? = null,
)

public sealed interface BlockDamageOutcome {
    public data class Damaged(val newState: BlockState, val damageDealt: Int) : BlockDamageOutcome
    public data class Broken(val drops: List<ItemId>, val change: BlockChange.Broken) : BlockDamageOutcome
    public data class Ineffective(val reason: IneffectiveReason) : BlockDamageOutcome
}
public sealed interface IneffectiveReason {
    public data object WrongTool : IneffectiveReason
    public data object IndestructibleBlock : IneffectiveReason
    public data object ImmuneByMaterial : IneffectiveReason
    public data object ZeroDamageCell : IneffectiveReason
}
```

`InMemoryBlockStateGateway`:
- Looks up the per-block `toolEffectiveness` cell **before** falling through
  to `BlockDurabilityRule`.
- Scales by `baseAmount / DEFAULT_BASE` (DEFAULT_BASE = 100) so authors can
  balance per-cell values around a 100-amount baseline.
- Resolves drops per `BlockDrop.requiresTool` (kind match + tier ≥) and
  `dropChance` (independent roll, then `countRange`).
- Publishes `BlockDamagedBySkill` (HP > 0) or `BlockBrokenBySkill` (HP = 0).

`DamageSource.Block(world, position, blockType, tool, attackerId)` added so
back-damage from crumbling blocks can flow into the standard
`DamageAttempt` pipeline (`attackerIdOrNull` extended;
`KnockbackModifier.originPositionFor` returns `block.position`).

### 7. `IBlockDurabilityRepository` + V20 migration (cross-restart persistence)

```kotlin
public interface IBlockDurabilityRepository {
    public fun loadAll(world: WorldId): Result<Map<Vec3, BlockState>, BlockGatewayError>
    public fun upsertAll(world: WorldId, states: Map<Vec3, BlockState>): Result<Unit, BlockGatewayError>
    public fun deletePosition(world: WorldId, pos: Vec3): Result<Unit, BlockGatewayError>
    public fun deletePositions(world: WorldId, positions: Collection<Vec3>): Result<Unit, BlockGatewayError>
}
```

Priority-100 overlay pattern (ADR-0068):
- `InMemoryBlockDurabilityRepository` registered by `AsteraCorePlugin` at
  priority 0 (no-op when Postgres absent).
- `PostgresBlockDurabilityRepository` registered by `AsteraPersistencePlugin`
  at priority 100 when `ASTERA_PG_URL` is set.

V20 migration (`block_durability` table):
```sql
CREATE TABLE block_durability (
    world_id          VARCHAR(64) NOT NULL,
    pos_x             INTEGER NOT NULL,
    pos_y             INTEGER NOT NULL,
    pos_z             INTEGER NOT NULL,
    block_type        VARCHAR(128) NOT NULL,
    current_hp        INTEGER NOT NULL CHECK (current_hp >= 0),
    max_hp            INTEGER NOT NULL CHECK (max_hp >= 1),
    last_damaged_tick BIGINT NOT NULL CHECK (last_damaged_tick >= 0),
    damaged_by        UUID NULL,
    PRIMARY KEY (world_id, pos_x, pos_y, pos_z)
);
CREATE INDEX idx_block_durability_world ON block_durability (world_id);
CREATE INDEX idx_block_durability_world_type ON block_durability (world_id, block_type);
```

`Vec3` doubles are truncated to `INTEGER`; block placement is integral, the
in-memory tracker performs the same truncation at place time.

### 8. Three new domain events

- `BlockDamagedBySkill(world, position, skillId, attackerId, blockType, amount, newDurability, atTick)`
- `BlockBrokenBySkill(world, position, skillId, attackerId, blockType, drops, atTick)`
- `CustomBlockPlaced(world, position, blockType, placedBy, atTick)`

All three are open-world-scoped (no `matchId`) — custom blocks live both in
match arenas and the persistent open world. Wave 5d's `BlockBroken` /
`BlockPlaced` events (match-scoped) remain for the listener-driven path
(BukkitEventAdapter break events flow through them).

### 9. Two new Konsist rules

- `ToolType must be a two-axis data class (Wave 5x ADR-0090)` — rejects a
  regression to enum or to a different parameter set.
- `IBlockDurabilityRepository requires priority 100 Postgres overlay (Wave 5x)` —
  pins `PostgresBlockDurabilityRepository`'s FQN + implementation contract.

Total Konsist rules: 28 (Wave 5g baseline) → 30.

## Consequences

### Positive
- **Vision shipped**: pickaxe / axe / shovel rock-paper-scissors against
  wood / stone / soil materials, with 5-tier vanilla ladder
  (wood→netherite), in one self-consistent matrix.
- **Custom blocks are data**: dropping `content/blocks/<id>.yaml` is
  enough to add a new placeable, damageable, lootable block — no Kotlin
  required. `/astera reload blocks` swaps live.
- **Custom blocks persist**: server restart preserves every player-placed
  custom block + its current HP + max HP, via the existing ADR-0068
  Postgres-overlay machinery (V20 follows the V15-V19 pattern exactly).
- **Skill engine has a typed block path**: `applyDamageFromSkill` returns a
  sealed outcome (Damaged / Broken / Ineffective) so callers exhaustively
  handle every case at compile time.
- **Drop gates respect tools**: `BlockDrop.requiresTool` matches kind +
  tier-ordinal — authors can say "only iron+ pickaxe drops the
  hardened-soil item" without bespoke per-block code.

### Negative / Trade-offs
- **Wave 5d → 5x is a breaking change**: every `ToolType.IRON` /
  `ToolType.WOOD` / etc. callsite was renamed (`ToolType.IRON_PICKAXE` /
  `WOODEN_PICKAXE` companion); `BlockDurabilityYamlConfig` YAML schema
  changed key shape (`wood:` → `"PICKAXE/WOOD":`). Per CLAUDE.md §Pre-
  release posture this is intentional — no dual-decoder, no alias enum.
- **Matrix grew 4×**: 20 → 80 cells. Authors will normally cover ~12 cells
  (3 non-NONE kinds × 5 tiers × a few materials they care about), with the
  rest falling through to `DEFAULT_DAMAGE`.
- **`maxHp` defaults to 100**: vanilla / unknown blocks behave exactly as
  Wave 5d, but call sites that previously copied a `BlockState` via
  `copy(durability = …)` must now consider `maxHp` if the block is custom.
  `InMemoryBlockStateGateway` handles this internally; the only external
  affected site (`BlockStateAutoRecoveryUseCase.tick`) was updated.
- **No Paper place handler yet**: `ItemSpec.placeableAs` is wired to the
  domain + gateway but no Bukkit listener consumes it. That's Wave 6b
  (PaperCustomBlockAdapter) — the spec lives in the registry but a player
  right-clicking a placement item on a block in vanilla play does nothing
  until Wave 6b lands.

### Mitigations
- **Forward-only migration**: every internal test was rewritten in the
  same commit; `RestoreBlocksOnMatchEndedUseCaseTest`'s `RecordingGateway`
  fake added stubs for the two new methods.
- **Konsist guards regression**: the two new rules catch enum-collapse +
  Postgres-overlay drift before merge.
- **Sample YAML shipped**: `content/blocks/hardened-soil.yaml` is the
  end-to-end smoke test for the authoring flow.
- **Wave 6b commitment**: the `PaperCustomBlockAdapter` (BlockPlaceEvent /
  BlockBreakEvent hijack) is on the Wave 6 Paper-sweep handoff per the
  scope note in `wave-5g-core-hardening-giggly-sunset.md` §"Wave 5x".

## Alternatives Considered

- **Keep `ToolType` enum, add `BlockToolFamily` sibling enum**:
  Splits the matrix lookup into two passes (kind → family → cell). Rejected
  because authors would need to read two enums + a mapping to predict damage;
  the two-axis data class makes "PICKAXE/IRON" a single named value.

- **Per-block-type Postgres table (`hardened_soil`, `granite`, …)**:
  Rejected — schema explosion + future content additions would require
  migrations. The single `block_durability` table with `block_type
  VARCHAR(128)` keeps the schema stable as authors add YAML.

- **Keep `BlockState.MAX_DURABILITY` as a global constant + per-block
  `damageMultiplier`**:
  Rejected — the multiplier still ends up at `MAX_DURABILITY × multiplier`
  which is the same as a per-instance `maxHp`. Storing `maxHp` directly
  avoids a multiply at every cap check and matches how authors think
  ("this block has 5000 HP").

- **Use the Wave 5d `BlockChangeJournal` for persistence**:
  Rejected — the journal is match-scoped + bounded (5000 entries with FIFO
  eviction). Custom blocks placed in the open world need durable,
  unbounded state. The two systems coexist: journal handles per-match
  restore (still in-memory), `IBlockDurabilityRepository` handles
  cross-restart durability.

- **Defer custom blocks to Wave 6 Paper sweep**:
  Rejected — would land the Paper adapter and the domain in the same wave,
  producing a worse design (the domain shape would be steered by Paper
  ergonomics). Wave 5x lands the domain + content + persistence cleanly so
  Wave 6 only writes the Bukkit listener.

## References

- Related ADRs: [[adr/0033-block-state-and-recovery]] (Wave 5d origin),
  [[adr/0058-universal-attribute-container]] (why BlockState is not an
  attribute container), [[adr/0068-cross-server-persistence-boundary]]
  (priority-100 overlay pattern), [[adr/0073-item-centric-content-model-and-skill-binding]]
  (ItemSpec model that `placeableAs` extends),
  [[adr/0065-per-category-reload-dispatcher]] (`/astera reload blocks`),
  [[adr/0088-combat-aoe-and-environment-damage-source]] (Wave 5m AOE that
  precedes the skill→block path).
- Related docs: [[architecture/principles]] (§12.4 single damage event),
  `CLAUDE.md` §Pre-release posture (forward-only justification).
- External: Annihilation game mode for the rock-paper-scissors mining
  inspiration.
