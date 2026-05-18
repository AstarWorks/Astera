package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IMatchTeamLookup] for use case tests.
 *
 * Backed by a single `PlayerId → TeamId` map. Tests set the world up by
 * calling [place] for each participant, then exercise the use case under
 * test. Players not [place]d return `null` from [teamOf] — matching the
 * port contract "player is not currently in a match".
 *
 * Per ADR-0022 (`Match` plugin owns the production impl), this fake is
 * registered into the `ServiceRegistry` by the test harness so cross-plugin
 * resolution (`services.get<IMatchTeamLookup>()`) succeeds without standing
 * up a real Match.
 */
public class FakeMatchTeamLookup : IMatchTeamLookup {

    private val teams: MutableMap<PlayerId, TeamId> = mutableMapOf()

    /** Snapshot of the current player → team assignments. For test readability. */
    public val assignments: Map<PlayerId, TeamId>
        get() = teams.toMap()

    /**
     * Register [playerId] as a member of [teamId]. Overwrites any previous
     * assignment for the same player.
     */
    public fun place(playerId: PlayerId, teamId: TeamId) {
        teams[playerId] = teamId
    }

    /** Remove [playerId] from the lookup — subsequent [teamOf] calls return `null`. */
    public fun remove(playerId: PlayerId) {
        teams.remove(playerId)
    }

    /** Clear all assignments. */
    public fun clear() {
        teams.clear()
    }

    override fun teamOf(playerId: PlayerId): TeamId? = teams[playerId]
}
