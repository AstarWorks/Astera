package com.astarworks.astera.match.domain.orenode

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.ToolTier

/** One item stack a mined node gives. */
public data class OreDrop(val itemId: String, val count: Int)

/**
 * A group of identical Mid ore nodes of one stage (`content/ore-nodes/<stage-id>.yaml`): the vanilla [material] they show,
 * the [placeholder] material standing in for [regrowSeconds] after a mine, the pickaxe tier needed, the stack they give,
 * and where they stand ([cells], stage-relative x/z; y is absolute).
 */
public data class OreNodeSpec(
    val material: String,
    val placeholder: String,
    val regrowSeconds: Int,
    val requiredTier: ToolTier,
    val drops: List<OreDrop>,
    val cells: List<Vec3>,
)
