-- Wave 5flex / ADR-0102 surface #4: PowerIndex (Networth aggregate) snapshot
-- table.
--
-- One row per player, upserted on every recalculation. The Wave 5flex
-- recalculation use case
-- (RecalculatePowerIndexUseCase) writes the latest snapshot; the read sites
-- (Wave 7b leaderboard sort, Wave 5flex nameplate, Wave 8b profile card)
-- read constant-time.
--
-- Why a flat row instead of a JSONB blob: every read site cares about either
-- the `total` (leaderboard sort) or one specific component (nameplate). A
-- JSONB column would force casts on every read; flat columns let Postgres
-- handle the comparisons natively. Component sum is materialised so the
-- leaderboard query is `ORDER BY total DESC`, no recomputation in SQL.
--
-- The CHECK constraints mirror the domain invariants (every component is
-- non-negative). The Postgres impl never inserts a negative value, but the
-- CHECK is defense-in-depth against a future bug or manual UPDATE.
--
-- Indexes:
--   - `idx_power_index_total` — covers the Wave 7b leaderboard sort key
--     (total descending, computed_at descending for stable tie-break).
--   - PK on `player_id` covers the per-player nameplate read.
CREATE TABLE power_index_snapshots (
    player_id                  UUID PRIMARY KEY,
    equipment_value            BIGINT NOT NULL DEFAULT 0 CHECK (equipment_value >= 0),
    vault_value                BIGINT NOT NULL DEFAULT 0 CHECK (vault_value >= 0),
    wallet_value               BIGINT NOT NULL DEFAULT 0 CHECK (wallet_value >= 0),
    marketplace_listing_value  BIGINT NOT NULL DEFAULT 0 CHECK (marketplace_listing_value >= 0),
    total                      BIGINT NOT NULL DEFAULT 0 CHECK (total >= 0),
    computed_at                TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_power_index_total
    ON power_index_snapshots (total DESC, computed_at DESC);
