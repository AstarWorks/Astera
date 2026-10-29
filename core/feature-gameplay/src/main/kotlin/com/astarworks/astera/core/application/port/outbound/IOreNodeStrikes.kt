package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Mid ore nodes as a skill target. A tool skill (`break_area` with a tool class) that reaches a node cell hits it through
 * this port, so the node's own rules (tool affinity, Norma level gate, HP, drops, regrowth) apply exactly as to a hand mine;
 * Combat never knows the node rules. Match registers the implementation.
 */
public interface IOreNodeStrikes {

    /** True when the cell of [pos] in [world] is a Mid ore node of a running match (mined and regrowing cells included). */
    public fun isNode(world: WorldId, pos: Vec3): Boolean

    /** What a skill strike did to a node. */
    public enum class Result {
        /** Not a node, regrowing, level-locked or the wrong tool: nothing happened. */
        NONE,

        /** The node lost HP. */
        HIT,

        /** The strike finished the node: drops given, block swapped for its placeholder, regrowth started. */
        MINED,
    }

    /** [player] strikes the node at [pos] with a tool skill: [tool] is the skill's tool class and item tier, [blockDamage] the skill's `block_damage`. */
    public fun strike(player: PlayerId, world: WorldId, pos: Vec3, tool: ToolType, blockDamage: Int): Result
}
