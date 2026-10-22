package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default [IBlockEditGuard] (WP5): a skill may edit terrain only where a match
 * arena is. "Arena" is either
 *
 *  - a registered stage region containing the position, or
 *  - the caster is currently in a match (their team is known) -- matches run in
 *    their own world, and stage regions are not populated everywhere yet.
 *
 * Lobby, free-roam and any other world therefore stay untouched, regardless of
 * what a skill (or a misconfigured mob) tries.
 */
public class ArenaBlockEditGuard(
    private val regionIndex: () -> IStageRegionIndex?,
    private val teamLookup: () -> IMatchTeamLookup?,
) : IBlockEditGuard {

    override fun canEdit(actor: PlayerId, world: WorldId, pos: Vec3): Boolean {
        if (regionIndex()?.regionsAt(world, pos)?.isNotEmpty() == true) return true
        return teamLookup()?.teamOf(actor) != null
    }
}
