package com.astarworks.astera.core.application.service.observability

import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.domain.observability.SlowQueryRecord
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 in-memory default for [ISlowQueryAuditRepository]. Lock-free
 * append-only deque + an atomic counter. Bounded by [maxEntries] to avoid
 * unbounded growth in development; production deployments use the Postgres
 * overlay (V23, priority 100) for retention.
 *
 * Old entries are pruned head-first when capacity is exceeded — Phase 1 PoC
 * acceptable; Phase 2 may add LRU or time-windowed eviction.
 */
public class InMemorySlowQueryAuditRepository(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ISlowQueryAuditRepository {

    private val entries: ConcurrentLinkedDeque<SlowQueryRecord> = ConcurrentLinkedDeque()
    private val counter: AtomicLong = AtomicLong(0L)

    override suspend fun record(record: SlowQueryRecord) {
        entries.addLast(record)
        counter.incrementAndGet()
        // Cheap bound — synchronizing once per add keeps the deque small.
        while (entries.size > maxEntries) {
            entries.pollFirst() ?: break
        }
    }

    override suspend fun findAfter(since: Instant, limit: Int): List<SlowQueryRecord> =
        entries
            .filter { it.capturedAt >= since }
            .sortedByDescending { it.durationMs }
            .take(limit)

    override suspend fun findByHash(statementHash: Long, limit: Int): List<SlowQueryRecord> =
        entries
            .filter { it.statementHash == statementHash }
            .sortedByDescending { it.capturedAt }
            .take(limit)

    override suspend fun count(): Long = counter.get()

    override suspend fun deleteOlderThan(cutoff: Instant): Int {
        var removed = 0
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            if (record.capturedAt.isBefore(cutoff)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 10_000
    }
}
