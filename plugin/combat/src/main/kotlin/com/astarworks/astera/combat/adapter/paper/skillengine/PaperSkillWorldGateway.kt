package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.combat.application.service.SkillWorldRules
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Material
import org.bukkit.Bukkit
import org.slf4j.LoggerFactory
import org.bukkit.persistence.PersistentDataType
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import com.astarworks.astera.core.domain.Result as AsteraResult

/**
 * Block reads, edits and damage for the skill engine in ONE world, [worldId] (the world the cast started in; the kit
 * builds one gateway per world). Damage and cover go through [IBlockStateGateway] so the
 * match's durability and cover bookkeeping stay the single owner; plain place/remove are raw block edits the editor reverts.
 */
public class PaperSkillWorldGateway(
    private val server: org.bukkit.Server,
    private val worldId: WorldId,
    private val ownerOf: (CastId) -> UUID?,
    private val blocks: () -> IBlockStateGateway?,
    private val guard: () -> IBlockEditGuard?,
    private val covers: () -> ICustomBlockRegistry?,
    /** The running cast's skill definition (its id and its item), null once the cast has ended. */
    private val skillOf: (CastId) -> SkillDef?,
    /** The field-tool class and tier an item id names (its tool tag), null for any other item. */
    private val itemTool: (String) -> ToolType?,
) : ISkillWorldGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val reported = HashSet<String>()

    private fun world(): World? = server.getWorld(worldId.value)
    private fun block(cell: BlockCell): Block? = world()?.getBlockAt(cell.x, cell.y, cell.z)
    private fun worldId(@Suppress("UNUSED_PARAMETER") w: World): WorldId = worldId
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

    override fun place(cell: BlockCell, blockKey: String): Boolean {
        val material = Material.matchMaterial(blockKey)
        if (material == null) {
            log.error("skill engine: cannot place unknown block key '{}' at {}", blockKey, cell)
            return false
        }
        val target = block(cell)
        if (target == null) {
            log.error("skill engine: cannot place '{}' at {}: no active world", blockKey, cell)
            return false
        }
        target.setType(material, false)
        return true
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
        val w = world() ?: return failed("break_area at $cell: no active world")
        val owner = ownerOf(cast) ?: return failed("break_area at $cell: cast ${cast.value} has no owner")
        val gateway = blocks() ?: return failed("break_area at $cell: block gateway is not registered")
        val def = skillOf(cast) ?: return failed("break_area at $cell: cast ${cast.value} is not running")
        val skillId = runCatching { SkillId(def.baseId) }.getOrNull() ?: return failed("break_area: '${def.baseId}' is not a valid skill id")
        val profile = when (val r = SkillWorldRules.profile(def.baseId, toolClass, blockDamage, def.item?.let(itemTool), def.item)) {
            is AsteraResult.Success -> r.value
            is AsteraResult.Failure -> return failed(r.error.message, once = "${def.baseId}/${r.error::class.simpleName}")
        }
        val result = gateway.applyDamageFromSkill(worldId(w), pos(cell), skillId, PlayerId(owner), profile)
        val outcome = when (result) {
            is AsteraResult.Success -> result.value
            is AsteraResult.Failure -> return failed("break_area at $cell: block gateway refused: ${result.error}", once = "${def.baseId}/gateway")
        }
        return when (outcome) {
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken -> BlockDamageOutcome.BROKEN
            else -> BlockDamageOutcome.NONE
        }
    }

    override fun consumeItem(cast: CastId, itemId: String): Boolean {
        val ownerId = ownerOf(cast) ?: run { log.error("skill engine: consume_item '{}': cast {} has no owner", itemId, cast.value); return false }
        // Server-wide: the owner may have crossed worlds since the cast started.
        val player = Bukkit.getPlayer(ownerId) ?: run { log.error("skill engine: consume_item '{}': owner {} is not online", itemId, ownerId); return false }
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val stack: ItemStack = inv.getItem(slot) ?: continue
            if (stack.isEmpty) continue
            if (!SkillWorldRules.stackMatches(itemId, asteraIdOf(stack), stack.type.key.toString())) continue
            if (stack.amount <= 1) inv.setItem(slot, null) else stack.amount -= 1
            return true
        }
        return false
    }

    /** The Astera item id a stack carries (`item_id` in its PDC, matched by path), null for plain stacks. */
    private fun asteraIdOf(stack: ItemStack): String? {
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }

    /** A failure is logged (once per [once] key when it would repeat per cell) and reported as no damage, never swallowed. */
    private fun failed(message: String, once: String? = null): BlockDamageOutcome {
        if (once == null || reported.add(once)) log.error("skill engine: {}", message)
        return BlockDamageOutcome.NONE
    }
}
