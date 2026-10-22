-- ADR-0235: resumable onboarding and one graduation reward per player.
CREATE TABLE player_tutorial_state (
    player_id UUID PRIMARY KEY,
    script_id TEXT NOT NULL,
    step_index INTEGER NOT NULL CHECK (step_index >= 0),
    started_at_tick BIGINT NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    reward_granted BOOLEAN NOT NULL DEFAULT FALSE
);
