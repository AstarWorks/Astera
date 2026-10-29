package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.SkillWorldRules
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IOreNodeStrikes
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
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
import com.astarworks.astera.core.domain.block.BlockDamageOutcome as DomainOutcome
import com.astarworks.astera.core.domain.Result as AsteraResult
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The sim's block seam for the skill engine's world ops: the same rules as the Paper `PaperBlockAccess` (cover blocks through
 * [IBlockStateGateway], ore nodes and structures through their services, tool class x item tier x block HP for plain blocks)
 * over [SimWorld]'s block map. Every service is looked up lazily, since Match registers some after Combat wires.
 */
internal class SimBlockAccess(private val sim: SimWorld, private val services: ServiceRegistry) : ISkillBlockAccess {

    private fun pos(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())
    private fun isOreNode(cast: CastRef, cell: BlockCell): Boolean = services.get<IOreNodeStrikes>()?.isNode(cast.world, pos(cell)) == true
    private fun blockStates(): IBlockStateGateway? = services.get<IBlockStateGateway>()

    override fun blockAt(cast: CastRef, cell: BlockCell): String? = sim.blockAt(cast.world, pos(cell))?.key

    override fun canEdit(cast: CastRef, cell: BlockCell, outsideArena: Boolean): Boolean =
        isOreNode(cast, cell) || services.get<IAreaBreakExclusion>()?.isExcluded(cast.world, pos(cell)) != true

    override fun isCover(cast: CastRef, cell: BlockCell): Boolean = blockStates()?.isCover(cast.world, pos(cell)) ?: false

    override fun isStructure(cast: CastRef, cell: BlockCell): Boolean =
        isOreNode(cast, cell) || services.get<IStructureTargets>()?.targetAtBlock(cast.world, pos(cell)) != null

    override fun isCoverBlock(blockKey: String): Boolean =
        runCatching { services.get<ICustomBlockRegistry>()?.resolve(BlockType(blockKey)) != null }.getOrDefault(false)

    override fun place(cast: CastRef, cell: BlockCell, blockKey: String) {
        sim.placeBlock(cast.world, pos(cell), BlockType(blockKey))
    }

    override fun remove(cast: CastRef, cell: BlockCell) {
        sim.removeBlock(cast.world, pos(cell))
    }

    override fun placeCover(cast: CastRef, cell: BlockCell, blockKey: String): Boolean {
        if (!canEdit(cast, cell, outsideArena = false)) return false
        val gateway = blockStates() ?: return false
        val spec = runCatching { services.get<ICustomBlockRegistry>()?.resolve(BlockType(blockKey)) }.getOrNull() ?: return false
        if (!gateway.canPlaceAt(cast.world, pos(cell))) return false
        return gateway.placeTemporaryBlock(cast.world, pos(cell), spec, PlayerId(cast.caster)) is AsteraResult.Success
    }

    private fun tierOf(cast: CastRef): ToolTier =
        cast.item?.let { id -> services.get<IItemRegistry>()?.let { HitCustomBlockUseCase.toolOf(it, ItemId(id)) } }?.tier ?: ToolTier.STONE

    private fun kindOf(toolClass: String?): ToolKind? =
        toolClass?.let { c -> ToolKind.entries.firstOrNull { it != ToolKind.NONE && it.name.equals(c, ignoreCase = true) } }

    override fun damage(cast: CastRef, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val at = pos(cell)
        val kind = kindOf(toolClass)
        if (isOreNode(cast, cell)) {
            val nodes = services.get<IOreNodeStrikes>() ?: return BlockDamageOutcome.NONE
            val tool = kind ?: return BlockDamageOutcome.NONE
            val result = nodes.strike(PlayerId(cast.caster), cast.world, at, ToolType(tool, tierOf(cast)), blockDamage)
            return if (result == IOreNodeStrikes.Result.NONE) BlockDamageOutcome.NONE else BlockDamageOutcome.CHIPPED
        }
        services.get<IStructureTargets>()?.targetAtBlock(cast.world, at)?.let { target ->
            val tool = kind ?: return BlockDamageOutcome.NONE
            val structures = services.get<IStructureTargets>() ?: return BlockDamageOutcome.NONE
            val source = IStructureTargets.DamageSource(IStructureTargets.DamageKind.TOOL_SKILL, tool, null)
            val result = structures.damage(target.id, PlayerId(cast.caster), blockDamage * SkillWorldRules.structureTierFactor(tierOf(cast)), source)
            return if (result is AsteraResult.Success) BlockDamageOutcome.CHIPPED else BlockDamageOutcome.NONE
        }
        val gateway = blockStates() ?: return BlockDamageOutcome.NONE
        if (gateway.isCover(cast.world, at)) return strikeCover(cast, gateway, at, blockDamage, kind)
        val key = blockAt(cast, cell) ?: return BlockDamageOutcome.NONE
        if (kind != null) {
            val type = BlockType(key)
            val hardness = BlockHpRules.simulatedHardness(type)
            if (hardness < 0) return BlockDamageOutcome.NONE
            val tool = ToolType(kind, tierOf(cast))
            val maxHp = BlockHpRules.maxHp(hardness)
            val hp = gateway.damageBlock(cast.world, at, maxHp, BlockHpRules.damage(blockDamage, tool, BlockHpRules.preferredKind(type) == kind, hardness))
            if (hp > 0) return BlockDamageOutcome.CHIPPED
        }
        sim.removeBlock(cast.world, at)
        return BlockDamageOutcome.BROKEN
    }

    private fun strikeCover(cast: CastRef, gateway: IBlockStateGateway, at: Vec3, blockDamage: Int, kind: ToolKind?): BlockDamageOutcome {
        val profile = if (kind != null) BlockDamageProfile(blockDamage, ToolType(kind, tierOf(cast))) else BlockDamageProfile.flat(blockDamage)
        val outcome = (gateway.applyDamageFromSkill(cast.world, at, SkillId("skill-engine"), PlayerId(cast.caster), profile) as? AsteraResult.Success)?.value
        return when (outcome) {
            is DomainOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is DomainOutcome.Broken -> { sim.removeBlock(cast.world, at); BlockDamageOutcome.BROKEN }
            else -> BlockDamageOutcome.NONE
        }
    }

    override fun consumeItem(cast: CastRef, itemId: String): Boolean = false
}
