package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.power.PowerIndex

/**
 * Outbound port: persisted [PowerIndex] snapshots
 * (Wave 5flex / ADR-0102 surface #4).
 *
 * In-memory default registered by `AsteraEconomyPlugin` (priority 0);
 * Postgres overlay (`PostgresPowerIndexRepository`, V34) registered by
 * `AsteraPersistencePlugin` at priority 100, following the universal
 * Wave 3 (ADR-0020) pattern.
 *
 * # Why persistence
 *
 * The snapshot is *recomputed* periodically by
 * [com.astarworks.astera.economy.application.usecase.power.RecalculatePowerIndexUseCase],
 * but every read site — nameplate badge, profile card REST, leaderboard
 * sort key — needs constant-time access. Persisting the latest snapshot
 * gives Wave 7b leaderboards a single-table sort and Wave 5flex nameplate
 * a sub-tick lookup.
 *
 * # Update semantics
 *
 * [save] upserts on [PlayerId]; only the most recent snapshot is
 * retained. Historical trends are out of scope for Wave 5flex (Wave
 * _ops-4 Prometheus + cohort analytics owns the historical view).
 *
 * # Leaderboard query
 *
 * [topN] returns the top [n] snapshots in descending [PowerIndex.total]
 * order. Used by Wave 7b leaderboard adapters; bound [n] sensibly
 * (10 / 50 / 100) — there is no pagination contract in Wave 5flex.
 */
public interface IPowerIndexRepository {

    /** Lookup the latest snapshot. `null` if never computed for [playerId]. */
    public fun find(playerId: PlayerId): PowerIndex?

    /** Upsert the snapshot keyed by [PowerIndex.playerId]. */
    public fun save(snapshot: PowerIndex)

    /** Hard-delete; tests + admin only. */
    public fun delete(playerId: PlayerId)

    /**
     * Top [n] snapshots by [PowerIndex.total] descending. Stable tie-break is
     * implementation-defined (Postgres impl uses `computed_at DESC` on ties,
     * in-memory uses insertion order).
     */
    public fun topN(n: Int): List<PowerIndex>
}
