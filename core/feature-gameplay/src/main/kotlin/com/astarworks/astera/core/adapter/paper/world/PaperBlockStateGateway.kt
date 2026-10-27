package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
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
import com.astarworks.astera.core.domain.world.BlockKey
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Block
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

/** Paper world I/O for ADR-0297. Damage lives only in the platform-free sparse store. */
public class PaperBlockStateGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val customBlockRegistry: ICustomBlockRegistry? = null,
    private val durabilityRule: BlockDurabilityRule = BlockDurabilityRule.defaultMatrix(),
    private val random: Random = Random.Default,
    /** Called just before this gateway edits the world (break), so an arena slot journal can remember the block. */
    private val beforeWorldEdit: (Block) -> Unit = {},
) : IBlockStateGateway {
    private val damage = com.astarworks.astera.core.application.service.BlockHpStore()
    override fun damagedWorlds(): Set<WorldId> = damage.worlds()
    override fun damageBlock(world: WorldId, pos: Vec3, maxHp: Int, amount: Int): Int =
        damage.hit(world, cellOf(pos), maxHp, amount, scheduler.currentTick())
    override fun hasDamage(world: WorldId, pos: Vec3): Boolean = damage.damage(world, cellOf(pos)) > 0
    override fun clearDamage(world: WorldId, pos: Vec3) { damage.clear(world, cellOf(pos)) }
    override fun expireDamage(world: WorldId, currentTick: Long): List<Vec3> = damage.expire(world, currentTick, durabilityRule.idleTicks)
    override fun clearWorldDamage(world: WorldId): List<Vec3> = damage.clearWorld(world)


    private fun cellOf(pos: Vec3): Vec3 = Vec3(floor(pos.x), floor(pos.y), floor(pos.z))

    private val log = LoggerFactory.getLogger(javaClass)

    /** Temporary blocks placed during a match (ADR-0280); [resetCover] clears them. */
    private val temporaryKeys: MutableSet<BlockKey> = ConcurrentHashMap.newKeySet()

    override fun getDurability(world: WorldId, pos: Vec3): Int {
        val block = resolveBlock(world, pos) ?: return BlockState.DEFAULT_MAX_DURABILITY
        if (block.type.isAir) return 0
        val spec = customBlockRegistry?.resolveByBaseMaterial(block.type.name)
        val maxHp = spec?.baseDurability ?: com.astarworks.astera.core.domain.world.BlockHpRules.maxHp(block.type.hardness.toDouble())
        return (maxHp - damage.damage(world, cellOf(pos))).coerceAtLeast(0)
    }

    override fun applyDamageFromSkill(
        world: WorldId,
        pos: Vec3,
        skillId: SkillId,
        attackerId: PlayerId,
        profile: BlockDamageProfile,
    ): Result<BlockDamageOutcome, BlockGatewayError> {
        val block = resolveBlock(world, pos)
            ?: return Result.failure(
                BlockGatewayError.Storage("world or chunk unavailable for ($world, $pos)"),
            )
        if (block.type.isAir || block.type.hardness < 0 || block.type == Material.BARRIER || block.type == Material.BEDROCK)
            return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        val customSpec = customBlockRegistry?.resolveByBaseMaterial(block.type.name)
        val blockType = customSpec?.id ?: bukkitBlockTypeOf(block)
        val effectiveMaterial = profile.materialOverride
            ?: customSpec?.material
            ?: durabilityRule.materialOf(blockType)

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
            scaled = (cellDamage.toLong() * profile.baseAmount / BlockDamageProfile.DEFAULT_BASE)
                .toInt()
                .coerceAtLeast(0)
        }
        if (scaled == 0) {
            return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        }

        val maxHp = customSpec?.baseDurability ?: com.astarworks.astera.core.domain.world.BlockHpRules.maxHp(block.type.hardness.toDouble())
        val nextHp = damageBlock(world, pos, maxHp, scaled)
        val seeded = defaultStateFor(world, pos).copy(
            blockType = if (customSpec != null) customSpec.id else blockType,
            maxHp = maxHp,
            durability = maxHp,
        )
        val nextState = seeded.copy(
            durability = nextHp,
            lastDamagedTick = scheduler.currentTick(),
            damagedBy = attackerId,
        )

        return if (nextHp == 0) {
            val drops = resolveDrops(customSpec, profile.effectiveTool)
            // World-side break: clear to AIR so the player sees the block
            // disappear. The in-memory impl skips this; only the Paper
            // adapter knows about the actual Bukkit world.
            beforeWorldEdit(block)
            block.type = Material.AIR
            // Drop the tracker entry — restoring a broken position later
            // would resurrect it at full HP without a placement event,
            // which is incorrect.
            val change = BlockChange.Broken(
                position = pos,
                prevBlockType = seeded.blockType,
                byPlayer = attackerId,
                atTick = scheduler.currentTick(),
            )
            eventBus.publish(
                BlockBrokenBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = seeded.blockType,
                    drops = drops,
                    atTick = scheduler.currentTick(),
                ),
            )
            Result.success(BlockDamageOutcome.Broken(drops, change))
        } else {
            eventBus.publish(
                BlockDamagedBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = nextState.blockType,
                    amount = scaled,
                    newDurability = nextHp,
                    atTick = scheduler.currentTick(),
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
    ): Result<BlockState, BlockGatewayError> = place(world, pos, spec, placedBy, temporaryBlock = false)

    override fun placeTemporaryBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError> = place(world, pos, spec, placedBy, temporaryBlock = true)

    private fun place(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
        temporaryBlock: Boolean,
    ): Result<BlockState, BlockGatewayError> {
        val block = resolveBlock(world, pos)
            ?: return Result.failure(
                BlockGatewayError.Storage("world or chunk unavailable for ($world, $pos)"),
            )
        val material = Material.matchMaterial(spec.baseBukkitMaterial)
            ?: return Result.failure(
                BlockGatewayError.Storage(
                    "Material.matchMaterial returned null for '${spec.baseBukkitMaterial}' " +
                        "(spec ${spec.id})",
                ),
            )
        // World-side swap first — if Bukkit rejects the placement we don't
        // want the tracker to claim the block is placed. The arena slot journal remembers what stood here first,
        // so the arena repair after the match brings it back.
        beforeWorldEdit(block)
        block.type = material
        if (temporaryBlock) temporaryKeys += BlockKey.of(world, pos)

        val state = BlockState(
            blockType = spec.id,
            durability = spec.baseDurability,
            lastDamagedTick = scheduler.currentTick(),
            damagedBy = null,
            maxHp = spec.baseDurability,
        )
        clearDamage(world, pos)
        eventBus.publish(
            CustomBlockPlaced(
                world = world,
                position = pos,
                blockType = spec.id,
                placedBy = placedBy,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(state)
    }

    override fun isCover(world: WorldId, pos: Vec3): Boolean {
        val block = resolveBlock(world, pos) ?: return false
        return customBlockRegistry?.resolveByBaseMaterial(block.type.name) != null
    }

    override fun canPlaceAt(world: WorldId, pos: Vec3): Boolean =
        resolveBlock(world, pos)?.type?.isAir == true

    override fun resetCover(world: WorldId, centre: Vec3?, halfExtent: Double): List<Vec3> {
        val changed = clearWorldDamage(world).toMutableList()
        for (key in temporaryKeys.toList()) {
            if (key.worldId != world.value) continue
            if (centre != null && (kotlin.math.abs(key.x - centre.x) > halfExtent || kotlin.math.abs(key.z - centre.z) > halfExtent)) continue
            temporaryKeys.remove(key)
            val bukkitWorld = server.getWorld(world.value) ?: continue
            val block = bukkitWorld.getBlockAt(key.x, key.y, key.z)
            if (!block.type.isAir) block.type = Material.AIR
            val at = Vec3(key.x.toDouble(), key.y.toDouble(), key.z.toDouble())
            if (at !in changed) changed += at
        }
        return changed
    }

    override fun tracked(world: WorldId): Map<Vec3, BlockState> = damage.snapshot(world).mapValues { (pos, hit) ->
        val block = resolveBlock(world, pos)
        val spec = block?.let { customBlockRegistry?.resolveByBaseMaterial(it.type.name) }
        val maxHp = spec?.baseDurability ?: com.astarworks.astera.core.domain.world.BlockHpRules.maxHp(block?.type?.hardness?.toDouble() ?: 1.0)
        BlockState(spec?.id ?: block?.let(::bukkitBlockTypeOf) ?: UNKNOWN_BLOCK_TYPE, maxHp - hit.damage, hit.lastHitTick, null, maxHp)
    }

    /**
     * Resolve a Bukkit [Block] from `(worldId, pos)`. Returns null when the
     * world is unknown or unloaded — callers translate that into a typed
     * `BlockGatewayError` or a safe default depending on the entrypoint
     * contract.
     */
    private fun resolveBlock(world: WorldId, pos: Vec3): Block? {
        val bukkitWorld = server.getWorld(world.value)
        if (bukkitWorld == null) {
            log.debug("PaperBlockStateGateway: world {} not loaded", world)
            return null
        }
        return bukkitWorld.getBlockAt(
            floor(pos.x).toInt(),
            floor(pos.y).toInt(),
            floor(pos.z).toInt(),
        )
    }

    /** Walk the per-block-spec override first, then the global rule. */
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
     * Mirror of `InMemoryBlockStateGateway.resolveDrops`: tool-gate filter +
     * `dropChance` roll + `countRange` sample, flattened to a per-instance
     * `ItemId` list so the caller spawns N item entities directly.
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

    private fun toolSatisfies(tool: ToolType, gate: ToolType): Boolean {
        if (gate.kind == ToolKind.NONE) return tool.kind == ToolKind.NONE
        if (tool.kind != gate.kind) return false
        return tool.tier.ordinal >= gate.tier.ordinal
    }

    /**
     * Seed a fresh [BlockState] for an untracked position. The Paper adapter
     * can read the actual Bukkit material at the position, so we feed the
     * real [BlockType] in (unlike the in-memory impl which uses an
     * `astera:unknown` placeholder).
     */
    private fun defaultStateFor(world: WorldId, pos: Vec3): BlockState {
        val block = resolveBlock(world, pos)
        val type = if (block != null) bukkitBlockTypeOf(block) else UNKNOWN_BLOCK_TYPE
        val maxHp = block?.let { customBlockRegistry?.resolveByBaseMaterial(it.type.name)?.baseDurability ?: com.astarworks.astera.core.domain.world.BlockHpRules.maxHp(it.type.hardness.toDouble()) } ?: BlockState.DEFAULT_MAX_DURABILITY
        return BlockState(
            blockType = type,
            durability = maxHp,
            lastDamagedTick = 0L,
            damagedBy = null,
            maxHp = maxHp,
        )
    }

    /**
     * Map a Bukkit [Block.type] to a vendor-neutral [BlockType] in the
     * `minecraft:` namespace. Bukkit's `Material.key.toString()` already
     * yields `minecraft:stone` form for vanilla materials.
     */
    private fun bukkitBlockTypeOf(block: Block): BlockType {
        val keyed = block.type.key.toString()
        return runCatching { BlockType(keyed) }.getOrElse { UNKNOWN_BLOCK_TYPE }
    }

    private companion object {
        private val UNKNOWN_BLOCK_TYPE = BlockType("astera:unknown")
    }
}
