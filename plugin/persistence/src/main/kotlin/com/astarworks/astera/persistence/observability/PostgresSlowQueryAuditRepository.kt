package com.astarworks.astera.persistence.observability

import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.domain.observability.SlowQueryRecord
import com.astarworks.astera.persistence.table.SlowQueryAuditTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Postgres-backed [ISlowQueryAuditRepository] (V23).
 *
 * Append-heavy / rare-read workload. `record` is best-effort: SQL failures
 * are logged but do not propagate (this storage is observability, not
 * critical path). Used by `/astera slow-query report` admin command.
 */
public class PostgresSlowQueryAuditRepository(private val db: Database) : ISlowQueryAuditRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(record: SlowQueryRecord) {
        try {
            transaction(db) {
                SlowQueryAuditTable.insert {
                    it[capturedAt] = record.capturedAt
                    it[durationMs] = record.durationMs
                    it[statementSummary] = record.statementSummary.take(SUMMARY_MAX)
                    it[statementHash] = record.statementHash
                    it[rowsAffected] = record.rowsAffected
                    it[context] = record.context
                }
            }
        } catch (ex: SQLException) {
            log.warn("slow-query audit insert failed (dropped)", ex)
        }
    }

    override fun findAfter(since: Instant, limit: Int): List<SlowQueryRecord> = transaction(db) {
        SlowQueryAuditTable
            .selectAll()
            .where { SlowQueryAuditTable.capturedAt greaterEq since }
            .orderBy(SlowQueryAuditTable.durationMs to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                SlowQueryRecord(
                    capturedAt = row[SlowQueryAuditTable.capturedAt],
                    durationMs = row[SlowQueryAuditTable.durationMs],
                    statementSummary = row[SlowQueryAuditTable.statementSummary],
                    statementHash = row[SlowQueryAuditTable.statementHash],
                    rowsAffected = row[SlowQueryAuditTable.rowsAffected],
                    context = row[SlowQueryAuditTable.context],
                )
            }
    }

    override fun findByHash(statementHash: Long, limit: Int): List<SlowQueryRecord> = transaction(db) {
        SlowQueryAuditTable
            .selectAll()
            .where { SlowQueryAuditTable.statementHash eq statementHash }
            .orderBy(SlowQueryAuditTable.capturedAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                SlowQueryRecord(
                    capturedAt = row[SlowQueryAuditTable.capturedAt],
                    durationMs = row[SlowQueryAuditTable.durationMs],
                    statementSummary = row[SlowQueryAuditTable.statementSummary],
                    statementHash = row[SlowQueryAuditTable.statementHash],
                    rowsAffected = row[SlowQueryAuditTable.rowsAffected],
                    context = row[SlowQueryAuditTable.context],
                )
            }
    }

    override fun count(): Long = transaction(db) {
        SlowQueryAuditTable.selectAll().count()
    }

    override fun deleteOlderThan(cutoff: Instant): Int = transaction(db) {
        SlowQueryAuditTable.deleteWhere { capturedAt less cutoff }
    }

    private companion object {
        const val SUMMARY_MAX = 512
    }
}
