package com.astarworks.astera.combat.adapter.paper.skillengine.world

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import com.astarworks.astera.core.domain.Result as AsteraResult

/**
 * World ops of the skill engine on Paper. The shared ledger ([LedgerWorldGateway]) owns shapes, ordering, ripple and
 * reverting; [PaperBlockAccess] is the Bukkit block seam; `explode` / `lightning` are one vanilla call each.
 */
public class PaperSkillWorldGateway(
    private val server: Server,
    world: () -> World?,
    guard: () -> IBlockEditGuard?,
    blockStates: () -> IBlockStateGateway?,
    covers: () -> ICustomBlockRegistry?,
    private val lightning: () -> ILightningGateway?,
) : LedgerWorldGateway(PaperBlockAccess(server, world, guard, blockStates, covers)) {

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
 * Block reads, edits and damage. The port carries no world, so every call acts in [world] (the "last seen world" the
 * entity query uses). Damage and cover go through [IBlockStateGateway] so the match's durability and cover bookkeeping
 * stay the single owner; plain place/remove are raw block edits the ledger reverts.
 */
internal class PaperBlockAccess(
    private val server: Server,
    private val world: () -> World?,
    private val guard: () -> IBlockEditGuard?,
    private val blockStates: () -> IBlockStateGateway?,
    private val covers: () -> ICustomBlockRegistry?,
) : ISkillBlockAccess {

    private fun block(cell: BlockCell): Block? = world()?.getBlockAt(cell.x, cell.y, cell.z)
    private fun worldId(w: World): WorldId = WorldId(w.name)
    private fun pos(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())

    override fun blockAt(cell: BlockCell): String? = block(cell)?.type?.takeIf { !it.isAir }?.key?.toString()

    override fun canEdit(caster: UUID, cell: BlockCell, outsideArena: Boolean): Boolean {
        val w = world() ?: return false
        if (outsideArena) return true
        return guard()?.canEdit(PlayerId(caster), worldId(w), pos(cell)) ?: true
    }

    override fun isCover(cell: BlockCell): Boolean {
        val w = world() ?: return false
        return blockStates()?.isCover(worldId(w), pos(cell)) ?: false
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

    override fun placeCover(caster: UUID, cell: BlockCell, blockKey: String): Boolean {
        val w = world() ?: return false
        if (!canEdit(caster, cell, outsideArena = false)) return false
        val gateway = blockStates() ?: return false
        val spec = runCatching { covers()?.resolve(BlockType(blockKey)) }.getOrNull() ?: return false
        if (!gateway.canPlaceAt(worldId(w), pos(cell))) return false
        return gateway.placeTemporaryBlock(worldId(w), pos(cell), spec, PlayerId(caster)) is AsteraResult.Success
    }

    override fun damage(caster: UUID, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val w = world() ?: return BlockDamageOutcome.NONE
        val gateway = blockStates() ?: return BlockDamageOutcome.NONE
        val result = gateway.applyDamageFromSkill(
            worldId(w), pos(cell), SkillId("skill-engine"), PlayerId(caster), BlockDamageProfile.flat(blockDamage),
        )
        val outcome = (result as? AsteraResult.Success)?.value ?: return BlockDamageOutcome.NONE
        return when (outcome) {
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken -> BlockDamageOutcome.BROKEN
            else -> BlockDamageOutcome.NONE
        }
    }

    override fun consumeItem(caster: UUID, itemId: String): Boolean {
        val inv = server.getPlayer(caster)?.inventory ?: return false
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
