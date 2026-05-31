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

    /**
     * Delete every record with [SlowQueryRecord.capturedAt] strictly before
     * [cutoff]. Returns the number of rows removed (0 if the table was
     * already clean past [cutoff]).
     *
     * Wave AG 5operator-data-integrity (ADR-0143): backs the
     * `SlowQueryRetentionListener` scheduled task that runs weekly to keep
     * the audit table bounded. Operators using `/astera slow-query report`
     * still get the last N days of digests; older detail is rolled off.
     * The default retention window is 30 days (configurable via the
     * scheduler that calls this).
     */
    public fun deleteOlderThan(cutoff: Instant): Int
}
