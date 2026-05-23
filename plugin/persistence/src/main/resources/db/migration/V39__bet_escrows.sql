-- Wave 5bet / ADR-0103 §Persistence: per-bet escrow audit table.
--
-- One row per `IBettingEscrowService.hold()` invocation. The lifecycle is
-- driven by the in-memory or Postgres impl of `IBettingEscrowService`:
--   Held → Paid     (releaseToWinner)
--   Held → Refunded (refundBoth — draw / cancel / cleanup)
--
-- Why a separate row from `betting_matches`:
--   - escrow movement is the SSOT for compliance / audit (Wave 5e log feeds
--     this table)
--   - separating it lets the V38 row update on resolve be a cheap status flip,
--     while V39 inserts on hold and updates exactly once on settlement
--   - the matchmaking history scan never needs to join escrow state
--
-- Mirror of `betting_matches.id` via FK keeps integrity tight; ON DELETE
-- RESTRICT enforces "you cannot drop a betting match while its escrow row
-- exists" (we never delete bet history in Phase 1).
CREATE TABLE bet_escrows (
    betting_match_id   UUID PRIMARY KEY
                              REFERENCES betting_matches (id) ON DELETE RESTRICT,
    challenger_id      UUID NOT NULL,
    opponent_id        UUID NOT NULL,
    currency           VARCHAR(16) NOT NULL,
    per_side_amount    BIGINT NOT NULL CHECK (per_side_amount > 0),
    status             VARCHAR(16) NOT NULL,
    held_at            TIMESTAMP NOT NULL DEFAULT now(),
    settled_at         TIMESTAMP,
    CONSTRAINT distinct_escrow_participants CHECK (challenger_id <> opponent_id),
    CONSTRAINT settled_iff_terminal CHECK (
        (status = 'Held' AND settled_at IS NULL) OR
        (status IN ('Paid', 'Refunded') AND settled_at IS NOT NULL)
    )
);

CREATE INDEX idx_bet_escrows_status
    ON bet_escrows (status);
CREATE INDEX idx_bet_escrows_challenger
    ON bet_escrows (challenger_id);
CREATE INDEX idx_bet_escrows_opponent
    ON bet_escrows (opponent_id);
