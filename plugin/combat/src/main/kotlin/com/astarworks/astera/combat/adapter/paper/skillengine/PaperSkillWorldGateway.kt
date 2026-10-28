package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.combat.application.port.outbound.IToolBlockGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.CoverBlockService
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockHpRules
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import com.astarworks.astera.core.domain.Result as AsteraResult

/**
 * Block reads, edits and damage for the skill engine. The ports carry no world, so every call acts in [world] (the cast
 * owner's world, the same "last seen world" the entity query uses). Damage and cover go through [IBlockStateGateway] so the
 * match's durability and cover bookkeeping stay the single owner; plain place/remove are raw block edits the editor reverts.
 */
public class PaperSkillWorldGateway(
    private val world: () -> World?,
    private val ownerOf: (CastId) -> UUID?,
    private val blocks: () -> IBlockStateGateway?,
    private val guard: () -> IBlockEditGuard?,
    private val covers: () -> ICustomBlockRegistry?,
    /** Lazy: Match registers the structure targets (Annihilation pillars) after Combat wires. */
    private val structures: () -> IStructureTargets? = { null },
    /** Mining I/O of tool skills: surface hardness, drops with the tool, hit and break feedback. */
    private val toolBlocks: () -> IToolBlockGateway? = { null },
    /** Crack overlay and cover strikes; the one door cover damage goes through. */
    private val cover: () -> CoverBlockService? = { null },
    private val items: () -> IItemRegistry? = { null },
    /** The item the cast's skill is bound to; its tool tier sets the damage tier. */
    private val itemOf: (CastId) -> String? = { null },
) : ISkillWorldGateway {

    private fun block(cell: BlockCell): Block? = world()?.getBlockAt(cell.x, cell.y, cell.z)
    private fun worldId(w: World): WorldId = WorldId(w.name)
    private fun pos(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())

    override fun blockAt(cell: BlockCell): String? = block(cell)?.type?.takeIf { !it.isAir }?.key?.toString()

    override fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean): Boolean {
        val w = world() ?: return false
        val owner = ownerOf(cast) ?: return false
        if (outsideArena) return true
        return guard()?.canEdit(PlayerId(owner), worldId(w), pos(cell)) ?: true
    }

    override fun isCover(cell: BlockCell): Boolean {
        val w = world() ?: return false
        return blocks()?.isCover(worldId(w), pos(cell)) ?: false
    }

    override fun isCoverBlock(blockKey: String): Boolean =
        runCatching { covers()?.resolve(BlockType(blockKey)) != null }.getOrDefault(false)

    override fun place(cell: BlockCell, blockKey: String) {
        val material = Material.matchMaterial(blockKey) ?: return
        block(cell)?.setType(material, false)
    }

    override fun remove(cell: BlockCell) {
        block(cell)?.setType(Material.AIR, false)
    }

    override fun placeCover(cast: CastId, cell: BlockCell, blockKey: String): Boolean {
        val w = world() ?: return false
        if (!canEdit(cast, cell, outsideArena = false)) return false
        val gateway = blocks() ?: return false
        val owner = ownerOf(cast) ?: return false
        val spec = runCatching { covers()?.resolve(BlockType(blockKey)) }.getOrNull() ?: return false
        if (!gateway.canPlaceAt(worldId(w), pos(cell))) return false
        return gateway.placeTemporaryBlock(worldId(w), pos(cell), spec, PlayerId(owner)) is AsteraResult.Success
    }

    override fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val w = world() ?: return BlockDamageOutcome.NONE
        val owner = ownerOf(cast) ?: return BlockDamageOutcome.NONE
        structures()?.targetAtBlock(worldId(w), pos(cell))?.let { return strikeStructure(w, it, owner, blockDamage, toolClass) }
        val gateway = blocks() ?: return BlockDamageOutcome.NONE
        val kind = toolClass?.let { runCatching { ToolKind.valueOf(it.uppercase()) }.getOrNull() }?.takeIf { it != ToolKind.NONE }
        if (kind != null) return strikeWithTool(cast, w, cell, PlayerId(owner), gateway, blockDamage, kind)
        val result = gateway.applyDamageFromSkill(
            worldId(w), pos(cell), SkillId("skill-engine"), PlayerId(owner), BlockDamageProfile.flat(blockDamage),
        )
        val outcome = (result as? AsteraResult.Success)?.value ?: return BlockDamageOutcome.NONE
        return when (outcome) {
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken -> BlockDamageOutcome.BROKEN
            else -> BlockDamageOutcome.NONE
        }
    }

    /**
     * A tool skill (`tool_class`) strikes [cell]: the (tool, block) matrix and tier set the damage, the block keeps its
     * HP across casts, the crack overlay follows it, and a break drops what the tool would drop by hand.
     */
    private fun strikeWithTool(
        cast: CastId,
        w: World,
        cell: BlockCell,
        owner: PlayerId,
        gateway: IBlockStateGateway,
        blockDamage: Int,
        kind: ToolKind,
    ): BlockDamageOutcome {
        val world = worldId(w)
        val at = pos(cell)
        val tier = itemOf(cast)?.let { id -> items()?.let { HitCustomBlockUseCase.toolOf(it, ItemId(id)) } }?.tier ?: ToolTier.STONE
        val tool = ToolType(kind, tier)
        val tools = toolBlocks()
        val skill = SkillId("skill-engine")
        if (gateway.isCover(world, at)) {
            val spec = block(cell)?.let { covers()?.resolveByBlockData(it.blockData.asString) }
            val outcome = (cover()?.strikeCell(
                owner, world, at, skill, BlockDamageProfile(blockDamage, tool), CoverBlockService.Feedback.swing(kind),
            ) as? AsteraResult.Success)?.value
            return when (outcome) {
                is CoverBlockService.CellOutcome.Broken -> {
                    spec?.placedFrom?.let { tools?.dropCustom(world, at, owner, it) }
                    BlockDamageOutcome.BROKEN
                }
                is CoverBlockService.CellOutcome.Chipped -> { tools?.hitEffect(world, at); BlockDamageOutcome.CHIPPED }
                is CoverBlockService.CellOutcome.Ineffective -> { tools?.clink(world, at); BlockDamageOutcome.NONE }
                null -> BlockDamageOutcome.NONE
            }
        }
        val surface = tools?.surface(world, at)
        val baseType = surface?.data?.substringBefore('[')?.let(::BlockType) ?: blockAt(cell)?.let(::BlockType)
        val hardness = surface?.hardness ?: baseType?.let(BlockHpRules::simulatedHardness) ?: 1.5
        if (hardness < 0) return BlockDamageOutcome.NONE
        val preferred = baseType == null || preferredKind(baseType) == kind.name
        val maxHp = BlockHpRules.maxHp(hardness)
        val hp = gateway.damageBlock(world, at, maxHp, BlockHpRules.damage(blockDamage, tool, preferred, hardness))
        cover()?.showHp(world, at, hp, maxHp)
        if (hp > 0) {
            tools?.hitEffect(world, at)
            return BlockDamageOutcome.CHIPPED
        }
        val toolData = runCatching { tools?.heldTool(owner, kind.name, tool.tier.name) }.getOrNull()
        if (tools != null) tools.breakBlock(world, at, owner, toolData) else remove(cell)
        surface?.let { tools?.effect(world, at, it.data, false) }
        return BlockDamageOutcome.BROKEN
    }

    /** Preferred class for solid terrain; hand-breakable surfaces do not need a class bonus. */
    private fun preferredKind(type: BlockType): String {
        val id = type.identifier
        return when {
            id.contains("sandstone") || id.contains("mud_brick") -> "PICKAXE"
            SOIL_WORDS.any { it in id } -> "SHOVEL"
            WOOD_WORDS.any { it in id } -> "AXE"
            else -> "PICKAXE"
        }
    }

    /**
     * A tool skill struck a structure block (an Annihilation pillar): it takes damage instead of the block breaking.
     * A cast without a tool class (a weapon's contact chip) is skipped: the weapon's hit already reaches the pillar
     * through the target pipeline, so chipping the block as well would count it twice.
     */
    private fun strikeStructure(
        w: World,
        target: IStructureTargets.StructureTarget,
        owner: UUID,
        blockDamage: Int,
        toolClass: String?,
    ): BlockDamageOutcome {
        val tool = toolClass?.let { runCatching { ToolKind.valueOf(it.uppercase()) }.getOrNull() }
            ?.takeIf { it != ToolKind.NONE } ?: return BlockDamageOutcome.NONE
        val structures = structures() ?: return BlockDamageOutcome.NONE
        val origin = org.bukkit.Bukkit.getPlayer(owner)?.location?.let { Vec3(it.x, it.y, it.z) }
        val source = IStructureTargets.DamageSource(IStructureTargets.DamageKind.TOOL_SKILL, tool, origin)
        val result = structures.damage(target.id, PlayerId(owner), blockDamage.toDouble(), source)
        return if (result is AsteraResult.Success) BlockDamageOutcome.CHIPPED else BlockDamageOutcome.NONE
    }

    override fun consumeItem(cast: CastId, itemId: String): Boolean {
        val owner = ownerOf(cast) ?: return false
        val inv = world()?.players?.firstOrNull { it.uniqueId == owner }?.inventory ?: return false
        val key = itemId.let { NamespacedKey.fromString(it) }
        for (slot in 0 until inv.size) {
            val stack: ItemStack = inv.getItem(slot) ?: continue
            val matches = stack.type.key == key
            if (!matches) continue
            if (stack.amount <= 1) inv.setItem(slot, null) else stack.amount -= 1
            return true
        }
        return false
    }

    private companion object {
        val SOIL_WORDS: List<String> = listOf("dirt", "grass_block", "sand", "gravel", "clay", "mud", "snow", "podzol", "mycelium", "soul_soil", "farmland")
        val WOOD_WORDS: List<String> = listOf("plank", "log", "wood", "stem", "hyphae", "leaves", "fence", "door", "bookshelf", "scaffolding", "bamboo", "wool", "hay_block", "carpet")
    }
}
