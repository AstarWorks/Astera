package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IOreNodeWorld
import com.astarworks.astera.match.application.service.OreNodeService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Mid ore nodes on Paper: a break (or the start of a dig) on a node block is cancelled and handed to [OreNodeService.mine],
 * which applies the tool affinity and Norma level rules, gives the drops and swaps the block for its placeholder. Blocks that are not nodes pass through untouched.
 */
public class PaperOreNodeListener(
    private val service: () -> OreNodeService?,
) : Listener {
    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) {
        val outcome = mine(event.player, event.block) ?: return
        if (outcome !is OreNodeService.Outcome.NotANode) event.isCancelled = true
    }

    /** Survival dig start: a node never starts vanilla cracking, so the break event only fires for instant mining. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDig(event: BlockDamageEvent) {
        val outcome = mine(event.player, event.block) ?: return
        if (outcome !is OreNodeService.Outcome.NotANode) event.isCancelled = true
    }

    /** An explosion never eats a node cell (ore or its regrowing cobblestone). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onEntityExplode(event: EntityExplodeEvent) {
        val svc = service() ?: return
        event.blockList().removeIf { svc.isExcluded(WorldId(it.world.name), Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBlockExplode(event: BlockExplodeEvent) {
        val svc = service() ?: return
        event.blockList().removeIf { svc.isExcluded(WorldId(it.world.name), Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())) }
    }

    private fun mine(player: Player, block: org.bukkit.block.Block): OreNodeService.Outcome? {
        val svc = service() ?: return null
        return svc.mine(
            PlayerId(player.uniqueId),
            WorldId(block.world.name),
            Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()),
            heldItemId(player),
        )
    }

    private fun heldItemId(player: Player): ItemId? {
        val pdc = player.inventory.itemInMainHand.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == ITEM_ID_KEY } ?: return null
        return pdc.get(key, PersistentDataType.STRING)?.let { runCatching { ItemId(it) }.getOrNull() }
    }

    private companion object {
        /** Mirrors `PaperItemIdResolver.ITEM_ID_KEY` in the items plugin (sibling plugins are not imported). */
        const val ITEM_ID_KEY = "item_id"
    }
}

/** Sets the node / placeholder block on the region thread that owns it. */
public class PaperOreNodeWorld(private val plugin: JavaPlugin) : IOreNodeWorld {
    override fun setBlock(world: WorldId, pos: Vec3, material: String) {
        val w = plugin.server.getWorld(world.value) ?: return
        val type = Material.matchMaterial(material) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable { at.block.type = type })
    }

    override fun hit(world: WorldId, pos: Vec3, hp: Int, maxHp: Int) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        val progress = (1.0f - hp.toFloat() / maxHp).coerceIn(0.0f, 1.0f)
        plugin.server.regionScheduler.execute(plugin, at, Runnable {
            at.world.playSound(at, at.block.blockData.soundGroup.hitSound, 1.0f, 1.0f)
            at.world.players.forEach { it.sendBlockDamage(at, progress) }
        })
    }

    override fun deny(world: WorldId, pos: Vec3) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable { at.world.playSound(at, org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f) })
    }

    override fun clearCrack(world: WorldId, pos: Vec3) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable { at.world.players.forEach { it.sendBlockDamage(at, 0.0f) } })
    }
}
