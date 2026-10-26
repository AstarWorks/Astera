package com.astarworks.astera.core.application.port.outbound.engagement

import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Outbound port: per-player engagement snapshot persistence (Wave 5engage,
 * ADR-0104).
 *
 * Backed by V30 `player_engagement_snapshots` (player_id PK, snapshot JSONB,
 * computed_at TIMESTAMP). Latest-write-wins: `upsert` replaces the whole row
 * on each recompute pass; no history (Prometheus owns the time series).
 *
 * # silly-knuth §5: no Result here
 *
 * The same reasoning as `IPlayerSessionRepository`: the dashboard service
 * recomputes from a stable input set (session repo + analytics counters),
 * not from user input. A failed write surfaces via the JDBC driver's
 * exception — there is no domain-shaped error the caller can recover from.
 *
 * Phase 1 default impl (`InMemoryEngagementSnapshotRepository`) is registered
 * at priority 0; Postgres `PostgresEngagementSnapshotRepository` overlays at
 * priority 100 when the persistence plugin loads.
 */
public interface IEngagementSnapshotRepository {

    /** Returns the latest snapshot for the player, or null if never computed. */
    public fun find(playerId: PlayerId): EngagementSnapshot?

    /** Replaces (or inserts) the player's snapshot. `computed_at` is taken from the snapshot. */
    public fun upsert(snapshot: EngagementSnapshot)

    /** Returns all snapshots — used by the dashboard service to compute DAU/WAU/MAU and retention. */
    public fun all(): List<EngagementSnapshot>

    /**
     * Returns the top-N snapshots sorted descending by [EngagementSnapshot.score].
     * Default implementation reads [all] and sorts in memory; Postgres impl
     * pushes the sort + LIMIT into SQL so cardinality stays bounded.
     */
    public fun topByScore(limit: Int): List<EngagementSnapshot> {
        require(limit >= 0) { "limit must be >= 0, was $limit" }
        return all()
            .sortedWith(compareByDescending<EngagementSnapshot> { it.score.value }.thenBy { it.playerId.uuid })
            .take(limit)
    }

    /**
     * Returns snapshots whose `lastSeenAt` is after [cutoff]. Used by the
     * dashboard service to count active users for DAU/WAU/MAU.
     */
    public fun seenSince(cutoff: Instant): List<EngagementSnapshot> =
        all().filter { it.lastSeenAt.isAfter(cutoff) }
}
