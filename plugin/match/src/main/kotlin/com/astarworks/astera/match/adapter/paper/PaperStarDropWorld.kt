package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.application.port.outbound.ExecutionScope

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
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID

/**
 * A star drop in the world is a dropped NETHER_STAR item entity: glowing, named `★ xN`, stack count = N (capped at
 * 64), never despawning, invulnerable, tagged with a PDC value (this boot's session token + a unique id) so it never
 * merges with another drop and a star left over from an earlier boot is recognised and removed. The glow is white for a
 * single star and gold for a stack of two or more (scoreboard team colour, see [syncGlow]). Its
 * vanilla pickup (player, mob, hopper) is always cancelled; a player touching it is reported to the listener, which
 * credits the in-match star count and removes the entity. The drop never reaches an inventory.
 */
public class PaperStarDropWorld(private val plugin: JavaPlugin, private val listeners: OwnedListeners) : IStarDropWorld, Listener {
    private val server = plugin.server
    private val key = NamespacedKey(plugin, "star_drop")
    private var listener: ((String, PlayerId) -> Unit)? = null

    /** Stars persist in the world (unlimited lifetime) but the drop ledger does not: a star from another boot is an orphan. */
    private val session = UUID.randomUUID().toString().take(8)

    init {
        listeners.register(this)
    }

    override fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String? {
        val w = server.getWorld(world.value) ?: return null
        val handle = "$session:${UUID.randomUUID()}"
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
        syncGlow(item.uniqueId, amount)
        // The entity UUID is the lookup key, so it can be located again without a side table.
        return item.uniqueId.toString()
    }

    override fun executionScope(handle: String): ExecutionScope =
        ExecutionScope.Entity(UUID.fromString(handle))

    override fun locate(handle: String): Vec3? {
        val e = server.getEntity(UUID.fromString(handle)) as? Item ?: return null
        if (!e.isValid) return null
        // A viewer who joined (or got a fresh scoreboard) after the last relabel still needs the stack colour.
        if (e.itemStack.amount >= 2) syncGlow(e.uniqueId, e.itemStack.amount)
        return Vec3(e.location.x, e.location.y, e.location.z)
    }

    override fun relabel(handle: String, amount: Int) {
        val e = server.getEntity(UUID.fromString(handle)) as? Item ?: return
        e.scheduler.execute(plugin, Runnable {
            e.customName(label(amount))
            e.itemStack = e.itemStack.also { it.amount = amount.coerceIn(1, MAX_STACK) }
            syncGlow(e.uniqueId, amount)
        }, null, 1L)
    }

    override fun remove(handle: String) {
        (server.getEntity(UUID.fromString(handle)) as? Item)?.let { item ->
            item.scheduler.execute(plugin, Runnable { dropFromTeams(item.uniqueId); item.remove() }, null, 1L)
        }
    }

    override fun take(handle: String, player: PlayerId) {
        val item = server.getEntity(UUID.fromString(handle)) as? Item ?: return
        item.scheduler.execute(plugin, Runnable {
            server.getPlayer(player.uuid)?.playPickupItemAnimation(item)
            dropFromTeams(item.uniqueId)
            item.remove()
        }, null, 1L)
    }

    override fun onPickupAttempt(listener: (handle: String, player: PlayerId) -> Unit) {
        this.listener = listener
    }

    private fun label(amount: Int): Component =
        Component.text("★ x$amount", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)

    private fun tag(item: Item): String? =
        item.itemStack.takeIf { it.type == Material.NETHER_STAR }?.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING)

    private fun isStar(item: Item): Boolean = tag(item) != null

    /** A star of an earlier boot: nothing tracks it, so it could never be collected. */
    private fun isOrphan(item: Item): Boolean = tag(item)?.startsWith("$session:") == false

    private fun removeOrphan(item: Item) {
        item.scheduler.execute(plugin, Runnable { item.remove() }, null, 1L)
    }

    /** Every scoreboard a viewer can be on (a match gives each player their own); glow colour is read from the viewer's board. */
    private fun boards(): Collection<Scoreboard> =
        (server.onlinePlayers.map { it.scoreboard } + listOfNotNull(server.scoreboardManager?.mainScoreboard)).distinct()

    /** Single star: no team (default white glow). Two or more: the gold team. */
    private fun syncGlow(entity: UUID, amount: Int) {
        val entry = entity.toString()
        for (board in boards()) {
            val team = board.getTeam(MULTI_TEAM)
            if (amount >= 2) {
                val t = team ?: board.registerNewTeam(MULTI_TEAM).also { it.color(NamedTextColor.GOLD) }
                if (!t.hasEntry(entry)) t.addEntry(entry)
            } else if (team != null && team.hasEntry(entry)) {
                team.removeEntry(entry)
            }
        }
    }

    private fun dropFromTeams(entity: UUID) {
        val entry = entity.toString()
        for (board in boards()) board.getTeam(MULTI_TEAM)?.takeIf { it.hasEntry(entry) }?.removeEntry(entry)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPlayerAttempt(event: PlayerAttemptPickupItemEvent) {
        if (!isStar(event.item)) return
        event.isCancelled = true
        if (isOrphan(event.item)) { removeOrphan(event.item); return }
        listener?.invoke(event.item.uniqueId.toString(), PlayerId(event.player.uniqueId))
    }

    /** Stars saved with a chunk by an earlier boot (the drop ledger is gone) are swept as the chunk loads. */
    @EventHandler
    public fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (e in event.entities) if (e is Item && isOrphan(e)) removeOrphan(e)
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
        /** Scoreboard team (gold glow) of stacked stars; the nameplate teams use the `ast_np_` prefix. */
        const val MULTI_TEAM = "ast_star_multi"
    }
}
