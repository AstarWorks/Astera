package com.astarworks.astera.core.application.port.outbound.afk

import com.astarworks.astera.core.domain.afk.ActivityKind
import com.astarworks.astera.core.domain.afk.AfkRecord
import com.astarworks.astera.core.domain.afk.AfkStatus
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — per-player AFK / activity tracker.
 *
 * Activity sources (Paper listeners, Wave 5a skill events) call
 * [recordActivity] on every relevant signal. A periodic sweeper
 * (Wave 5q `AfkSweepScheduler`) calls [status] each tick window to
 * promote players to Warning / AFK and publishes
 * [com.astarworks.astera.core.domain.event.PlayerAfkWarned] /
 * [com.astarworks.astera.core.domain.event.PlayerAfkKicked] when the
 * state changes.
 *
 * The match plumbing subscribes to those events to swap AFK players out
 * mid-match (Wave 5w `MatchFoundUseCase` + leaver penalty).
 */
public interface IAfkDetector {

    public fun recordActivity(playerId: PlayerId, kind: ActivityKind, atTick: Long)

    public fun status(playerId: PlayerId): AfkStatus

    /**
     * Returns one record for every player currently in Warning or AFK
     * status. Active players are intentionally omitted to keep the
     * snapshot O(active-afkers) rather than O(online-players).
     */
    public fun activeAfkers(): Collection<AfkRecord>

    /**
     * Reset all tracking for [playerId]. Called on Paper PlayerQuitEvent
     * + on `IMatch.assignPlayer` to avoid carrying stale state across
     * sessions / matches.
     */
    public fun reset(playerId: PlayerId)
}
