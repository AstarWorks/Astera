package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.match.result.LastMatchRecord
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Each player's most recent match result. Written on the game server when
 * rewards are paid, read on the lobby (`/astera stats last`, the return
 * recap), so the backing store must be the shared player-attribute store.
 */
public interface ILastMatchStore {
    public fun save(playerId: PlayerId, record: LastMatchRecord)

    public fun find(playerId: PlayerId): LastMatchRecord?

    /**
     * Whether the lobby already showed [playerId]'s recap of the stored match.
     * Marked by [markRecapShown]; a newer [save] resets it.
     */
    public fun recapShown(playerId: PlayerId): Boolean

    public fun markRecapShown(playerId: PlayerId)
}
