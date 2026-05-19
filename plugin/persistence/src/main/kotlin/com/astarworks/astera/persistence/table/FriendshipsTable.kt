package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `friendships` table — directed friendship records stored in canonical-pair
 * form (Wave _persist-1 / ADR-0068).
 *
 * The [com.astarworks.astera.core.application.port.outbound.IFriendshipRepository]
 * port treats `(A, B)` and `(B, A)` as the same edge — `find(A, B)` must return
 * the same row as `find(B, A)`. To avoid storing the pair twice (and to give
 * the table a single deterministic primary key), each row is normalised to
 * `(player_low, player_high)` where
 * `player_low.uuid.compareTo(player_high.uuid) < 0`. Queries against either
 * direction canonicalise the inputs before hitting the table, so the
 * unordered-pair semantics of the port are preserved at the repository
 * boundary.
 *
 * `status` stores [com.astarworks.astera.core.domain.social.FriendshipStatus]
 * as its enum `name` in a `VARCHAR(16)` so new statuses can be added without
 * an `ALTER TYPE`. `created_at` is a wall-clock [java.time.Instant] supplied
 * by the domain object on upsert (not a DB-side default at the Kotlin layer —
 * the V12 migration still backs it with `DEFAULT now()` for any direct SQL
 * insert).
 *
 * The composite PK `(player_low, player_high)` plus the V12 `CHECK
 * (player_low < player_high)` constraint together guarantee at the DB layer
 * that the canonical-pair invariant holds even if some future caller bypasses
 * this repository.
 */
public object FriendshipsTable : Table("friendships") {
    public val playerLow = javaUUID("player_low")
    public val playerHigh = javaUUID("player_high")
    public val status = varchar("status", 16)
    public val createdAt = timestamp("created_at")
    override val primaryKey: PrimaryKey = PrimaryKey(playerLow, playerHigh)
}
