package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship

/**
 * Outbound port: friendship records (Wave 5e). Pair order is unordered —
 * `find(A, B)` returns the same row as `find(B, A)`.
 */
public interface IFriendshipRepository {
    public suspend fun find(a: PlayerId, b: PlayerId): Friendship?
    public suspend fun forPlayer(playerId: PlayerId): List<Friendship>
    public suspend fun upsert(friendship: Friendship)
    public suspend fun remove(a: PlayerId, b: PlayerId)
}
