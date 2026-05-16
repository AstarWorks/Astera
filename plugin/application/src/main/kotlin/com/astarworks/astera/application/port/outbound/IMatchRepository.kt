package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.match.Match
import com.astarworks.astera.domain.model.match.MatchId
import com.astarworks.astera.domain.model.match.MatchPhase
import com.astarworks.astera.domain.model.player.PlayerId

public interface IMatchRepository : Repository<MatchId, Match> {
    public fun findActiveMatches(): Collection<Match>
    public fun findMatchOf(playerId: PlayerId): Match?
    public fun findInPhase(phase: MatchPhase): Collection<Match>
}
