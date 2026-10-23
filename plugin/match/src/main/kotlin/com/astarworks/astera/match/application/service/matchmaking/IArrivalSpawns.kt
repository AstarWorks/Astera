package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnPoint

/**
 * Where a player handed over from the lobby must stand the moment they appear on a game server, before the
 * match exists. Implemented by [MatchAssignmentReceiver]; consumed by the Paper adapter, which sets the login
 * location (so no later teleport races the client's first movement packets) and blocks damage while waiting.
 */
public interface IArrivalSpawns {

    /** The team spawn on the assigned stage for an assigned, not-yet-started player; `null` for anyone else. */
    public fun spawnFor(playerId: PlayerId): SpawnPoint?

    /** True while [playerId] holds a seat in the current assignment and its match has not started yet. */
    public fun isWaiting(playerId: PlayerId): Boolean
}
