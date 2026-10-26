package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingStatus

/**
 * In-memory [IBettingMatchRepository] for tests + Phase 1 boot (Wave 5bet /
 * ADR-0103). Postgres overlay lives in `:plugin:persistence` at priority 100.
 */
public class InMemoryBettingMatchRepository : IBettingMatchRepository {

    private val byId: MutableMap<BettingMatchId, BettingMatch> = mutableMapOf()

    override suspend fun save(match: BettingMatch): BettingMatch {
        byId[match.id] = match
        return match
    }

    override suspend fun findById(id: BettingMatchId): BettingMatch? = byId[id]

    override suspend fun recentForPlayer(playerId: PlayerId, limit: Int): List<BettingMatch> {
        require(limit > 0) { "limit must be > 0, got $limit" }
        return byId.values
            .asSequence()
            .filter { it.challengerId == playerId || it.opponentId == playerId }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    override suspend fun openForPlayer(playerId: PlayerId): List<BettingMatch> =
        byId.values
            .asSequence()
            .filter { it.challengerId == playerId || it.opponentId == playerId }
            .filter { it.status == BettingStatus.PendingAcceptance || it.status == BettingStatus.Active }
            .sortedByDescending { it.createdAt }
            .toList()

    override suspend fun findByMatchId(matchId: MatchId): BettingMatch? =
        byId.values.firstOrNull { it.matchId == matchId }

    override suspend fun findByStatus(status: BettingStatus): List<BettingMatch> =
        byId.values.filter { it.status == status }.sortedBy { it.createdAt }

    /** Phase 1 helper for tests: drop everything. */
    public fun clear() {
        byId.clear()
    }
}
