package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.adapter.paper.world.assertWorldOwnership
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IStarDropWorld
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID

/**
 * A star drop in the world is a dropped NETHER_STAR item entity: glowing, named `★ xN`, stack count = N (capped at
 * 64), never despawning, invulnerable, tagged with a unique PDC value so it never merges with another drop. Its
 * vanilla pickup (player, mob, hopper) is always cancelled; a player touching it is reported to the listener, which
 * credits the in-match star count and removes the entity. The drop never reaches an inventory.
 */
public class PaperStarDropWorld(private val plugin: JavaPlugin, private val scheduler: IScheduler) : IStarDropWorld, Listener {
    private val server = plugin.server
    private val key = NamespacedKey(plugin, "star_drop")
    private var listener: ((String, PlayerId) -> Unit)? = null

    private class Drop(val world: WorldId, val position: Vec3, @Volatile var amount: Int) {
        @Volatile var item: Item? = null
        @Volatile var removed: Boolean = false
    }
    private val drops = ConcurrentHashMap<String, Drop>()

    init {
        server.pluginManager.registerEvents(this, plugin)
    }

    override fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String? {
        val w = server.getWorld(world.value) ?: return null
        val handle = UUID.randomUUID().toString()
        val drop = Drop(world, position, amount)
        drops[handle] = drop
        val scope = regionScope(world, position)
        scheduler.run(scope, Duration.ZERO) {
            assertWorldOwnership(scheduler, scope)
            if (!drop.removed) {
                val stack = ItemStack(Material.NETHER_STAR, drop.amount.coerceIn(1, MAX_STACK))
                stack.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, handle) }
                val item = w.dropItem(Location(w, position.x, position.y, position.z), stack) { e ->
                    e.isGlowing = true
                    e.isInvulnerable = true
                    e.setUnlimitedLifetime(true)
                    e.setCanMobPickup(false)
                    e.pickupDelay = pickupDelayTicks.coerceAtLeast(0)
                    e.customName(label(drop.amount))
                    e.isCustomNameVisible = true
                    e.velocity = velocity?.let { Vector(it.x, it.y, it.z) } ?: Vector(0.0, 0.0, 0.0)
                }
                drop.item = item
                if (drop.removed) item.remove()
                else {
                    item.customName(label(drop.amount))
                    item.itemStack = item.itemStack.also { it.amount = drop.amount.coerceIn(1, MAX_STACK) }
                }
            }
        }
        return handle
    }

    override fun executionScope(handle: String): ExecutionScope {
        // A removed handle has no live world access left; the service's Global poll clears it.
        val drop = drops[handle] ?: return ExecutionScope.Global
        return drop.item?.let { ExecutionScope.Entity(it.uniqueId) } ?: regionScope(drop.world, drop.position)
    }

    override fun locate(handle: String): Vec3? {
        val drop = drops[handle] ?: return null
        val e = drop.item ?: return drop.position // pending region spawn, not a lost item
        // A poll can have selected the pending Region just before the entity was installed.
        if (!scheduler.isOnScope(ExecutionScope.Entity(e.uniqueId))) return drop.position
        if (!e.isValid) return null
        val at = e.location
        return Vec3(at.x, at.y, at.z)
    }

    override fun relabel(handle: String, amount: Int) {
        val drop = drops[handle] ?: return
        drop.amount = amount
        val item = drop.item ?: return
        scheduler.run(ExecutionScope.Entity(item.uniqueId), Duration.ZERO) {
            assertWorldOwnership(scheduler, ExecutionScope.Entity(item.uniqueId))
            item.customName(label(drop.amount))
            item.itemStack = item.itemStack.also { it.amount = drop.amount.coerceIn(1, MAX_STACK) }
        }
    }

    override fun remove(handle: String) {
        val drop = drops.remove(handle) ?: return
        drop.removed = true
        val item = drop.item ?: return
        scheduler.run(ExecutionScope.Entity(item.uniqueId), Duration.ZERO) {
            assertWorldOwnership(scheduler, ExecutionScope.Entity(item.uniqueId))
            item.remove()
        }
    }

    override fun take(handle: String, player: PlayerId) {
        val drop = drops.remove(handle) ?: return
        drop.removed = true
        val item = drop.item ?: return
        scheduler.run(ExecutionScope.Entity(item.uniqueId), Duration.ZERO) {
            assertWorldOwnership(scheduler, ExecutionScope.Entity(item.uniqueId))
            val viewer = server.getPlayer(player.uuid)
            if (viewer != null && org.bukkit.Bukkit.isOwnedByCurrentRegion(viewer)) viewer.playPickupItemAnimation(item)
            item.remove()
        }
    }

    override fun onPickupAttempt(listener: (handle: String, player: PlayerId) -> Unit) {
        this.listener = listener
    }

    private fun label(amount: Int): Component =
        Component.text("★ x$amount", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)

    private fun isStar(item: Item): Boolean =
        item.itemStack.takeIf { it.type == Material.NETHER_STAR }?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.STRING) == true

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPlayerAttempt(event: PlayerAttemptPickupItemEvent) {
        if (!isStar(event.item)) return
        event.isCancelled = true
        event.item.itemStack.itemMeta.persistentDataContainer.get(key, PersistentDataType.STRING)?.let {
            listener?.invoke(it, PlayerId(event.player.uniqueId))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onEntityPickup(event: EntityPickupItemEvent) {
        if (isStar(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onHopper(event: InventoryPickupItemEvent) {
        if (isStar(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onMerge(event: ItemMergeEvent) {
        if (isStar(event.entity) || isStar(event.target)) event.isCancelled = true
    }

    private companion object {
        const val MAX_STACK = 64
    }
}
