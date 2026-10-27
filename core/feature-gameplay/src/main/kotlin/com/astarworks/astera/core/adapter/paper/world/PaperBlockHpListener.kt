package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Vanilla world changes invalidate accumulated damage even when the replacement has the same material. */
public class PaperBlockHpListener(
    private val blocks: IBlockStateGateway,
    private val animation: () -> IBlockBreakAnimationGateway?,
) : Listener {
    private fun clear(block: Block) {
        val world = WorldId(block.world.name)
        val at = Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        blocks.clearDamage(world, at)
        animation()?.cancelBreakAnimation(world, at)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) { clear(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlace(event: BlockPlaceEvent) { clear(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onMultiPlace(event: BlockMultiPlaceEvent) { event.replacedBlockStates.forEach { clear(it.block) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onExplode(event: BlockExplodeEvent) { event.blockList().forEach(::clear) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onEntityExplode(event: EntityExplodeEvent) { event.blockList().forEach(::clear) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBurn(event: BlockBurnEvent) { clear(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onEntityChange(event: EntityChangeBlockEvent) { clear(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onExtend(event: BlockPistonExtendEvent) {
        event.blocks.forEach { clear(it); clear(it.getRelative(event.direction)) }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onRetract(event: BlockPistonRetractEvent) {
        event.blocks.forEach { clear(it); clear(it.getRelative(event.direction)) }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onUnload(event: WorldUnloadEvent) {
        val world = WorldId(event.world.name)
        blocks.clearWorldDamage(world).forEach { animation()?.cancelBreakAnimation(world, it) }
    }
}
