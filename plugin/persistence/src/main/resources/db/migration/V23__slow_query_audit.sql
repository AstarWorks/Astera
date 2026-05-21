-- Wave 5t (ADR-0086) — V23 slow query audit table.
--
-- Append-only log of Exposed queries that exceeded the configured threshold
-- (default 100ms). The Exposed `addLogger` hook installed by AsteraPersistencePlugin
-- writes here when `durationMs > threshold`. /astera slow-query report
-- reads from this table to build the weekly digest.
--
-- Storage profile: append-heavy, rare reads (only the report command +
-- the future Web admin dashboard). Index on (captured_at, duration_ms desc)
-- so the digest's "top N slowest in window" scan is index-friendly.

CREATE TABLE slow_query_audit (
    id                 BIGSERIAL PRIMARY KEY,
    captured_at        TIMESTAMP(3) NOT NULL,
    duration_ms        BIGINT       NOT NULL,
    statement_summary  TEXT         NOT NULL,   -- truncated to first 512 chars
    statement_hash     BIGINT       NOT NULL,   -- 64-bit hash for fingerprint grouping
    rows_affected      BIGINT       NOT NULL DEFAULT 0,
    context            TEXT         NOT NULL DEFAULT ''
);

CREATE INDEX idx_slow_query_audit_captured
    ON slow_query_audit (captured_at DESC);

CREATE INDEX idx_slow_query_audit_duration
    ON slow_query_audit (duration_ms DESC);

CREATE INDEX idx_slow_query_audit_hash
    ON slow_query_audit (statement_hash);
