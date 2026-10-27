package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/** Mining I/O: opaque block/tool data keeps platform types at the edge. Timing belongs to the caller. */
public interface IToolBlockGateway {
    public data class Surface(val data: String, val hardness: Double)
    public fun surface(world: WorldId, cell: Vec3): Surface?
    public fun heldTool(player: PlayerId): String?
    public fun crack(world: WorldId, cell: Vec3, progress: Double)
    public fun clink(world: WorldId, cell: Vec3)
    public fun breakBlock(world: WorldId, cell: Vec3, player: PlayerId, toolData: String?)
    public fun effect(world: WorldId, cell: Vec3, data: String, placing: Boolean)
    public fun dropCustom(world: WorldId, cell: Vec3, player: PlayerId, item: ItemId)
}
