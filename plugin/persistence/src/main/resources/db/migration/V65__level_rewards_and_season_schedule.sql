-- Atomic lifetime level claims and permanent typed entitlement IDs (ADR-0235).
CREATE TABLE level_reward_claims (
    player_id UUID NOT NULL,
    level INTEGER NOT NULL CHECK (level >= 2),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, level)
);
CREATE TABLE player_unlocks (
    player_id UUID NOT NULL,
    unlock_id TEXT NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, unlock_id)
);
CREATE TABLE season_schedule_resets (
    schedule_key TEXT PRIMARY KEY,
    reset_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
