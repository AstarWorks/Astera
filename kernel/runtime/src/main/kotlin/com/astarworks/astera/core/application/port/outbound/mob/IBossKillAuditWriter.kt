package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.event.BossDefeated

/**
 * Outbound port: persists a boss-kill audit row (V30 `boss_kills` table)
 * (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 in-core impl
 * ([com.astarworks.astera.core.application.service.mob.InMemoryBossKillAuditWriter])
 * records rows in memory for tests + plugin bootstrap. The persistence
 * plugin overlays a Postgres-backed impl at higher priority (priority 100,
 * same convention as `PostgresWalletRepository` / `PostgresLoadoutRepository`).
 *
 * Per ADR-0068 (cross-server persistence boundary), boss kills are
 * server-instance bound (the kill happened on **this** server) but the audit
 * row crosses to the central Postgres so leaderboards + weekly-clear gates
 * see every server's kills.
 *
 * # Why a dedicated port (not "persistence subscribes to BossDefeated")
 *
 * Two reasons:
 *  1. The lifecycle service needs synchronous acknowledgement that the row
 *     was written before it grants loot — async pub/sub leaves a window
 *     where players could exploit a write failure to skip the audit while
 *     keeping the loot.
 *  2. Tests can inject an `InMemoryBossKillAuditWriter` and assert audit
 *     correctness without standing up Postgres.
 */
public interface IBossKillAuditWriter {

    /**
     * Persist an audit row for the boss kill. Implementations MUST be
     * idempotent on [BossDefeated.mobId] — the lifecycle service may retry
     * on transient failure; double-writes for the same mob instance must
     * not produce duplicate rows.
     *
     * Returns true if a new row was written, false if the row already
     * existed (idempotent no-op). Implementations that cannot distinguish
     * (e.g. Postgres `INSERT ON CONFLICT DO NOTHING`) may return true
     * conservatively.
     */
    public fun record(event: BossDefeated): Boolean

    /**
     * Number of recorded boss-kill rows. Used by `/astera-boss status`
     * introspection + tests.
     */
    public fun count(): Long
}
