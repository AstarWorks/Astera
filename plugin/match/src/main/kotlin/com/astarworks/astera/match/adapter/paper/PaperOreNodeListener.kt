package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IOreNodeWorld
import com.astarworks.astera.match.application.port.outbound.IOreDigSight
import com.astarworks.astera.match.application.service.OreDigHold
import com.astarworks.astera.match.application.service.OreNodeService
import kotlin.math.floor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Mid ore nodes on Paper: a break (or the start of a dig) on a node block is cancelled and handed to [OreNodeService.mine],
 * which applies the tool affinity and Norma level rules, gives the drops and swaps the block for its placeholder. Blocks that are not nodes pass through untouched.
 */
public class PaperOreNodeListener(
    private val service: () -> OreNodeService?,
    private val actions: () -> com.astarworks.astera.match.application.service.StarActionService? = { null },
    private val hold: () -> OreDigHold? = { null },
) : Listener {
    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) {
        val outcome = mine(event.player, event.block) ?: return
        if (outcome !is OreNodeService.Outcome.NotANode) event.isCancelled = true
        if (outcome is OreNodeService.Outcome.Mined) actions()?.onOreMined(PlayerId(event.player.uniqueId))
    }

    /**
     * Survival dig start. A node never starts vanilla cracking, so this fires once per click, not once per hit: [OreDigHold] takes the
     * click's hit and keeps hitting at the node's hit cadence while the button stays down (arm swings) and the crosshair stays on the node.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDig(event: BlockDamageEvent) {
        val holder = hold()
        val block = event.block
        val outcome = if (holder != null) {
            holder.begin(PlayerId(event.player.uniqueId), WorldId(block.world.name), Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()))
        } else {
            mine(event.player, block)?.also { if (it is OreNodeService.Outcome.Mined) actions()?.onOreMined(PlayerId(event.player.uniqueId)) }
        } ?: return
        if (outcome !is OreNodeService.Outcome.NotANode) event.isCancelled = true
    }

    /** The button was released or the crosshair left the block. */
    @EventHandler
    public fun onDigAbort(event: BlockDamageAbortEvent) {
        hold()?.release(PlayerId(event.player.uniqueId))
    }

    /** A held dig swings the arm every tick; that keeps the hold alive. */
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onSwing(event: PlayerAnimationEvent) {
        hold()?.swung(PlayerId(event.player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onQuit(event: PlayerQuitEvent) {
        hold()?.release(PlayerId(event.player.uniqueId))
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

/** What a digging player holds and aims at, read on the player's own scheduler. */
public class PaperOreDigSight(private val server: org.bukkit.Server) : IOreDigSight {
    override fun heldItem(player: PlayerId): ItemId? {
        val pdc = server.getPlayer(player.uuid)?.inventory?.itemInMainHand?.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)?.let { runCatching { ItemId(it) }.getOrNull() }
    }

    override fun aimsAt(player: PlayerId, world: WorldId, pos: Vec3): Boolean {
        val target = server.getPlayer(player.uuid)?.getTargetBlockExact(REACH) ?: return false
        return target.world.name == world.value && target.x == floor(pos.x).toInt() && target.y == floor(pos.y).toInt() && target.z == floor(pos.z).toInt()
    }

    private companion object {
        const val REACH = 6
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

    override fun mined(world: WorldId, pos: Vec3, material: String) {
        val w = plugin.server.getWorld(world.value) ?: return
        val data = (Material.matchMaterial(material) ?: return).createBlockData()
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable {
            val centre = at.clone().add(0.5, 0.5, 0.5)
            at.world.spawnParticle(org.bukkit.Particle.BLOCK, centre, 30, 0.3, 0.3, 0.3, 0.0, data)
            at.world.playSound(centre, data.soundGroup.breakSound, 1.0f, 1.0f)
        })
    }

    override fun deny(world: WorldId, pos: Vec3) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable { at.world.playSound(at, org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f) })
    }

    override fun refused(world: WorldId, pos: Vec3) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable {
            at.world.spawnParticle(org.bukkit.Particle.SMOKE, at.clone().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0.01)
        })
    }

    override fun clearCrack(world: WorldId, pos: Vec3) {
        val w = plugin.server.getWorld(world.value) ?: return
        val at = Location(w, pos.x, pos.y, pos.z)
        plugin.server.regionScheduler.execute(plugin, at, Runnable { at.world.players.forEach { it.sendBlockDamage(at, 0.0f) } })
    }
}
