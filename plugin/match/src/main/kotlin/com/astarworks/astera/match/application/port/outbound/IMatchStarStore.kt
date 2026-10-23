package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.star.StarError

/**
 * In-match star counters, one per (match, player). Stars are an item of the match: everyone starts at 0, nothing is
 * persisted, and [clear] drops the whole match at its end. Deliberately not a wallet, ledger or attribute container.
 */
public interface IMatchStarStore {
    /** Current count; 0 for an unknown (match, player). */
    public fun get(matchId: MatchId, playerId: PlayerId): Int

    /** Adds [amount] (> 0) and returns the new total. */
    public fun add(matchId: MatchId, playerId: PlayerId, amount: Int): Int

    /** Removes [amount] (> 0) when affordable and returns the remaining count. */
    public fun spend(matchId: MatchId, playerId: PlayerId, amount: Int): Result<Int, StarError>

    /** Forgets every counter of [matchId]. */
    public fun clear(matchId: MatchId)
}
