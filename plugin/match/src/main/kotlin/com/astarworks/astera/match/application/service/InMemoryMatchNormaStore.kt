package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.domain.norma.NormaProgress
import java.util.concurrent.ConcurrentHashMap

public class InMemoryMatchNormaStore : IMatchNormaStore {
    private val state: MutableMap<MatchId, MutableMap<PlayerId, NormaProgress>> = ConcurrentHashMap()

    override fun get(matchId: MatchId, playerId: PlayerId): NormaProgress = state[matchId]?.get(playerId) ?: NormaProgress()

    override fun put(matchId: MatchId, playerId: PlayerId, progress: NormaProgress) {
        state.computeIfAbsent(matchId) { ConcurrentHashMap() }[playerId] = progress
    }

    override fun clear(matchId: MatchId) {
        state.remove(matchId)
    }
}
