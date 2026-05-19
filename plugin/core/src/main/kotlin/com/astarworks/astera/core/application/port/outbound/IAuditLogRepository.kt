package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.audit.AuditEvent
import java.time.Instant

/**
 * Outbound port: append-only audit log (Wave 5e).
 *
 * Wave 5e backs by V9 `audit_log` (Postgres). InMemory default in core
 * for tests. Writes are fire-and-forget — callers do not block on commit.
 * Reads are admin-tool / web-dashboard territory (Phase 4 Web Admin UI).
 */
public interface IAuditLogRepository {

    /** Append [event] to the log. */
    public fun write(event: AuditEvent)

    /**
     * Read the most recent [limit] events, newest first. Used by admin
     * tooling. `since` filters to events recorded on/after that instant.
     */
    public fun recent(limit: Int, since: Instant? = null): List<AuditEvent>
}
