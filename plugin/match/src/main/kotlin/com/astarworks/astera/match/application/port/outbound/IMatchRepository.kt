package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.Repository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.match.Match
import com.astarworks.astera.match.domain.match.MatchId
import com.astarworks.astera.match.domain.match.MatchPhase

public interface IMatchRepository : Repository<MatchId, Match> {
    public fun findActiveMatches(): Collection<Match>
    public fun findMatchOf(playerId: PlayerId): Match?
    public fun findInPhase(phase: MatchPhase): Collection<Match>
}
