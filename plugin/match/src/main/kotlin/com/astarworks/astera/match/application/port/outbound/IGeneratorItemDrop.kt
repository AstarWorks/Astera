package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** An ore (or any item) generator pop lying in the world as an ordinary pickable item stack (Paper: a dropped item entity). */
public interface IGeneratorItemDrop {
    /** Drops [amount] of the item [itemId] at [position]; `false` when the item or world is unknown. */
    public fun drop(world: WorldId, position: Vec3, itemId: String, amount: Int): Boolean
}
