-- Wave 5h / ADR-0074 §3: per-player tutorial completion / skip / cursor state.
-- One row per player; latest-write-wins (no history). The two `*_tutorials`
-- JSONB columns each carry a JSON array of TutorialScriptId strings
-- (lower-kebab-case). `current_tutorial` + `current_step` are the in-flight
-- cursor and are nullable (both null when no tutorial is running).
CREATE TABLE player_tutorial_state (
    player_id            UUID PRIMARY KEY,
    completed_tutorials  JSONB NOT NULL DEFAULT '[]'::jsonb,
    skipped_tutorials    JSONB NOT NULL DEFAULT '[]'::jsonb,
    current_tutorial     VARCHAR(64),
    current_step         INT,
    updated_at           TIMESTAMP NOT NULL DEFAULT now()
);
