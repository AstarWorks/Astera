package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.stats.PlayerStats
import java.util.concurrent.ConcurrentHashMap

public class InMemoryPlayerStatsRepository : IPlayerStatsRepository {

    private val store: ConcurrentHashMap<PlayerId, PlayerStats> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): PlayerStats? = store[playerId]

    override fun upsert(stats: PlayerStats): PlayerStats {
        store[stats.playerId] = stats
        return stats
    }

    override fun all(): Collection<PlayerStats> = store.values.toList()
}
