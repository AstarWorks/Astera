package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cells an area skill (`break_area`) must never remove, whatever its power: blocks owned by another system that
 * has its own mining rules (Mid ore nodes are mined one at a time with a pickaxe tier). Feature plugins register
 * an implementation; skills ask through the registry, so Combat does not depend on them.
 */
public fun interface IAreaBreakExclusion {

    /** True when the block in the cell of [pos] in [world] may not be broken by an area skill. */
    public fun isExcluded(world: WorldId, pos: Vec3): Boolean
}
