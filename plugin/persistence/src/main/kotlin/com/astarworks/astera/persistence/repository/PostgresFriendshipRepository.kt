package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import com.astarworks.astera.persistence.table.FriendshipsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/**
 * Postgres-backed [IFriendshipRepository] (Wave _persist-1 / ADR-0068).
 *
 * Registered by `AsteraPersistencePlugin` at `priority=100`; overlays the
 * in-memory default that core / a feature plugin registers.
 *
 * Storage shape: the table holds one row per unordered pair, stored as
 * `(player_low, player_high)` with `player_low < player_high` (canonical-pair
 * pattern, see [FriendshipsTable]). Every public method canonicalises its
 * inputs through [canonicalPair] before touching the table so the port's
 * unordered-pair contract (`find(A, B) == find(B, A)`) holds for any caller.
 *
 * On read, the [Friendship] domain object is reconstructed with
 * `playerA = playerLow` and `playerB = playerHigh`. The port doc explicitly
 * states the pair is unordered, so callers cannot rely on the original
 * `(A, B)` insertion order being preserved — they only get a deterministic
 * canonical order back. If a feature ever needs to remember "who initiated",
 * that belongs on the [Friendship] domain object (e.g. an `initiator:
 * PlayerId` field) rather than being smuggled in via the pair order.
 *
 * Columnar (not JSONB): the row is fixed-shape (two UUIDs, status, timestamp),
 * so JSONB would add overhead without any forward-compat win — new statuses
 * are added by extending the enum + writing the new name into `status`.
 */
public class PostgresFriendshipRepository(private val db: Database) : IFriendshipRepository {

    override suspend fun find(a: PlayerId, b: PlayerId): Friendship? = ioTransaction(db) {
        val (low, high) = canonicalPair(a, b)
        FriendshipsTable
            .selectAll()
            .where {
                (FriendshipsTable.playerLow eq low) and
                    (FriendshipsTable.playerHigh eq high)
            }
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun forPlayer(playerId: PlayerId): List<Friendship> = ioTransaction(db) {
        FriendshipsTable
            .selectAll()
            .where {
                (FriendshipsTable.playerLow eq playerId.uuid) or
                    (FriendshipsTable.playerHigh eq playerId.uuid)
            }
            .map { it.toDomain() }
    }

    override suspend fun upsert(friendship: Friendship) {
        val (low, high) = canonicalPair(friendship.playerA, friendship.playerB)
        ioTransaction(db) {
            FriendshipsTable.upsert(FriendshipsTable.playerLow, FriendshipsTable.playerHigh) {
                it[playerLow] = low
                it[playerHigh] = high
                it[status] = friendship.status.name
                it[createdAt] = friendship.createdAt
            }
        }
    }

    override suspend fun remove(a: PlayerId, b: PlayerId) {
        val (low, high) = canonicalPair(a, b)
        ioTransaction(db) {
            FriendshipsTable.deleteWhere {
                (FriendshipsTable.playerLow eq low) and
                    (FriendshipsTable.playerHigh eq high)
            }
        }
    }

    /**
     * Returns `(min, max)` for the pair by lexicographic UUID-string compare,
     * which matches PostgreSQL's byte-wise UUID `<` operator. Used to project
     * any caller's `(A, B)` / `(B, A)` view of the edge onto the table's
     * canonical storage form.
     *
     * Note on ordering: [UUID.compareTo] would NOT work here — it compares
     * `(mostSigBits, leastSigBits)` as signed longs, which inverts whenever
     * the most-significant bit differs between the two UUIDs. PostgreSQL's
     * UUID type uses unsigned byte-wise comparison, so we mirror that via
     * the hex `toString()` representation (which is byte-uniform).
     *
     * `a == b` is not guarded against here — the [Friendship] domain object's
     * own invariants (and the V12 `CHECK (player_low < player_high)`
     * constraint) are responsible for rejecting self-friendship.
     */
    private fun canonicalPair(a: PlayerId, b: PlayerId): Pair<UUID, UUID> =
        if (a.uuid.toString() < b.uuid.toString()) a.uuid to b.uuid else b.uuid to a.uuid

    private fun ResultRow.toDomain(): Friendship = Friendship(
        playerA = PlayerId(this[FriendshipsTable.playerLow]),
        playerB = PlayerId(this[FriendshipsTable.playerHigh]),
        status = FriendshipStatus.valueOf(this[FriendshipsTable.status]),
        createdAt = this[FriendshipsTable.createdAt],
    )
}
