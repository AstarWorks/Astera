package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Sets the vanilla material of one block of a match world (Paper: on the region that owns it). */
public interface IOreNodeWorld {
    public fun setBlock(world: WorldId, pos: Vec3, material: String)

    /** A hit that did not finish the node: show the crack overlay for [hp] of [maxHp] left and play the hit. */
    public fun hit(world: WorldId, pos: Vec3, hp: Int, maxHp: Int)

    /** The node is mined (or reset): clear its crack overlay. */
    public fun clearCrack(world: WorldId, pos: Vec3)
}
