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

    /** The node [material] was just mined: show debris particles and its break sound at the block (the placeholder swap follows). */
    public fun mined(world: WorldId, pos: Vec3, material: String)

    /** A swing was refused (tool too weak or wrong for the node): show a small deny puff at the node. */
    public fun refused(world: WorldId, pos: Vec3)

    /** A player under the node's Norma level swung at it: play the deny sound at the node. */
    public fun deny(world: WorldId, pos: Vec3)
}
