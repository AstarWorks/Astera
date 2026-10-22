package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe in-memory [IPlayerXpRepository]; the Postgres-less fallback. */
public class InMemoryPlayerXpRepository : IPlayerXpRepository {
    private val totals: ConcurrentHashMap<PlayerId, Long> = ConcurrentHashMap()

    override fun totalXp(playerId: PlayerId): Long = totals[playerId] ?: 0L

    override fun addXp(playerId: PlayerId, amount: Long): Long =
        totals.merge(playerId, amount, Long::plus)!!
}
