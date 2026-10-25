package com.astarworks.astera.core.domain.observability

import java.time.Instant

/**
 * Wave 5t (ADR-0086) — captured slow-query event.
 *
 * Recorded by the Exposed `addLogger` hook (Phase 1 Postgres path) when a
 * query takes longer than [com.astarworks.astera.core.domain.observability.SlowQueryThresholdMs].
 * The audit table V23 (`slow_query_audit`) persists these for the weekly
 * digest. Hash of the query text is stored alongside the verbatim text so
 * SELECT-by-fingerprint stays index-friendly.
 */
public data class SlowQueryRecord(
    val capturedAt: Instant,
    val durationMs: Long,
    val statementSummary: String,
    val statementHash: Long,
    val rowsAffected: Long = 0,
    val context: String = "",
)

/** Wall-clock duration threshold (ms). Default 100; overridable via config. */
@JvmInline
public value class SlowQueryThresholdMs(public val value: Long) {
    init {
        require(value >= 0L) { "SlowQueryThresholdMs must be non-negative (got $value)" }
    }
}
