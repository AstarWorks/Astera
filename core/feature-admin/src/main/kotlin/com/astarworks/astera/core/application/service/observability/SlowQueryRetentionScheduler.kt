package com.astarworks.astera.core.application.service.observability

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Periodic scheduler that purges old rows from [ISlowQueryAuditRepository]
 * (ADR-0143 — Wave AG 5operator-data-integrity).
 *
 * # Why
 *
 * Round 18 audit γ confirmed `PostgresSlowQueryAuditRepository` had no
 * archival / TTL method, so the `slow_query_audit` table (V23) grew
 * unbounded over months — DBA ops would silently degrade as the table
 * bloated. The Wave 5t / ADR-0086 audit infrastructure was append-only
 * with no retention story.
 *
 * # Behavior
 *
 * On start, schedules a recurring task at `period` interval (default 7
 * days) that calls
 * [ISlowQueryAuditRepository.deleteOlderThan]`(now - retention)` and
 * logs the removed row count at INFO. The first run happens after
 * `initialDelay` (default 1 hour, so a freshly-started server doesn't
 * fire immediately on boot).
 *
 * # Defaults
 *
 *  - Retention window: 30 days. Operators using `/astera slow-query
 *    report` still get the last month of digests; older detail is
 *    rolled off.
 *  - Sweep period: 7 days (weekly). Frequent enough to keep the table
 *    bounded; infrequent enough that the DELETE is not a hot-path
 *    concern.
 *  - Initial delay: 1 hour. Gives `/astera reload` cycles + early
 *    operator inspection a chance to complete before the first purge.
 *
 * # Failure handling
 *
 * `deleteOlderThan` is best-effort; the InMemory impl iterates a
 * deque, the Postgres impl runs a single `DELETE WHERE` statement.
 * Exceptions propagate to the scheduler (which logs + continues to the
 * next firing per [IScheduler.repeat] contract).
 *
 * # Lifecycle
 *
 * Wired in `AsteraCorePlugin.onEnable` after `ISlowQueryAuditRepository`
 * is registered. The returned [IScheduler.TaskHandle] is held by the
 * plugin's subscription holder so `onDisable` can cancel.
 */
public class SlowQueryRetentionScheduler(
    private val auditRepo: ISlowQueryAuditRepository,
    private val scheduler: IScheduler,
    private val clock: () -> Instant = Instant::now,
    private val retention: Duration = DEFAULT_RETENTION,
    private val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    private val period: Duration = DEFAULT_PERIOD,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(): IScheduler.TaskHandle =
        scheduler.repeat(ExecutionScope.Io, initialDelay, period) { sweep() }

    internal fun sweep() {
        val cutoff = clock().minus(retention.toJavaDuration())
        try {
            val removed = auditRepo.deleteOlderThan(cutoff)
            if (removed > 0) {
                log.info(
                    "SlowQueryRetentionScheduler: purged {} record(s) older than {} ({} retention)",
                    removed,
                    cutoff,
                    retention,
                )
            } else {
                log.debug(
                    "SlowQueryRetentionScheduler: nothing to purge older than {}",
                    cutoff,
                )
            }
        } catch (e: RuntimeException) {
            log.warn(
                "SlowQueryRetentionScheduler: purge failed for cutoff {} : {}",
                cutoff,
                e.message,
            )
        }
    }

    public companion object {
        public val DEFAULT_RETENTION: Duration = 30.days
        public val DEFAULT_INITIAL_DELAY: Duration = 1.hours
        public val DEFAULT_PERIOD: Duration = 7.days
    }
}
