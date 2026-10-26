package com.astarworks.astera.core.adapter.paper.arena

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockExplodeEvent

/** Retain the first pre-edit state, including player-built blocks outside the island. */
internal class PaperSlotJournalListener(private val arenas: PaperArenaWorld) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) { arenas.remember(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) { arenas.remember(event.block, event.blockReplacedState.blockData) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplosion(event: EntityExplodeEvent) { event.blockList().forEach { arenas.remember(it) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().forEach { arenas.remember(it) } }
}
