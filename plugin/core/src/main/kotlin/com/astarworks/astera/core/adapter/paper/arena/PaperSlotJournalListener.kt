package com.astarworks.astera.core.adapter.paper.arena

import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent

/**
 * Retain the first pre-edit state of every block a match changes, including blocks outside the island: player edits,
 * explosions, buckets, doors/beds, fire, fluids, falling blocks / endermen and pistons. All handlers run at MONITOR,
 * i.e. before the change is applied, so [Block.getBlockData] is still the previous state.
 */
internal class PaperSlotJournalListener(private val arenas: PaperArenaWorld) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (event is BlockMultiPlaceEvent) event.replacedBlockStates.forEach { arenas.remember(it.block, it.blockData) }
        else arenas.remember(event.block, event.blockReplacedState.blockData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplosion(event: EntityExplodeEvent) { event.blockList().forEach { arenas.remember(it) } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().forEach { arenas.remember(it) } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) { arenas.remember(event.block) }

    /** Fluid flow: the block the liquid spreads into. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) { arenas.remember(event.toBlock) }

    /** Falling blocks (the origin turning to air and the landing cell), endermen, wither, farmland trampling. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) { arenas.remember(event.block) }

    /** Snow / ice / fire spread (`BlockSpreadEvent` is a form event) and similar. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) { arenas.remember(event.block) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        arenas.remember(event.block.getRelative(event.direction)) // the piston head
        event.blocks.forEach { moved -> arenas.remember(moved); arenas.remember(moved.getRelative(event.direction)) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        arenas.remember(event.block.getRelative(event.direction)) // the piston head
        event.blocks.forEach { pulled ->
            arenas.remember(pulled)
            arenas.remember(pulled.getRelative(event.direction))
            arenas.remember(pulled.getRelative(event.direction.oppositeFace))
        }
    }
}
