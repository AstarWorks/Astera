package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IBossKillAuditWriter
import com.astarworks.astera.core.domain.event.BossDefeated
import com.astarworks.astera.core.domain.mob.runtime.MobId

/**
 * In-memory default impl of [IBossKillAuditWriter] (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 commit shape: keeps each unique-by-[MobId] [BossDefeated] event.
 * The persistence plugin will register a Postgres-backed impl at priority
 * 100 to overlay this in mainline. Tests read [recordedEvents] directly
 * to assert audit correctness without standing up Postgres.
 *
 * Idempotency: re-recording the same [MobId] is a no-op (returns false).
 */
public class InMemoryBossKillAuditWriter : IBossKillAuditWriter {

    private val rows: LinkedHashMap<MobId, BossDefeated> = LinkedHashMap()

    override fun record(event: BossDefeated): Boolean {
        if (rows.containsKey(event.mobId)) return false
        rows[event.mobId] = event
        return true
    }

    override fun count(): Long = rows.size.toLong()

    /** Test/inspection accessor — returns recorded events in insertion order. */
    public fun recordedEvents(): List<BossDefeated> = rows.values.toList()

    /** Test accessor — clear all records. */
    public fun clear() {
        rows.clear()
    }
}
