package com.astarworks.astera.match.adapter.paper

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
public class PaperStarDropWorld(private val plugin: JavaPlugin) : IStarDropWorld, Listener {
    private val server = plugin.server
    private val key = NamespacedKey(plugin, "star_drop")
    private var listener: ((String, PlayerId) -> Unit)? = null

    init {
        server.pluginManager.registerEvents(this, plugin)
    }

    override fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String? {
        val w = server.getWorld(world.value) ?: return null
        val handle = UUID.randomUUID().toString()
        val stack = ItemStack(Material.NETHER_STAR, amount.coerceIn(1, MAX_STACK))
        stack.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, handle) }
        val item = w.dropItem(Location(w, position.x, position.y, position.z), stack) { e ->
            e.isGlowing = true
            e.isInvulnerable = true
            e.setUnlimitedLifetime(true)
            e.setCanMobPickup(false)
            e.pickupDelay = pickupDelayTicks.coerceAtLeast(0)
            e.customName(label(amount))
            e.isCustomNameVisible = true
            e.velocity = velocity?.let { Vector(it.x, it.y, it.z) } ?: Vector(0.0, 0.0, 0.0)
        }
        // The entity UUID is the lookup key, so it can be located again without a side table.
        return item.uniqueId.toString()
    }

    override fun locate(handle: String): Vec3? {
        val e = server.getEntity(UUID.fromString(handle)) as? Item ?: return null
        if (!e.isValid) return null
        return Vec3(e.location.x, e.location.y, e.location.z)
    }

    override fun relabel(handle: String, amount: Int) {
        val e = server.getEntity(UUID.fromString(handle)) as? Item ?: return
        e.customName(label(amount))
        e.itemStack = e.itemStack.also { it.amount = amount.coerceIn(1, MAX_STACK) }
    }

    override fun remove(handle: String) {
        (server.getEntity(UUID.fromString(handle)) as? Item)?.remove()
    }

    override fun take(handle: String, player: PlayerId) {
        val item = server.getEntity(UUID.fromString(handle)) as? Item ?: return
        server.getPlayer(player.uuid)?.playPickupItemAnimation(item)
        item.remove()
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
        listener?.invoke(event.item.uniqueId.toString(), PlayerId(event.player.uniqueId))
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
