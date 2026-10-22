package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Durable lifetime-XP total per player, behind `ILevelService` (ADR-0187). Levels
 * are derived from the total by `XpCurve`, so only the total is stored.
 * Persistence overlays the in-memory default at priority 100.
 */
public interface IPlayerXpRepository {
    public fun totalXp(playerId: PlayerId): Long

    /** Atomically adds [amount] (>= 0) and returns the new total. */
    public fun addXp(playerId: PlayerId, amount: Long): Long
}
