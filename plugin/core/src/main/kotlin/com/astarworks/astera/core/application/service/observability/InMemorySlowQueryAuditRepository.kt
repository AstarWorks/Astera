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

    override fun record(record: SlowQueryRecord) {
        entries.addLast(record)
        counter.incrementAndGet()
        // Cheap bound — synchronizing once per add keeps the deque small.
        while (entries.size > maxEntries) {
            entries.pollFirst() ?: break
        }
    }

    override fun findAfter(since: Instant, limit: Int): List<SlowQueryRecord> =
        entries
            .filter { it.capturedAt >= since }
            .sortedByDescending { it.durationMs }
            .take(limit)

    override fun findByHash(statementHash: Long, limit: Int): List<SlowQueryRecord> =
        entries
            .filter { it.statementHash == statementHash }
            .sortedByDescending { it.capturedAt }
            .take(limit)

    override fun count(): Long = counter.get()

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 10_000
    }
}
