package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.jsonb.EngagementSnapshotJson
import com.astarworks.astera.persistence.table.PlayerEngagementSnapshotsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IEngagementSnapshotRepository] (Wave 5engage / ADR-0104).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default (`InMemoryEngagementSnapshotRepository`) the core plugin
 * registers at priority=0. JSONB shape lives in [EngagementSnapshotJson].
 *
 * `upsert` writes the whole document on each recompute — the dashboard
 * service is the only writer (no concurrent merge), so an unconditional
 * replace is correct. Per ADR-0014, this matches the bounded-aggregate
 * pattern shared with `player_sessions` / `player_settings`.
 */
public class PostgresEngagementSnapshotRepository(private val db: Database) : IEngagementSnapshotRepository {

    override fun find(playerId: PlayerId): EngagementSnapshot? = transaction(db) {
        PlayerEngagementSnapshotsTable
            .selectAll()
            .where { PlayerEngagementSnapshotsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                row[PlayerEngagementSnapshotsTable.snapshot].toDomain(playerId)
            }
    }

    override fun upsert(snapshot: EngagementSnapshot) {
        transaction(db) {
            PlayerEngagementSnapshotsTable.upsert(PlayerEngagementSnapshotsTable.playerId) {
                it[playerId] = snapshot.playerId.uuid
                it[PlayerEngagementSnapshotsTable.snapshot] = EngagementSnapshotJson.fromDomain(snapshot)
                it[computedAt] = snapshot.computedAt
            }
        }
    }

    override fun all(): List<EngagementSnapshot> = transaction(db) {
        PlayerEngagementSnapshotsTable
            .selectAll()
            .map { row ->
                row[PlayerEngagementSnapshotsTable.snapshot]
                    .toDomain(PlayerId(row[PlayerEngagementSnapshotsTable.playerId]))
            }
    }

    /**
     * Push the topN + sort into SQL. Note the score is *inside* the JSONB
     * blob; Postgres can index JSONB paths but adding the index is a
     * separate migration. For Phase 1 the in-memory sort over `all()` is
     * fine (player count is bounded; the dashboard recompute runs on a
     * schedule, not per-request). We still expose a Postgres-side variant
     * that defers to the default-method implementation.
     */
    override fun topByScore(limit: Int): List<EngagementSnapshot> {
        require(limit >= 0) { "limit must be >= 0, was $limit" }
        return super.topByScore(limit)
    }

    /**
     * Push the `lastSeenAt > cutoff` filter into SQL via the materialized
     * `computed_at` column (close enough as a coarse staleness filter — the
     * dashboard service's `compute()` reads `all()` anyway and re-filters
     * precisely in-memory).
     *
     * Phase 1: defer to default impl until we have a documented production
     * load that justifies a path-extract index.
     */
    override fun seenSince(cutoff: Instant): List<EngagementSnapshot> = super.seenSince(cutoff)
}
