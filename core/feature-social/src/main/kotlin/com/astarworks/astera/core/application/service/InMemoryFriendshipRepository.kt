package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IFriendshipRepository] (Wave 5e).
 *
 * Friendships are stored under a canonical (smaller-UUID, larger-UUID) key so
 * the directed `find(A, B)` and `find(B, A)` calls return the same row. The
 * stored [Friendship] preserves the original `playerA` / `playerB` ordering
 * supplied by the caller — only the lookup is unordered.
 *
 * Wave 5e ships in-memory only; persistence may add a Postgres overlay later
 * (priority-override via `ServiceRegistry`).
 */
public class InMemoryFriendshipRepository : IFriendshipRepository {

    private val rows: ConcurrentHashMap<Pair<PlayerId, PlayerId>, Friendship> = ConcurrentHashMap()

    override suspend fun find(a: PlayerId, b: PlayerId): Friendship? = rows[canonicalKey(a, b)]

    override suspend fun forPlayer(playerId: PlayerId): List<Friendship> =
        rows.values.filter { it.playerA == playerId || it.playerB == playerId }

    override suspend fun upsert(friendship: Friendship) {
        rows[canonicalKey(friendship.playerA, friendship.playerB)] = friendship
    }

    override suspend fun remove(a: PlayerId, b: PlayerId) {
        rows.remove(canonicalKey(a, b))
    }

    private fun canonicalKey(a: PlayerId, b: PlayerId): Pair<PlayerId, PlayerId> {
        val cmp = a.uuid.compareTo(b.uuid)
        return if (cmp <= 0) a to b else b to a
    }
}
