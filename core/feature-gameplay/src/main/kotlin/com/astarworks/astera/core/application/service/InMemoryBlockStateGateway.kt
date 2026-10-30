package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.BalanceRef
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

/** Simulator block identity/placement metadata plus the same sparse HP store that Paper uses. */
public class InMemoryBlockStateGateway(
    private val eventBus: IDomainEventBus? = null,
    private val customBlockRegistry: ICustomBlockRegistry? = null,
    private val durabilityRef: BalanceRef<BlockDurabilityRule> = BalanceRef(BlockDurabilityRule.defaultMatrix()),
    private val random: Random = Random.Default,
    private val worldGateway: (() -> com.astarworks.astera.core.application.port.outbound.IWorldGateway?)? = null,
    private val initialTick: () -> Long = { 0L },
) : IBlockStateGateway {
    private val damage = com.astarworks.astera.core.application.service.BlockHpStore()
    override fun damagedWorlds(): Set<WorldId> = damage.worlds()
    override fun damageBlock(world: WorldId, pos: Vec3, maxHp: Int, amount: Int): Int =
        damage.hit(world, cellOf(pos), maxHp, amount, initialTick())
    override fun hasDamage(world: WorldId, pos: Vec3): Boolean = damage.damage(world, cellOf(pos)) > 0
    override fun clearDamage(world: WorldId, pos: Vec3) { damage.clear(world, cellOf(pos)) }
    override fun expireDamage(world: WorldId, currentTick: Long): List<Vec3> = damage.expire(world, currentTick, durabilityRef.current.idleTicks)
    override fun clearWorldDamage(world: WorldId): List<Vec3> = damage.clearWorld(world)


    private val worlds: MutableMap<WorldId, MutableMap<Vec3, BlockType>> = mutableMapOf()

    private val broken = mutableMapOf<WorldId, MutableSet<Vec3>>()

    /** Positions of the temporary blocks placed during a match (ADR-0280); [resetCover] removes them. */
    private val temporary: MutableMap<WorldId, MutableSet<Vec3>> = mutableMapOf()

    override fun getDurability(world: WorldId, pos: Vec3): Int =
        (stateAt(world, pos).durability - damage.damage(world, cellOf(pos))).coerceAtLeast(0)

    override fun applyDamageFromSkill(
        world: WorldId,
        pos: Vec3,
        skillId: SkillId,
        attackerId: PlayerId,
        profile: BlockDamageProfile,
    ): Result<BlockDamageOutcome, BlockGatewayError> {
        val existing = stateAt(world, pos)
        if (existing.durability <= 0) return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        val blockType = existing.blockType
        val customSpec = customBlockRegistry?.resolve(blockType)
        val effectiveMaterial = profile.materialOverride
            ?: customSpec?.material
            ?: durabilityRef.current.materialOf(blockType)

        if (customSpec != null && effectiveMaterial in customSpec.immuneTo) {
            return Result.success(
                BlockDamageOutcome.Ineffective(IneffectiveReason.ImmuneByMaterial),
            )
        }

        val scaled: Int
        if (profile.flat) {
            scaled = profile.baseAmount
        } else {
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
            scaled = (cellDamage.toLong() * profile.baseAmount / BlockDamageProfile.DEFAULT_BASE)
                .toInt()
                .coerceAtLeast(0)
        }
        if (scaled == 0) {
            return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        }

        val maxHp = existing.maxHp
        val nextHp = damageBlock(world, pos, maxHp, scaled)
        val nextState = existing.copy(
            durability = nextHp,
            lastDamagedTick = initialTick(),
            damagedBy = attackerId,
        )

        return if (nextHp == 0) {
            broken.getOrPut(world) { mutableSetOf() }.add(cellOf(pos))
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

    override fun restore(world: WorldId, pos: Vec3) { clearDamage(world, pos) }

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
        store[cellOf(pos)] = spec.id
        broken[world]?.remove(cellOf(pos))
        clearDamage(world, pos)
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

    override fun tracked(world: WorldId): Map<Vec3, BlockState> = damage.snapshot(world).mapValues { (pos, hit) ->
        val seed = stateAt(world, pos)
        seed.copy(durability = seed.maxHp - hit.damage, lastDamagedTick = hit.lastHitTick)
    }

    override fun isCover(world: WorldId, pos: Vec3): Boolean =
        cellOf(pos) !in broken[world].orEmpty() && customBlockRegistry?.resolve(stateAt(world, pos).blockType) != null

    override fun canPlaceAt(world: WorldId, pos: Vec3): Boolean =
        worlds[world]?.containsKey(cellOf(pos)) != true || cellOf(pos) in broken[world].orEmpty()

    override fun placeTemporaryBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError> {
        val cell = cellOf(pos)
        val placed = placeCustomBlock(world, cell, spec, placedBy)
        if (placed is Result.Success) temporary.getOrPut(world) { mutableSetOf() }.add(cell)
        return placed
    }

    override fun resetCover(world: WorldId, centre: Vec3?, halfExtent: Double): List<Vec3> {
        val store = worlds[world] ?: return clearWorldDamage(world)
        val temp = temporary[world]
        val changed = clearWorldDamage(world).toMutableList()
        for (pos in store.keys.toList()) {
            if (centre != null && (kotlin.math.abs(pos.x - centre.x) > halfExtent || kotlin.math.abs(pos.z - centre.z) > halfExtent)) continue
            if (temp != null && pos in temp) {
                store.remove(pos)
                temp.remove(pos)
                changed += pos
            } else if (broken[world]?.remove(pos) == true) {
                changed += pos
            }
        }
        return changed
    }

    private fun cellOf(pos: Vec3): Vec3 =
        Vec3(kotlin.math.floor(pos.x), kotlin.math.floor(pos.y), kotlin.math.floor(pos.z))

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
        return durabilityRef.current.damageFor(tool, material)
    }

    /**
     * Walk [spec.drops], rolling each drop's `dropChance` and `countRange`,
     * gating on `requiresTool` against [tool]. Returns the flattened
     * [ItemId] list (one entry per dropped instance, so the gateway caller
     * can spawn N item entities directly).
     */
    private fun resolveDrops(spec: CustomBlockSpec?, tool: ToolType): List<ItemId> {
        if (spec == null) return emptyList()
        if (spec.visual != null && spec.placedFrom != null) return listOf(spec.placedFrom)
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

    private fun stateAt(world: WorldId, pos: Vec3): BlockState {
        val physical = worlds[world]?.get(cellOf(pos)) ?: worldGateway?.invoke()?.blockAt(world, pos) ?: UNKNOWN_BLOCK_TYPE
        val type = customBlockRegistry?.resolve(physical)?.id ?: customBlockRegistry?.resolveByBaseMaterial(physical.identifier.uppercase())?.id ?: physical
        val maxHp = customBlockRegistry?.resolve(type)?.baseDurability ?: com.astarworks.astera.core.domain.world.BlockHpRules.maxHp(com.astarworks.astera.core.domain.world.BlockHpRules.simulatedHardness(type))
        return BlockState(type, if (cellOf(pos) in broken[world].orEmpty()) 0 else maxHp, 0L, null, maxHp)
    }

    private companion object {
        private val UNKNOWN_BLOCK_TYPE = BlockType("astera:unknown")
    }
}
