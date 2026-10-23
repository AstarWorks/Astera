package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.norma.NormaProgress

/** Per-(match, player) Norma state. In memory on the host server; [clear] drops a match at its end. */
public interface IMatchNormaStore {
    public fun get(matchId: MatchId, playerId: PlayerId): NormaProgress
    public fun put(matchId: MatchId, playerId: PlayerId, progress: NormaProgress)
    public fun clear(matchId: MatchId)
}
