-- Wave 3 (R2) / ADR-0020: per-(player, currency) wallet ledger.
-- Postgres-backed overlay of InMemoryCurrencyLedger. balance >= 0 is the
-- domain invariant; the CHECK constraint mirrors it at the DB layer so any
-- direct SQL write (admin, migration) also respects it.
CREATE TABLE wallets (
    player_id   UUID NOT NULL,
    currency    TEXT NOT NULL,
    balance     BIGINT NOT NULL CHECK (balance >= 0),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, currency)
);
