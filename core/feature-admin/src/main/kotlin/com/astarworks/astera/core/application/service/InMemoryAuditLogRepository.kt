package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import java.time.Instant

/**
 * In-memory default [IAuditLogRepository] for tests and Postgres-less runs
 * (Wave 5e). Persistence's Postgres impl overlays this at priority=100.
 *
 * Events are stored in insertion (= recording) order; [recent] returns them
 * newest-first to match the V9 Postgres `ORDER BY recorded_at DESC LIMIT`
 * contract. Not thread-safe — Astera dispatches on the Paper main thread.
 */
public class InMemoryAuditLogRepository : IAuditLogRepository {

    private val events: MutableList<Record> = mutableListOf()

    private data class Record(val recordedAt: Instant, val event: AuditEvent)

    override fun write(event: AuditEvent) {
        events += Record(recordedAt = Instant.now(), event = event)
    }

    override fun recent(limit: Int, since: Instant?): List<AuditEvent> {
        require(limit >= 0) { "limit must be >= 0: $limit" }
        if (limit == 0) return emptyList()
        return events
            .asReversed()
            .asSequence()
            .filter { since == null || !it.recordedAt.isBefore(since) }
            .take(limit)
            .map { it.event }
            .toList()
    }
}
