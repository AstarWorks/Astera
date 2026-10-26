package com.astarworks.astera.economy.application.port.outbound.betting

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingStatus

/**
 * Outbound port: read + write [BettingMatch] records (Wave 5bet / ADR-0103).
 *
 * Priority-0 InMemory impl lives in `economy.application.service.betting`;
 * priority-100 Postgres overlay lives in `plugin:persistence` (V38
 * `betting_matches` table).
 */
public interface IBettingMatchRepository {

    public suspend fun save(match: BettingMatch): BettingMatch

    public suspend fun findById(id: BettingMatchId): BettingMatch?

    /**
     * Most recent matches owned by [playerId] in EITHER role (challenger or
     * opponent), ordered by `createdAt DESC`, capped at [limit].
     */
    public suspend fun recentForPlayer(playerId: PlayerId, limit: Int = 25): List<BettingMatch>

    /** Currently `PendingAcceptance` or `Active` matches involving [playerId]. */
    public suspend fun openForPlayer(playerId: PlayerId): List<BettingMatch>

    /**
     * Resolve a betting match by the underlying [MatchId] of the PvP
     * encounter. Used by `ResolveBettingMatchOnMatchEndedListener` when the
     * Match plugin publishes [com.astarworks.astera.core.domain.event.MatchEnded].
     */
    public suspend fun findByMatchId(matchId: MatchId): BettingMatch?

    /**
     * All matches in status [status]. Phase 1: used by the expiry sweeper to
     * find `PendingAcceptance` records whose deadline has passed.
     */
    public suspend fun findByStatus(status: BettingStatus): List<BettingMatch>
}
