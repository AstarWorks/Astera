-- Wave 5f (ADR-0035, RTM #19) — JP regulatory currency compliance.
-- Per-issuance row for 6-month expiry tracking + circulation cap accounting.
CREATE TABLE currency_issuance_log (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL,
    currency VARCHAR(16) NOT NULL,
    source_class VARCHAR(16) NOT NULL,
    issued_amount BIGINT NOT NULL CHECK (issued_amount > 0),
    outstanding_amount BIGINT NOT NULL CHECK (outstanding_amount >= 0),
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT outstanding_le_issued CHECK (outstanding_amount <= issued_amount)
);

CREATE INDEX idx_issuance_player_currency ON currency_issuance_log (player_id, currency);
CREATE INDEX idx_issuance_currency_issued_at ON currency_issuance_log (currency, issued_at);
