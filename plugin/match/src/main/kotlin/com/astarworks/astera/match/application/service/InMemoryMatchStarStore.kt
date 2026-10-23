package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.star.StarError
import java.util.concurrent.ConcurrentHashMap

/** Process-local [IMatchStarStore]: the match lives on one server, so the counters do too. */
public class InMemoryMatchStarStore : IMatchStarStore {

    private val counts: MutableMap<MatchId, MutableMap<PlayerId, Int>> = ConcurrentHashMap()

    override fun get(matchId: MatchId, playerId: PlayerId): Int = counts[matchId]?.get(playerId) ?: 0

    override fun add(matchId: MatchId, playerId: PlayerId, amount: Int): Int {
        require(amount > 0) { "amount must be > 0 (was $amount)" }
        val perMatch = counts.computeIfAbsent(matchId) { ConcurrentHashMap() }
        return perMatch.merge(playerId, amount, Int::plus) ?: amount
    }

    override fun spend(matchId: MatchId, playerId: PlayerId, amount: Int): Result<Int, StarError> {
        if (amount <= 0) return Result.failure(StarError.InvalidAmount(amount))
        val perMatch = counts[matchId]
        val have = perMatch?.get(playerId) ?: 0
        if (perMatch == null || have < amount) return Result.failure(StarError.Insufficient(have, amount))
        val left = perMatch.compute(playerId) { _, cur -> (cur ?: 0) - amount }!!
        return Result.success(left)
    }

    override fun clear(matchId: MatchId) {
        counts.remove(matchId)
    }
}
