package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.regionScope
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Turns a match participant's left-click on a custom cover block (`content/blocks/`, ADR-0090) into one tool swing
 * ([HitCustomBlockUseCase]). Authored visuals are identified by full block state, so vanilla digging is cancelled and only the
 * field tools hurt them. Outside a match nothing here applies.
 */
public class PaperCoverToolListener(
    plugin: JavaPlugin,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
    private val registry: ICustomBlockRegistry,
    private val blocks: com.astarworks.astera.core.application.port.outbound.IBlockStateGateway,
    private val matches: () -> IMatchRepository?,
    private val useCase: () -> HitCustomBlockUseCase?,
    private val exclusion: () -> IAreaBreakExclusion?,
) : Listener {

    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.HIGH)
    public fun onSwing(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!isCoverInMatch(player, block)) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.isCancelled = true
        useCase()?.hit(PlayerId(player.uniqueId), WorldId(block.world.name), Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()), heldItemId(player))
    }

    /** Vanilla has already checked placement/reach and will consume exactly one of the actual held stack. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onPlace(event: org.bukkit.event.block.BlockPlaceEvent) {
        val pdc = event.itemInHand.itemMeta?.persistentDataContainer ?: return
        val key = pdc.keys.firstOrNull { it.key == PaperItemMaterializer.ITEM_ID_KEY } ?: return
        val id = pdc.get(key, PersistentDataType.STRING)?.let { runCatching { ItemId(it) }.getOrNull() } ?: return
        val spec = registry.resolveByItem(id) ?: return
        if (spec.visual == null) return
        if (matches()?.findMatchOf(PlayerId(event.player.uniqueId)) == null) {
            event.isCancelled = true
            return
        }
        val block = event.blockPlaced
        val result = blocks.placeTemporaryBlock(WorldId(block.world.name),
            Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()), spec, PlayerId(event.player.uniqueId))
        if (result is com.astarworks.astera.core.domain.Result.Failure) event.isCancelled = true
    }

    /** Survival dig start: never let vanilla chew through cover. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDig(event: BlockDamageEvent) {
        if (isCoverInMatch(event.player, event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) {
        if (isCoverInMatch(event.player, event.block)) event.isCancelled = true
    }

    private fun isCoverInMatch(player: Player, block: Block): Boolean =
        registry.resolveByBlockData(block.blockData.asString) != null &&
            // Blocks another system owns (Mid ore nodes share a base material with cover) keep their own mining rules.
            exclusion()?.isExcluded(WorldId(block.world.name), Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())) != true &&
            matches()?.findMatchOf(PlayerId(player.uniqueId)) != null

    // Note blocks are reserved rendering carriers. Disable tuning for ALL notes, so ordinary notes cannot
    // enter the reserved states. Note=0 remains vanilla placement; authored notes are always 1..9.
    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onNoteInteract(event: PlayerInteractEvent) {
        if (event.clickedBlock?.type != org.bukkit.Material.NOTE_BLOCK) return
        event.setUseInteractedBlock(Event.Result.DENY)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onNotePhysics(event: org.bukkit.event.block.BlockPhysicsEvent) {
        if (event.block.type == org.bukkit.Material.NOTE_BLOCK) {
            event.isCancelled = true
            val canonical = canonicalNote(event.block)
            if (canonical != event.block.blockData) event.block.setBlockData(canonical, false)
        }
        // Paper may emit only the root physics event. Snapshot adjacent carriers before propagation,
        // then restore only surviving note blocks (never resurrect broken/replaced cells).
        for (face in listOf(org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
            val neighbor = event.block.getRelative(face)
            if (neighbor.type != org.bukkit.Material.NOTE_BLOCK) continue
            val saved = canonicalNote(neighbor)
            scheduler.runLaterTicks(regionScope(WorldId(neighbor.world.name), Vec3(neighbor.x.toDouble(), neighbor.y.toDouble(), neighbor.z.toDouble())), 1) {
                if (neighbor.type == org.bukkit.Material.NOTE_BLOCK && neighbor.blockData != saved &&
                    (neighbor.blockData as org.bukkit.block.data.type.NoteBlock).note == (saved as org.bukkit.block.data.type.NoteBlock).note)
                    neighbor.setBlockData(saved, false)
            }
        }
    }

    // Shape propagation can change instrument before the root physics event reaches Bukkit. Note is
    // invariant under neighbor/power changes, so recover the authored carrier from its reserved note.
    private fun canonicalNote(block: Block): org.bukkit.block.data.BlockData {
        val current = block.blockData as org.bukkit.block.data.type.NoteBlock
        val spec = registry.all().firstOrNull { it.visual?.state?.get("note") == current.note.id.toString() }
        return spec?.visual?.let { org.bukkit.Bukkit.createBlockData(it.blockData) } ?: current
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onNoteRedstone(event: org.bukkit.event.block.BlockRedstoneEvent) {
        if (event.block.type == org.bukkit.Material.NOTE_BLOCK) event.newCurrent = event.oldCurrent
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onNotePlay(event: org.bukkit.event.block.NotePlayEvent) {
        if (event.block.type == org.bukkit.Material.NOTE_BLOCK) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onPistonExtend(event: org.bukkit.event.block.BlockPistonExtendEvent) {
        if (event.blocks.any { it.type == org.bukkit.Material.NOTE_BLOCK }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onPistonRetract(event: org.bukkit.event.block.BlockPistonRetractEvent) {
        if (event.blocks.any { it.type == org.bukkit.Material.NOTE_BLOCK }) event.isCancelled = true
    }

    private fun heldItemId(player: Player): ItemId? {
        // The item id key lives under the namespace of whichever plugin materialized the item (Items, Core, ...), so match by key name.
        val pdc = player.inventory.itemInMainHand.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == PaperItemMaterializer.ITEM_ID_KEY } ?: return null
        val raw = pdc.get(key, PersistentDataType.STRING) ?: return null
        return runCatching { ItemId(raw) }.getOrNull()
    }
}
