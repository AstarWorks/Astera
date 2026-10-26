package com.astarworks.astera.persistence.adapter.io

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.persistence.repository.PostgresAuditLogRepository
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

public class CachedAuditLog(private val storage: PostgresAuditLogRepository, private val writes: WriteBehind) : IAuditLogRepository {
    private val events = java.util.concurrent.ConcurrentLinkedDeque<Pair<Instant, AuditEvent>>()
    override fun write(event: AuditEvent) {
        events.addLast(Instant.now() to event)
        while (events.size > RECENT_CAP) events.pollFirst()
        writes.enqueue("audit" to event.id) { storage.write(event) }
    }
    override fun recent(limit: Int, since: Instant?): List<AuditEvent> {
        require(limit >= 0)
        return events.descendingIterator().asSequence().filter { since == null || !it.first.isBefore(since) }.take(limit).map { it.second }.toList()
    }

    private companion object {
        const val RECENT_CAP = 5_000
    }
}
