-- V61: daily login-bonus streak per player (ADR-0199). last_claim_date is the
-- server-zone calendar day of the last successful claim.
CREATE TABLE login_streaks (
    player_id       UUID PRIMARY KEY,
    streak_days     INT       NOT NULL CHECK (streak_days >= 0),
    last_claim_date DATE      NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
