-- Wave 5bet / ADR-0103 §Persistence: 1v1 betting match record.
--
-- One row per [com.astarworks.astera.economy.domain.betting.BettingMatch].
-- The PvP match identifier ([com.astarworks.astera.core.domain.match.MatchId])
-- is stored as the human-authored lower-kebab-case string for parity with
-- every other table that references it (it is NOT a UUID — see
-- `MatchId.kt`). It is nullable because pre-acceptance records do not yet
-- have an underlying PvP match assigned.
--
-- # Why a separate table from `bet_escrows`
--
-- Escrow state has its own audit lifecycle (held → paid / refunded with
-- per-side amount). Conflating it with the match record would force a
-- transactional update of the match row on every wallet movement and would
-- bloat history scans by the daily-cap aggregator. Same split rationale as
-- ADR-0035 marketplace listing ↔ fulfillment.
--
-- # Queryable columns vs JSONB
--
-- Per ADR-0014: every column the use case filters on (status / accepted_at /
-- match_id / stake_currency / stake_amount / player columns) is first-class.
-- The terminal-state metadata (winner_id, end_reason_kind, end_reason_detail)
-- could collapse into a JSONB but the cardinality is tiny (5 sealed BettingEndReason
-- variants) and the columns make the audit-grade reports trivial.
--
-- # Indexes
--
--   - `idx_betting_matches_status` — expiry sweeper + admin "all open" views.
--   - `idx_betting_matches_match_id` — `ResolveBettingOnMatchEndedListener` lookup.
--   - `idx_betting_matches_challenger_created` — per-player history (recent + cap aggregation).
--   - `idx_betting_matches_opponent_created`   — per-player history (recent + cap aggregation).
CREATE TABLE betting_matches (
    id                       UUID PRIMARY KEY,
    policy_id                VARCHAR(64) NOT NULL,
    challenger_id            UUID NOT NULL,
    opponent_id              UUID NOT NULL,
    stake_currency           VARCHAR(16) NOT NULL,
    stake_amount             BIGINT NOT NULL CHECK (stake_amount > 0),
    status                   VARCHAR(32) NOT NULL,
    created_at               TIMESTAMP NOT NULL DEFAULT now(),
    accepted_at              TIMESTAMP,
    resolved_at              TIMESTAMP,
    acceptance_deadline      TIMESTAMP NOT NULL,
    match_id                 VARCHAR(128),
    winner_id                UUID,
    end_reason_kind          VARCHAR(32),
    end_reason_detail        TEXT,
    CONSTRAINT distinct_participants CHECK (challenger_id <> opponent_id)
);

CREATE INDEX idx_betting_matches_status
    ON betting_matches (status);
CREATE INDEX idx_betting_matches_match_id
    ON betting_matches (match_id)
    WHERE match_id IS NOT NULL;
CREATE INDEX idx_betting_matches_challenger_created
    ON betting_matches (challenger_id, created_at DESC);
CREATE INDEX idx_betting_matches_opponent_created
    ON betting_matches (opponent_id, created_at DESC);
