package com.astarworks.astera.core.application.port.outbound.lobby

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only view of "is this player waiting in a match queue?" (ADR-0248). Core cannot see the Match plugin's
 * matchmaker, so Match registers this port; it drives the `state:queued` / `state:idle` visibility of kit entries
 * and menu tiles (Leave queue vs. Play).
 */
public fun interface ILobbyQueueState {
    public fun isQueued(playerId: PlayerId): Boolean
}

/**
 * Asks the lobby to rebuild a player's hotbar kit and crafting-grid buttons (ADR-0248), e.g. after a queue join
 * or leave changed which `state:` entries apply. Registered by Core's Paper bindings; safe from any thread.
 */
public fun interface ILobbyKitRefresher {
    public fun refresh(playerId: PlayerId)
}
