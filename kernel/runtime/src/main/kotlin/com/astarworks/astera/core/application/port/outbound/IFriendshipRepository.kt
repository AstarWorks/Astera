package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship

/**
 * Outbound port: friendship records (Wave 5e). Pair order is unordered —
 * `find(A, B)` returns the same row as `find(B, A)`.
 */
public interface IFriendshipRepository {
    public fun find(a: PlayerId, b: PlayerId): Friendship?
    public fun forPlayer(playerId: PlayerId): List<Friendship>
    public fun upsert(friendship: Friendship)
    public fun remove(a: PlayerId, b: PlayerId)
}
