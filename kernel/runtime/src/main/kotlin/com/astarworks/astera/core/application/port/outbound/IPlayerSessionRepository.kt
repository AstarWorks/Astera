package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession

/**
 * Outbound port: per-player session state lookup + write (Wave 5e).
 *
 * In-memory by default (Wave 5e); no Postgres overlay — sessions are
 * runtime-only and reconstructed at `PlayerJoined`. The port exists so
 * the Loading state can be observed by Wave 6b's `BukkitEventAdapter`
 * action-lock enforcer.
 */
public interface IPlayerSessionRepository {

    /** Returns the current session for [playerId], or `null` if not tracked. */
    public fun find(playerId: PlayerId): PlayerSession?

    /** Replace any existing session for [playerId] with [session]. */
    public fun upsert(session: PlayerSession)

    /** Remove tracking for [playerId] (e.g., on disconnect cleanup). */
    public fun remove(playerId: PlayerId)

    /** Snapshot of every tracked session — admin / debug. */
    public fun all(): List<PlayerSession>
}
