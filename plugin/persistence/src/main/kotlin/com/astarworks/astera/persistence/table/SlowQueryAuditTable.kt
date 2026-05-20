package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Wave 5t (ADR-0086) — V23 migration table. Append-only slow query audit.
 *
 * Inserted by the Exposed `addLogger` hook in [com.astarworks.astera.persistence.observability.SlowQueryAuditLogger].
 * Read by `/astera slow-query report` admin command (Wave 5t).
 */
public object SlowQueryAuditTable : Table("slow_query_audit") {
    public val id = long("id").autoIncrement()
    public val capturedAt = timestamp("captured_at")
    public val durationMs = long("duration_ms")
    public val statementSummary = text("statement_summary")
    public val statementHash = long("statement_hash")
    public val rowsAffected = long("rows_affected").default(0L)
    public val context = text("context").default("")
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
