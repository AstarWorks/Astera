package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind

/**
 * One Annihilation pillar of a stage (WP10): the block of [team]'s [tier]
 * pillar, in the stage's world.
 *
 * [position] is the block coordinate (integral doubles, the block's minimum
 * corner); the pillar's hit-box centre is [centre]. [block] is what the world
 * adapter materialises there while the pillar stands. [hp] overrides the
 * mode's per-pillar HP when set (e.g. a beefier Tier 3). [affinity] is the tool class (pickaxe / shovel / axe) the
 * pillar's material is mined with: a matching tool deals more damage to it.
 */
public data class StagePillar(
    val team: TeamId,
    val tier: NexusPillarTier,
    val position: Vec3,
    val block: BlockType,
    val affinity: ToolKind,
    val hp: Int? = null,
) {
    init {
        require(affinity != ToolKind.NONE) { "StagePillar.affinity must be pickaxe, shovel or axe (team=${team.value}, tier=$tier)" }
        require(hp == null || hp > 0) { "StagePillar.hp must be > 0 (team=${team.value}, tier=$tier, hp=$hp)" }
    }

    /** Centre of the pillar's block. */
    public val centre: Vec3 get() = Vec3(position.x + 0.5, position.y + 0.5, position.z + 0.5)
}
