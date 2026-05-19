package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.RewardBundleJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `reward_outbox` table — durable outbox for cross-server reward delivery
 * (Wave _persist-1 / ADR-0068, V15 migration).
 *
 * Wave 5e introduced [com.astarworks.astera.core.application.port.outbound.IRewardBridge]
 * with an in-memory single-JVM impl. Wave _persist-1 makes that durable so a
 * server restart (or, in Phase 4, a Velocity-orchestrated hop between game and
 * lobby pods) does not drop pending bundles. The persistent shape is the
 * classic **outbox pattern**:
 *
 * - `enqueue(bundle)` → INSERT one row with `delivered_at = NULL`.
 * - `consumeForPlayer(playerId)` → inside a single transaction, SELECT every
 *   undelivered row for the player ordered by [createdAt] ASC, then UPDATE
 *   each row's [deliveredAt] to `now()`. A retry that crashes mid-flight
 *   leaves the rows untouched (still `delivered_at IS NULL`), so the next
 *   call re-drains them — at-least-once semantics, idempotent on retry.
 * - `peek(playerId)` → SELECT-only variant for admin / debug.
 *
 * Phase 4 wiring (Velocity proxy + Redis pub/sub) treats this table as
 * source-of-truth durability and Redis as the cross-pod notify layer; both
 * read from `reward_outbox`, so this schema is forward-compatible with the
 * federation rollout. ADR-0014's columnar-vs-JSONB call lands on JSONB for
 * the bundle contents because each row is read whole — only the indexed
 * fields (`player_id`, `delivered_at`, `created_at`) stay columnar.
 */
public object RewardOutboxTable : Table("reward_outbox") {
    public val bundleId = javaUUID("bundle_id")
    public val playerId = javaUUID("player_id")
    public val payload = jsonb("payload", RewardBundleJson.serializer())
    public val createdAt = timestamp("created_at")
    public val deliveredAt = timestamp("delivered_at").nullable()
    override val primaryKey: PrimaryKey = PrimaryKey(bundleId)
}
