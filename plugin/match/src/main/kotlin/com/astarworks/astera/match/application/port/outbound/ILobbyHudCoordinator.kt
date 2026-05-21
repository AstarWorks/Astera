package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port (Wave 5w / ADR-0089 γ): show or hide the lobby waiting-room
 * HUD for a player.
 *
 * The implementation reads queue state from [IMatchmaker] and pushes
 * presentation updates through Wave 5b vendor-neutral HUD primitives
 * (`IBossBarManager`, `IScoreboardManager`, `IMcPlayer.sendActionBar`).
 *
 * **Lifecycle expectation**: callers invoke [onEnter] when the player joins
 * the lobby world (or enters a hub region), [onLeave] when they leave (or
 * transition into a match), and [refresh] from a tick-driven scheduler so
 * the ETA countdown and queue-position number stay current.
 */
public interface ILobbyHudCoordinator {

    /** Begin showing the lobby HUD to [playerId]. Idempotent. */
    public fun onEnter(playerId: PlayerId)

    /** Hide the lobby HUD from [playerId]. Idempotent. */
    public fun onLeave(playerId: PlayerId)

    /**
     * Re-render the HUD for [playerId] using the current matchmaker state
     * (queue position / ETA / mode). Called from a 1s scheduler tick by
     * default; no-op for players who are not currently being tracked.
     */
    public fun refresh(playerId: PlayerId)

    /** Snapshot of player ids currently being shown the lobby HUD. */
    public fun trackedPlayers(): Set<PlayerId>
}
