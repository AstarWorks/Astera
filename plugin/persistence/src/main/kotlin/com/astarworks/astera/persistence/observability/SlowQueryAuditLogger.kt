package com.astarworks.astera.persistence.observability

import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.domain.observability.SlowQueryRecord
import com.astarworks.astera.core.domain.observability.SlowQueryThresholdMs
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Exposed [SqlLogger] that captures statements whose
 * surrounding transaction duration exceeds [SlowQueryThresholdMs] and writes
 * them to the [ISlowQueryAuditRepository] (V23 audit table).
 *
 * Phase 1 PoC granularity: Exposed's [SqlLogger.log] hook only fires after
 * execution and exposes [Transaction.duration] (cumulative ms since txn
 * start). That's a coarser signal than per-statement timing, but it's the
 * stable Phase 1 API and matches what `warnLongQueriesDuration` uses
 * internally. Wave 5u may upgrade to per-statement nanos via the richer
 * `StatementInterceptor` once that API stabilises in Exposed 1.5+.
 *
 * Threshold + opt-out are controlled by `ASTERA_SLOW_QUERY_THRESHOLD_MS`
 * env var (`0` = disabled).
 *
 * Wiring: attach to every transaction via `Transaction.addLogger(this)`
 * inside the global `connectAndMigrate` setup, or per-call via
 * `transaction { addLogger(SlowQueryAuditLogger(...)) }`.
 */
public class SlowQueryAuditLogger(
    private val repository: ISlowQueryAuditRepository,
    private val threshold: SlowQueryThresholdMs,
    private val clock: () -> Instant = Instant::now,
) : SqlLogger {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun log(context: StatementContext, transaction: Transaction) {
        if (threshold.value == 0L) return
        val durationMs = runCatching { transaction.duration }.getOrDefault(0L)
        if (durationMs < threshold.value) return
        try {
            val sql = runCatching { context.sql(transaction) }.getOrElse {
                context.statement.toString()
            }
            repository.record(
                SlowQueryRecord(
                    capturedAt = clock(),
                    durationMs = durationMs,
                    statementSummary = sql.take(SUMMARY_MAX),
                    statementHash = sql.hashCode().toLong(),
                    rowsAffected = 0L,
                    context = context.statement.type.name,
                ),
            )
        } catch (t: Throwable) {
            this.log.debug("slow-query logger dispatch failed (dropped)", t)
        }
    }

    public companion object {
        public const val SUMMARY_MAX: Int = 512

        /** Read [SlowQueryThresholdMs] from env, defaulting to 100ms. */
        public fun thresholdFromEnv(): SlowQueryThresholdMs {
            val raw = System.getenv("ASTERA_SLOW_QUERY_THRESHOLD_MS")
            val parsed = raw?.toLongOrNull()
            return SlowQueryThresholdMs(parsed?.coerceAtLeast(0L) ?: 100L)
        }
    }
}
