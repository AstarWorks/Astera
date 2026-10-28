package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.paper.lobby.TrainingItemTag
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.items.application.port.outbound.ITrainingInventory
import com.astarworks.astera.items.application.port.outbound.ITrainingRackGateway
import com.astarworks.astera.items.application.port.outbound.RackFrame
import com.astarworks.astera.items.application.service.training.TrainingRackService
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper [ITrainingRackGateway] (F3-11): one non-persistent, fixed item frame per weapon, tagged
 * with [TAG] and the item id so a crash or `/reload` leaves no stray frames (they are swept on the next
 * [ensure]). Main thread only.
 */
internal class PaperTrainingRackGateway(
    plugin: JavaPlugin,
    private val itemIcon: (ItemId) -> ItemStack?,
) : ITrainingRackGateway {

    private val server: Server = plugin.server
    private val itemKey: NamespacedKey = NamespacedKey(plugin, "rack_item")

    override fun ensure(world: WorldId, frames: List<RackFrame>): Int? {
        val bukkitWorld = server.getWorld(world.value) ?: return null
        val wanted = frames.associateBy { Triple(it.slot.x, it.slot.y, it.slot.z) }
        val seen = HashSet<Triple<Int, Int, Int>>()
        for (frame in bukkitWorld.entities.filterIsInstance<ItemFrame>()) {
            if (TAG !in frame.scoreboardTags) continue
            val cell = Triple(frame.location.blockX, frame.location.blockY, frame.location.blockZ)
            val want = wanted[cell]
            val shown = frame.persistentDataContainer.get(itemKey, PersistentDataType.STRING)
            if (want == null || shown != want.itemId.value || !seen.add(cell)) frame.remove()
        }
        var present = seen.size
        for ((cell, frame) in wanted) {
            if (cell in seen) continue
            val face = runCatching { BlockFace.valueOf(frame.slot.facing.uppercase()) }.getOrDefault(BlockFace.EAST)
            val loc = Location(bukkitWorld, cell.first.toDouble(), cell.second.toDouble(), cell.third.toDouble())
            bukkitWorld.getChunkAt(loc).load()
            val icon = itemIcon(frame.itemId) ?: continue
            bukkitWorld.spawn(loc, ItemFrame::class.java) { f ->
                f.setFacingDirection(face, true)
                com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(f, "training-rack")
                f.isFixed = true
                f.isInvulnerable = true
                f.setItem(icon, false)
                f.addScoreboardTag(TAG)
                f.persistentDataContainer.set(itemKey, PersistentDataType.STRING, frame.itemId.value)
            }
            present++
        }
        return present
    }

    override fun removeAll() {
        for (w in server.worlds) {
            w.entities.filterIsInstance<ItemFrame>().filter { TAG in it.scoreboardTags }.forEach { it.remove() }
        }
    }

    /** The weapon a rack frame shows, or `null` for any other entity. */
    fun itemOf(frame: ItemFrame): ItemId? {
        if (TAG !in frame.scoreboardTags) return null
        return frame.persistentDataContainer.get(itemKey, PersistentDataType.STRING)?.let(::ItemId)
    }

    companion object {
        const val TAG: String = "astera_rack_frame"
    }
}

/** Paper [ITrainingInventory] over the [TrainingItemTag] PDC marker. */
internal class PaperTrainingInventory(private val server: Server) : ITrainingInventory {

    override fun holds(playerId: PlayerId, itemId: ItemId): Boolean {
        val player = server.getPlayer(playerId.uuid) ?: return false
        return player.inventory.contents.any { TrainingItemTag.itemId(it) == itemId.value }
    }

    override fun strip(playerId: PlayerId): Int {
        val player = server.getPlayer(playerId.uuid) ?: return 0
        return stripPlayer(player)
    }

    override fun holders(): Set<PlayerId> =
        server.onlinePlayers
            .filter { p -> p.inventory.contents.any(TrainingItemTag::isTraining) || TrainingItemTag.isTraining(p.itemOnCursor) }
            .map { PlayerId(it.uniqueId) }
            .toSet()

    companion object {
        fun stripPlayer(player: Player): Int {
            var removed = TrainingItemTag.strip(player.inventory)
            if (TrainingItemTag.isTraining(player.itemOnCursor)) {
                player.setItemOnCursor(null)
                removed++
            }
            return removed
        }
    }
}

/**
 * Rack interaction plus the lobby-only guarantees for training stacks (ADR-0200): right-clicking a
 * frame claims its weapon; training stacks cannot be dropped or moved into another container, and are
 * stripped on quit and when the player leaves the training world. Match entry is handled by
 * [TrainingRackService.onMatchStarted].
 */
internal class PaperTrainingRackListener(
    private val rack: TrainingRackService,
    private val frames: PaperTrainingRackGateway,
    private val rangeWorld: () -> String?,
) : Listener {

    // Not ignoreCancelled: the lobby rules cancel item-frame interaction for non-builders.
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        val itemId = frames.itemOf(frame) ?: return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        rack.claim(PlayerId(event.player.uniqueId), itemId)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFrameHit(event: EntityDamageByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frames.itemOf(frame) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFrameBreak(event: HangingBreakEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frames.itemOf(frame) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (TrainingItemTag.isTraining(event.itemDrop.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        val hotbar = if (event.hotbarButton >= 0) event.whoClicked.inventory.getItem(event.hotbarButton) else null
        if (TrainingItemTag.isTraining(event.currentItem) ||
            TrainingItemTag.isTraining(event.cursor) ||
            TrainingItemTag.isTraining(hotbar)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        if (TrainingItemTag.isTraining(event.oldCursor)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        PaperTrainingInventory.stripPlayer(event.player)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (event.player.world.name != rangeWorld()) PaperTrainingInventory.stripPlayer(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf { TrainingItemTag.isTraining(it) }
        event.itemsToKeep.removeIf { TrainingItemTag.isTraining(it) }
    }
}
