package com.astarworks.astera.combat.adapter.paper.skillengine.world

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.IToolBlockGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.application.service.SkillWorldRules
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.IOreNodeStrikes
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyToolBay
import com.astarworks.astera.core.application.service.CoverBlockService
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.BlockHpRules
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import com.astarworks.astera.core.domain.Result as AsteraResult

/**
 * World ops of the skill engine on Paper. The shared ledger ([LedgerWorldGateway]) owns shapes, ordering, ripple and
 * reverting; [PaperBlockAccess] is the Bukkit block seam; `explode` / `lightning` are one vanilla call each.
 */
public class PaperSkillWorldGateway(
    private val server: Server,
    guard: () -> IBlockEditGuard?,
    blockStates: () -> IBlockStateGateway?,
    covers: () -> ICustomBlockRegistry?,
    structures: () -> IStructureTargets?,
    toolBay: () -> ILobbyToolBay?,
    private val lightning: () -> ILightningGateway?,
    coverService: () -> CoverBlockService?,
    toolBlocks: () -> IToolBlockGateway?,
    items: () -> IItemRegistry?,
    exclusion: () -> IAreaBreakExclusion?,
    oreNodes: () -> IOreNodeStrikes?,
) : LedgerWorldGateway(PaperBlockAccess(server, guard, blockStates, covers, structures, toolBay, coverService, toolBlocks, items, exclusion, oreNodes)) {

    override fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean) {
        val owner = server.getEntity(cast.caster) ?: return
        owner.world.createExplosion(Location(owner.world, at.x, at.y, at.z), power.toFloat(), fire, breakBlocks, owner)
    }

    override fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean) {
        val owner = server.getEntity(cast.caster) ?: return
        val world = owner.world
        if (visualOnly) {
            world.strikeLightningEffect(Location(world, at.x, at.y, at.z))
        } else {
            lightning()?.strike(WorldId(world.name), at, damageEntities = true)
        }
    }
}

/**
 * Block reads, edits and damage, in the world of the call's [CastRef]. Damage and cover go through [IBlockStateGateway] so the match's durability and cover bookkeeping
 * stay the single owner; plain place/remove are raw block edits the ledger reverts.
 *
 * A tool skill (`toolClass` set) strikes by the (tool class x item tier x block) rule of [BlockHpRules]: the tier comes from the
 * item the cast's skill is bound to ([CastRef.item]), the block keeps its HP across casts, the crack overlay follows it and a
 * break drops what that tool drops by hand. Cells an [IAreaBreakExclusion] names (Mid ore nodes) are never editable.
 */
internal class PaperBlockAccess(
    private val server: Server,
    private val guard: () -> IBlockEditGuard?,
    private val blockStates: () -> IBlockStateGateway?,
    private val covers: () -> ICustomBlockRegistry?,
    /** Lazy: Match registers the structure targets (Annihilation pillars) after Combat wires. */
    private val structures: () -> IStructureTargets?,
    /** Lazy: Core registers the lobby tool bay (MAP-A6) on lobby-hosting backends only. */
    private val toolBay: () -> ILobbyToolBay?,
    private val coverService: () -> CoverBlockService?,
    private val toolBlocks: () -> IToolBlockGateway?,
    private val items: () -> IItemRegistry?,
    private val exclusion: () -> IAreaBreakExclusion?,
    /** Lazy: Match registers the Mid ore nodes after Combat wires. A tool skill strikes a node through it. */
    private val oreNodes: () -> IOreNodeStrikes? = { null },
) : ISkillBlockAccess {

    private fun worldOf(cast: CastRef): World? = server.getWorld(cast.world.value)
    private fun block(cast: CastRef, cell: BlockCell): Block? = worldOf(cast)?.getBlockAt(cell.x, cell.y, cell.z)
    private fun worldId(w: World): WorldId = WorldId(w.name)
    private fun pos(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())

    override fun blockAt(cast: CastRef, cell: BlockCell): String? = block(cast, cell)?.type?.takeIf { !it.isAir }?.key?.toString()

    override fun canEdit(cast: CastRef, cell: BlockCell, outsideArena: Boolean): Boolean {
        val w = worldOf(cast) ?: return false
        // The lobby is protected except for the tool bay (MAP-A6): the bay policy outranks the outside-arena shortcut.
        toolBay()?.editPolicy(worldId(w), cell.x, cell.y, cell.z)?.let { return it }
        // A Mid ore node is not editable by the plain block rules, but a tool skill may strike it (damage() routes it to the node).
        if (!isOreNode(w, cell) && exclusion()?.isExcluded(worldId(w), pos(cell)) == true) return false
        if (outsideArena) return true
        return guard()?.canEdit(PlayerId(cast.caster), worldId(w), pos(cell)) ?: true
    }

    override fun isCover(cast: CastRef, cell: BlockCell): Boolean {
        val w = worldOf(cast) ?: return false
        return blockStates()?.isCover(worldId(w), pos(cell)) ?: false
    }

    override fun isStructure(cast: CastRef, cell: BlockCell): Boolean {
        val w = worldOf(cast) ?: return false
        return isOreNode(w, cell) || structures()?.targetAtBlock(worldId(w), pos(cell)) != null
    }

    private fun isOreNode(w: World, cell: BlockCell): Boolean = oreNodes()?.isNode(worldId(w), pos(cell)) == true

    override fun isCoverBlock(blockKey: String): Boolean =
        runCatching { covers()?.resolve(BlockType(blockKey)) != null }.getOrDefault(false)

    override fun place(cast: CastRef, cell: BlockCell, blockKey: String) {
        val material = Material.matchMaterial(blockKey) ?: return
        remember(cast, cell)
        block(cast, cell)?.setType(material, false)
    }

    override fun remove(cast: CastRef, cell: BlockCell) {
        remember(cast, cell)
        block(cast, cell)?.setType(Material.AIR, false)
    }

    /** Journals a bay block before a raw edit so the bay restores it (no-op outside the bay). */
    private fun remember(cast: CastRef, cell: BlockCell) {
        val w = worldOf(cast) ?: return
        toolBay()?.remember(worldId(w), cell.x, cell.y, cell.z)
    }

    override fun placeCover(cast: CastRef, cell: BlockCell, blockKey: String): Boolean {
        val w = worldOf(cast) ?: return false
        if (!canEdit(cast, cell, outsideArena = false)) return false
        val gateway = blockStates() ?: return false
        val spec = runCatching { covers()?.resolve(BlockType(blockKey)) }.getOrNull() ?: return false
        if (!gateway.canPlaceAt(worldId(w), pos(cell))) return false
        return gateway.placeTemporaryBlock(worldId(w), pos(cell), spec, PlayerId(cast.caster)) is AsteraResult.Success
    }

    override fun damage(cast: CastRef, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val w = worldOf(cast) ?: return BlockDamageOutcome.NONE
        if (isOreNode(w, cell)) return strikeOreNode(cast, w, cell, blockDamage, toolClass)
        structures()?.targetAtBlock(worldId(w), pos(cell))?.let { return strikeStructure(cast, it, blockDamage, toolClass) }
        val gateway = blockStates() ?: return BlockDamageOutcome.NONE
        val kind = toolClass?.let { runCatching { ToolKind.valueOf(it.uppercase()) }.getOrNull() }?.takeIf { it != ToolKind.NONE }
        if (kind != null) return strikeWithTool(cast, w, cell, gateway, blockDamage, kind)
        val result = gateway.applyDamageFromSkill(
            worldId(w), pos(cell), SkillId("skill-engine"), PlayerId(cast.caster), BlockDamageProfile.flat(blockDamage),
        )
        val outcome = (result as? AsteraResult.Success)?.value ?: return BlockDamageOutcome.NONE
        return when (outcome) {
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken -> BlockDamageOutcome.BROKEN
            else -> BlockDamageOutcome.NONE
        }
    }

    /** The tier of the item the cast's skill is bound to; an unbound or non-tool skill digs as a stone tool. */
    private fun tierOf(cast: CastRef): ToolTier =
        cast.item?.let { id -> items()?.let { HitCustomBlockUseCase.toolOf(it, ItemId(id)) } }?.tier ?: ToolTier.STONE

    private fun strikeWithTool(
        cast: CastRef,
        w: World,
        cell: BlockCell,
        gateway: IBlockStateGateway,
        blockDamage: Int,
        kind: ToolKind,
    ): BlockDamageOutcome {
        val world = worldId(w)
        val at = pos(cell)
        val owner = PlayerId(cast.caster)
        val tool = ToolType(kind, tierOf(cast))
        val tools = toolBlocks()
        val cover = coverService()
        if (gateway.isCover(world, at)) {
            val spec = block(cast, cell)?.let { covers()?.resolveByBlockData(it.blockData.asString) }
            val outcome = (cover?.strikeCell(
                owner, world, at, SkillId("skill-engine"), BlockDamageProfile(blockDamage, tool), CoverBlockService.Feedback.swing(kind),
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
        val baseType = surface?.data?.substringBefore('[')?.let(::BlockType) ?: blockAt(cast, cell)?.let(::BlockType)
        val hardness = surface?.hardness ?: baseType?.let(BlockHpRules::simulatedHardness) ?: 1.5
        if (hardness < 0) return BlockDamageOutcome.NONE
        val preferred = baseType == null || BlockHpRules.preferredKind(baseType) == kind
        val maxHp = BlockHpRules.maxHp(hardness)
        val hp = gateway.damageBlock(world, at, maxHp, BlockHpRules.damage(blockDamage, tool, preferred, hardness))
        cover?.showHp(world, at, hp, maxHp)
        if (hp > 0) {
            tools?.hitEffect(world, at)
            return BlockDamageOutcome.CHIPPED
        }
        val toolData = runCatching { tools?.heldTool(owner, kind.name, tool.tier.name) }.getOrNull()
        if (tools != null) tools.breakBlock(world, at, owner, toolData) else remove(cast, cell)
        surface?.let { tools?.effect(world, at, it.data, false) }
        return BlockDamageOutcome.BROKEN
    }

    /**
     * A tool skill struck a Mid ore node: the node applies its own affinity / Norma level / HP rules and handles its drops and regrowth,
     * so this never reports BROKEN (the ledger would otherwise restore the ore over the regrowing placeholder). A cast without a tool
     * class (a weapon's contact chip) is skipped.
     */
    private fun strikeOreNode(cast: CastRef, w: World, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val kind = toolClass?.let { c -> ToolKind.entries.firstOrNull { it != ToolKind.NONE && it.name.equals(c, ignoreCase = true) } }
            ?: return BlockDamageOutcome.NONE
        val nodes = oreNodes() ?: return BlockDamageOutcome.NONE
        val result = nodes.strike(PlayerId(cast.caster), worldId(w), pos(cell), ToolType(kind, tierOf(cast)), blockDamage)
        return if (result == IOreNodeStrikes.Result.NONE) BlockDamageOutcome.NONE else BlockDamageOutcome.CHIPPED
    }

    /**
     * A tool skill struck a structure block (an Annihilation pillar): the pillar takes damage instead of the block breaking,
     * scaled by the tier of the skill's item. A cast without a tool class (a weapon's contact chip) is skipped: the weapon's hit already
     * reaches the pillar through the target pipeline, so chipping the block as well would count it twice.
     */
    private fun strikeStructure(cast: CastRef, target: IStructureTargets.StructureTarget, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val tool = toolClass?.let { c -> ToolKind.entries.firstOrNull { it != ToolKind.NONE && it.name.equals(c, ignoreCase = true) } }
            ?: return BlockDamageOutcome.NONE
        val structures = structures() ?: return BlockDamageOutcome.NONE
        val caster = server.getPlayer(cast.caster)
        val origin = caster?.location?.let { Vec3(it.x, it.y, it.z) }
        val tierFactor = SkillWorldRules.structureTierFactor(tierOf(cast))
        val source = IStructureTargets.DamageSource(IStructureTargets.DamageKind.TOOL_SKILL, tool, origin)
        val result = structures.damage(target.id, PlayerId(cast.caster), blockDamage * tierFactor, source)
        return if (result is AsteraResult.Success) BlockDamageOutcome.CHIPPED else BlockDamageOutcome.NONE
    }

    override fun consumeItem(cast: CastRef, itemId: String): Boolean {
        val inv = server.getPlayer(cast.caster)?.inventory ?: return false
        val key = NamespacedKey.fromString(itemId)
        for (slot in 0 until inv.size) {
            val stack: ItemStack = inv.getItem(slot) ?: continue
            if (stack.type.key != key) continue
            if (stack.amount <= 1) inv.setItem(slot, null) else stack.amount -= 1
            return true
        }
        return false
    }
}
