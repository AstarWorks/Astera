package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats

public interface IPlayerStatsRepository {
    public fun find(playerId: PlayerId): PlayerStats?
    public fun upsert(stats: PlayerStats): PlayerStats
    public fun all(): Collection<PlayerStats>
}
