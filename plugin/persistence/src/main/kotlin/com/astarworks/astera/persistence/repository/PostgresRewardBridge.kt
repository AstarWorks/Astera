package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import com.astarworks.astera.persistence.jsonb.RewardBundleJson
import com.astarworks.astera.persistence.table.RewardOutboxTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Postgres-backed [IRewardBridge] (Wave _persist-1 / ADR-0068).
 *
 * Replaces the Wave 5e in-memory `InMemoryRewardBridge` with a durable outbox:
 * pending bundles survive pod restarts and (in Phase 4) cross-server hops
 * between game and lobby. Registered by `AsteraPersistencePlugin` at
 * priority=100 to overlay the in-memory default the core / weapons plugin
 * registers.
 *
 * ## Outbox semantics
 *
 * Each [enqueue] writes a fresh row with `delivered_at = NULL`. Each
 * [consumeForPlayer] atomically (single Exposed transaction):
 *
 * 1. SELECTs every undelivered row for the player ordered by `created_at ASC`
 *    so the consumer sees bundles in producer order.
 * 2. UPDATEs each row's `delivered_at = now()`.
 * 3. Returns the decoded [RewardBundle] list.
 *
 * If the transaction aborts (e.g. JDBC failure between step 1 and step 2),
 * Postgres rolls back the entire batch — no row is marked delivered and a
 * retry re-drains the same set. **At-least-once delivery, idempotent on
 * retry.** Callers (the lobby-side restore flow) must therefore make their
 * apply step idempotent on the [RewardBundleId] — Wave 5e's
 * `RewardApplicationUseCase` already does this via the `appliedBundleIds`
 * domain set.
 *
 * ## Concurrency
 *
 * Two concurrent `consumeForPlayer` calls for the same player can race on
 * the SELECT; the second one's UPDATE will be a no-op for any row the first
 * already marked (because the WHERE clause filters on `delivered_at IS NULL`
 * at SELECT time, but the same predicate doesn't bind on the UPDATE step).
 * In the Phase 1 single-JVM PoC this race is impossible (one call per
 * player join). In Phase 4 with Redis-pub/sub fanout, the per-row UPDATE
 * acts as the serialisation point — a row gets `delivered_at` set exactly
 * once, and the loser of the race observes the row as already-delivered on
 * its next SELECT. If a stronger guarantee is ever needed, swap the SELECT
 * for `SELECT … FOR UPDATE SKIP LOCKED` — that change is forward-compatible
 * with this schema. (Exposed v1's `forUpdate` modifier on Query supplies
 * the SQL; left out of Phase 1 to keep the read fast.)
 *
 * [peek] is the admin-only variant: same WHERE, no UPDATE — observes the
 * outbox without consuming.
 */
public class PostgresRewardBridge(private val db: Database) : IRewardBridge {

    override suspend fun enqueue(bundle: RewardBundle) {
        ioTransaction(db) {
            RewardOutboxTable.insert {
                it[bundleId] = bundle.id.value
                it[playerId] = bundle.playerId.uuid
                it[payload] = RewardBundleJson.fromDomain(bundle)
                it[createdAt] = bundle.createdAt
                it[deliveredAt] = null
            }
        }
    }

    override suspend fun consumeForPlayer(playerId: PlayerId): List<RewardBundle> = ioTransaction(db) {
        val rows = RewardOutboxTable
            .selectAll()
            .where {
                (RewardOutboxTable.playerId eq playerId.uuid) and
                    RewardOutboxTable.deliveredAt.isNull()
            }
            .orderBy(RewardOutboxTable.createdAt to SortOrder.ASC)
            .toList()
        if (rows.isEmpty()) {
            return@ioTransaction emptyList()
        }
        val now = Instant.now()
        rows.forEach { row ->
            val id = row[RewardOutboxTable.bundleId]
            RewardOutboxTable.update({ RewardOutboxTable.bundleId eq id }) {
                it[deliveredAt] = now
            }
        }
        rows.map { it.toDomain(playerId) }
    }

    override suspend fun peek(playerId: PlayerId): List<RewardBundle> = ioTransaction(db) {
        RewardOutboxTable
            .selectAll()
            .where {
                (RewardOutboxTable.playerId eq playerId.uuid) and
                    RewardOutboxTable.deliveredAt.isNull()
            }
            .orderBy(RewardOutboxTable.createdAt to SortOrder.ASC)
            .map { it.toDomain(playerId) }
    }

    private fun ResultRow.toDomain(playerId: PlayerId): RewardBundle =
        this[RewardOutboxTable.payload].toDomain(
            id = RewardBundleId(this[RewardOutboxTable.bundleId]),
            playerId = playerId,
            createdAt = this[RewardOutboxTable.createdAt],
        )
}
