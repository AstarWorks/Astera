package com.astarworks.astera.core.application.port.outbound.observability

import com.astarworks.astera.core.domain.observability.SlowQueryRecord
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — append-only audit repository for slow queries.
 *
 * Storage trail for `Exposed.addLogger` hooks that detect queries crossing
 * [com.astarworks.astera.core.domain.observability.SlowQueryThresholdMs].
 *
 * In-memory default (`InMemorySlowQueryAuditRepository`) lives in
 * `application/service/observability/`; the Postgres overlay
 * (`PostgresSlowQueryAuditRepository`) lives in `plugin/persistence/`,
 * registered at priority 100, V23 migration.
 *
 * `/astera slow-query report` (Wave 5t admin command) queries [findAfter] to
 * build the weekly digest.
 */
public interface ISlowQueryAuditRepository {

    /** Append a single record. */
    public fun record(record: SlowQueryRecord)

    /**
     * Records with [SlowQueryRecord.capturedAt] >= [since], sorted by
     * descending duration (slowest first). Caller paginates with [limit].
     */
    public fun findAfter(since: Instant, limit: Int = 50): List<SlowQueryRecord>

    /** Records by fingerprint, useful for "top N most-frequent" lookups. */
    public fun findByHash(statementHash: Long, limit: Int = 50): List<SlowQueryRecord>

    /** Snapshot total recorded count (for the digest header). */
    public fun count(): Long
}
