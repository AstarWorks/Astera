package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Implements [IMatchTeamLookup] (declared in core) by consulting the
 * [IMatchRepository] owned by the Match plugin.
 *
 * Port lives in core (not combat) so neither Combat nor Match plugins
 * depend on each other (avoids the Combat → Weapons → Match → Combat
 * cycle). Match wires this adapter onto the shared `ServiceRegistry`;
 * Combat resolves the port at runtime via `services.get<IMatchTeamLookup>()`.
 *
 * Lookup semantics:
 * - Find the player's currently-active match via
 *   [IMatchRepository.findMatchOf]. Returns `null` when the player is not in
 *   any tracked match — Combat's `FriendlyFirePolicy.BlockSameTeam` interprets
 *   `null` team as "do not block", so unknown participants fall through to
 *   normal damage handling.
 * - Within that match, return the [TeamId] of the team containing the player.
 *   Returns `null` when the match exists but no team membership is recorded
 *   (shouldn't happen for `findMatchOf` matches — included as defensive
 *   fallback in case the repository contract changes).
 */
public class MatchRepositoryTeamLookup(
    private val matchRepository: IMatchRepository,
) : IMatchTeamLookup {

    override fun teamOf(playerId: PlayerId): TeamId? {
        val match = matchRepository.findMatchOf(playerId) ?: return null
        return match.teams.firstOrNull { team -> playerId in team.members }?.id
    }

    override fun matchOf(playerId: PlayerId): com.astarworks.astera.core.domain.match.MatchId? =
        matchRepository.findMatchOf(playerId)?.id
}
