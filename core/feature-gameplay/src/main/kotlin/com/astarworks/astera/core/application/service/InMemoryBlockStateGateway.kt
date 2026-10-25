package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.block.IneffectiveReason
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.event.BlockDamagedBySkill
import com.astarworks.astera.core.domain.event.CustomBlockPlaced
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockChange
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.random.Random

/**
 * Default in-memory [IBlockStateGateway] (Wave 5d → Wave 5x, ADR-0090).
 *
 * Phase 1 PoC: a `Map<WorldId, MutableMap<Vec3, BlockState>>` tracks every
 * damaged or placed position. Untracked positions return
 * [BlockState.DEFAULT_MAX_DURABILITY] from [getDurability] — call sites never
 * need to pre-register stage geometry; the first damage event triggers
 * tracking.
 *
 * **Lifecycle of a tracked entry**:
 * - First [applyDamage] on an untracked position seeds a fresh [BlockState]
 *   at DEFAULT_MAX_DURABILITY then deducts the damage.
 * - [track] writes the supplied state verbatim — used by placed blocks
 *   ([com.astarworks.astera.core.domain.event.BlockPlaced] listeners) and by
 *   the pseudo-recovery scheduler to bump durability back up.
 * - [placeCustomBlock] seeds at `spec.baseDurability` for both current HP
 *   and `maxHp`, replacing any prior tracker entry at the position.
 * - [restore] resets the tracked entry to its `maxHp` (preserves
 *   `blockType` so pseudo-recovery can keep iterating). Untracked positions
 *   are a no-op.
 *
 * **Event publishing** (Wave 5x):
 *  - [applyDamageFromSkill] publishes `BlockDamagedBySkill` (HP reduced,
 *    block intact) or `BlockBrokenBySkill` (HP hit 0) via [eventBus].
 *  - [placeCustomBlock] publishes `CustomBlockPlaced`.
 *  - The Wave-5d [applyDamage] entrypoint still does NOT publish events —
 *    that path is for listener-driven raw damage where the upstream
 *    producer owns the "did it break" decision.
 *
 * **Not thread-safe** — Astera invariant: all match-state mutation runs on
 * the server main thread (per `IScheduler` regional contract). The plain
 * `MutableMap` is intentional; concurrent collections would imply support we
 * do not provide.
 */
public class InMemoryBlockStateGateway(
    private val eventBus: IDomainEventBus? = null,
    private val customBlockRegistry: ICustomBlockRegistry? = null,
    private val durabilityRule: BlockDurabilityRule = BlockDurabilityRule.defaultMatrix(),
    private val random: Random = Random.Default,
    private val initialTick: () -> Long = { 0L },
) : IBlockStateGateway {

    private val worlds: MutableMap<WorldId, MutableMap<Vec3, BlockState>> = mutableMapOf()

    override fun getDurability(world: WorldId, pos: Vec3): Int =
        worlds[world]?.get(pos)?.durability ?: BlockState.DEFAULT_MAX_DURABILITY

    override fun applyDamage(
        world: WorldId,
        pos: Vec3,
        amount: Int,
        tool: ToolType,
    ): BlockState {
        val store = worlds.getOrPut(world) { mutableMapOf() }
        val existing = store[pos]
        val current = existing?.durability ?: BlockState.DEFAULT_MAX_DURABILITY
        val maxHp = existing?.maxHp ?: BlockState.DEFAULT_MAX_DURABILITY
        // amount may be negative or zero on caller error — clamp damage to >= 0
        // so we never accidentally heal via applyDamage. Healing is restore() / track().
        val safeAmount = amount.coerceAtLeast(0)
        val nextDurability = (current - safeAmount).coerceIn(0, maxHp)
        val nextState = (existing ?: defaultStateFor(pos)).copy(
            durability = nextDurability,
            lastDamagedTick = initialTick(),
        )
        store[pos] = nextState
        // tool is currently passed through unchanged — BlockDurabilityRule
        // application happens upstream of the gateway in Wave 5d for this
        // entrypoint; Wave 5x's applyDamageFromSkill is the matrix-aware
        // entrypoint.
        @Suppress("UNUSED_PARAMETER") tool
        return nextState
    }

    override fun applyDamageFromSkill(
        world: WorldId,
        pos: Vec3,
        skillId: SkillId,
        attackerId: PlayerId,
        profile: BlockDamageProfile,
    ): Result<BlockDamageOutcome, BlockGatewayError> {
        val store = worlds.getOrPut(world) { mutableMapOf() }
        val existing = store[pos]
        val blockType = existing?.blockType ?: UNKNOWN_BLOCK_TYPE
        val customSpec = customBlockRegistry?.resolve(blockType)
        val effectiveMaterial = profile.materialOverride
            ?: customSpec?.material
            ?: durabilityRule.materialOf(blockType)

        if (customSpec != null && effectiveMaterial in customSpec.immuneTo) {
            return Result.success(
                BlockDamageOutcome.Ineffective(IneffectiveReason.ImmuneByMaterial),
            )
        }

        val cellDamage = resolveCellDamage(profile.effectiveTool, effectiveMaterial, customSpec)
        if (cellDamage == 0) {
            val reason = if (profile.effectiveTool.kind == ToolKind.NONE) {
                IneffectiveReason.WrongTool
            } else {
                IneffectiveReason.ZeroDamageCell
            }
            return Result.success(BlockDamageOutcome.Ineffective(reason))
        }

        // Scale by the skill's baseAmount so authors can balance per-cell
        // values around DEFAULT_BASE (= 100).
        val scaled = (cellDamage.toLong() * profile.baseAmount / BlockDamageProfile.DEFAULT_BASE)
            .toInt()
            .coerceAtLeast(0)
        if (scaled == 0) {
            return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        }

        val maxHp = existing?.maxHp ?: (customSpec?.baseDurability ?: BlockState.DEFAULT_MAX_DURABILITY)
        val currentHp = existing?.durability ?: maxHp
        val nextHp = (currentHp - scaled).coerceIn(0, maxHp)
        val nextState = (existing ?: defaultStateFor(pos).copy(
            blockType = if (customSpec != null) customSpec.id else UNKNOWN_BLOCK_TYPE,
            maxHp = maxHp,
            durability = maxHp,
        )).copy(
            durability = nextHp,
            lastDamagedTick = initialTick(),
            damagedBy = attackerId,
        )
        store[pos] = nextState

        return if (nextHp == 0) {
            val drops = resolveDrops(customSpec, profile.effectiveTool)
            val change = BlockChange.Broken(
                position = pos,
                prevBlockType = nextState.blockType,
                byPlayer = attackerId,
                atTick = initialTick(),
            )
            eventBus?.publish(
                BlockBrokenBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = nextState.blockType,
                    drops = drops,
                    atTick = initialTick(),
                ),
            )
            Result.success(BlockDamageOutcome.Broken(drops, change))
        } else {
            eventBus?.publish(
                BlockDamagedBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = nextState.blockType,
                    amount = scaled,
                    newDurability = nextHp,
                    atTick = initialTick(),
                ),
            )
            Result.success(BlockDamageOutcome.Damaged(nextState, scaled))
        }
    }

    override fun restore(world: WorldId, pos: Vec3) {
        val store = worlds[world] ?: return
        val existing = store[pos] ?: return
        store[pos] = existing.copy(
            durability = existing.maxHp,
            // Don't bump lastDamagedTick on restore — the field's only consumer
            // is the cool-off scheduler, which gates on (now - lastDamagedTick).
        )
    }

    override fun track(world: WorldId, pos: Vec3, state: BlockState) {
        val store = worlds.getOrPut(world) { mutableMapOf() }
        store[pos] = state
    }

    override fun placeCustomBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError> {
        val state = BlockState(
            blockType = spec.id,
            durability = spec.baseDurability,
            lastDamagedTick = initialTick(),
            damagedBy = null,
            maxHp = spec.baseDurability,
        )
        val store = worlds.getOrPut(world) { mutableMapOf() }
        store[pos] = state
        eventBus?.publish(
            CustomBlockPlaced(
                world = world,
                position = pos,
                blockType = spec.id,
                placedBy = placedBy,
                atTick = initialTick(),
            ),
        )
        return Result.success(state)
    }

    override fun tracked(world: WorldId): Map<Vec3, BlockState> =
        worlds[world]?.toMap() ?: emptyMap()

    /**
     * Resolve the cell damage from (tool, material), consulting the per-block
     * `toolEffectiveness` override first (Wave 5x), then the global
     * [BlockDurabilityRule].
     */
    private fun resolveCellDamage(
        tool: ToolType,
        material: BlockMaterial,
        spec: CustomBlockSpec?,
    ): Int {
        val override = spec?.toolEffectiveness?.get(tool)
        if (override != null) return override
        return durabilityRule.damageFor(tool, material)
    }

    /**
     * Walk [spec.drops], rolling each drop's `dropChance` and `countRange`,
     * gating on `requiresTool` against [tool]. Returns the flattened
     * [ItemId] list (one entry per dropped instance, so the gateway caller
     * can spawn N item entities directly).
     */
    private fun resolveDrops(spec: CustomBlockSpec?, tool: ToolType): List<ItemId> {
        if (spec == null) return emptyList()
        return buildList {
            for (drop in spec.drops) {
                if (drop.requiresTool != null && !toolSatisfies(tool, drop.requiresTool)) continue
                if (random.nextFloat() > drop.dropChance) continue
                val count = if (drop.countRange.first == drop.countRange.last) {
                    drop.countRange.first
                } else {
                    random.nextInt(drop.countRange.first, drop.countRange.last + 1)
                }
                repeat(count) { add(drop.itemId) }
            }
        }
    }

    /**
     * `tool` satisfies `gate` when the kind matches AND its tier ordinal is
     * at least the gate's tier ordinal. NONE → NONE only satisfies a
     * NONE gate.
     */
    private fun toolSatisfies(tool: ToolType, gate: ToolType): Boolean {
        if (gate.kind == ToolKind.NONE) return tool.kind == ToolKind.NONE
        if (tool.kind != gate.kind) return false
        return tool.tier.ordinal >= gate.tier.ordinal
    }

    /**
     * Seed a [BlockState] for a position that was previously untracked.
     *
     * Phase 1 PoC: we don't know what the original block was (no world-read
     * port wired yet), so we use a placeholder [BlockType] `astera:unknown`.
     * Wave 6's `PaperBlockStateGateway` will read the actual `BlockData` from
     * the world and supply the real type.
     */
    private fun defaultStateFor(@Suppress("UNUSED_PARAMETER") pos: Vec3): BlockState =
        BlockState(
            blockType = UNKNOWN_BLOCK_TYPE,
            durability = BlockState.DEFAULT_MAX_DURABILITY,
            lastDamagedTick = 0L,
            damagedBy = null,
            maxHp = BlockState.DEFAULT_MAX_DURABILITY,
        )

    @Suppress("unused")
    private fun referenceUnusedAxes(): Pair<ToolKind, ToolTier> =
        ToolKind.NONE to ToolTier.NONE

    private companion object {
        private val UNKNOWN_BLOCK_TYPE = BlockType("astera:unknown")
    }
}
