-- Wave 5e: append-only audit trail.
-- One row per AuditEvent variant. `kind` distinguishes the sealed
-- variant (e.g. 'permission_assigned', 'currency_transferred'); `data`
-- carries the variant-specific fields as JSONB. `occurred_at` is the
-- wall-clock of the originating domain event; `recorded_at` is when
-- the audit row was committed (may differ under retry).
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY,
    kind          TEXT NOT NULL,
    data          JSONB NOT NULL,
    occurred_at   TIMESTAMP NOT NULL,
    recorded_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_recorded_at_idx ON audit_log (recorded_at DESC);
CREATE INDEX audit_log_kind_idx        ON audit_log (kind);
