package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/** Test fixture: in-memory PlayerId → TeamId map for friendly-fire tests. */
public class FakeMatchTeamLookup(
    private val teams: MutableMap<PlayerId, TeamId> = mutableMapOf(),
) : IMatchTeamLookup {
    override fun teamOf(playerId: PlayerId): TeamId? = teams[playerId]

    public fun assign(playerId: PlayerId, team: TeamId) {
        teams[playerId] = team
    }
}
