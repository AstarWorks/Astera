-- Wave 5s (ADR-0091): Custom recipes + Quest chain (YAML registry).
--
-- Recipes are content-only (`content/recipes/*.yaml`); the only per-player
-- state that needs persistence is **quest chain progress** (current stage +
-- counter + completion latch that survive cross-server proxy hops, per
-- ADR-0068).
--
-- # player_quest_progress
-- One row per (player, chain) tuple. The use case writes both the active stage
-- id and the cumulative counter for that stage; on stage transitions the row
-- is overwritten with the next stage's id and stage_progress=0. Chain
-- completion latches `completed_at` (never unsets).
CREATE TABLE player_quest_progress (
    player_id        UUID NOT NULL,
    chain_id         TEXT NOT NULL,
    current_stage_id TEXT NOT NULL,
    stage_progress   INTEGER NOT NULL DEFAULT 0,
    completed_at     TIMESTAMP NULL,
    updated_at       TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, chain_id)
);

CREATE INDEX idx_player_quest_progress_player
    ON player_quest_progress(player_id);

CREATE INDEX idx_player_quest_progress_completed
    ON player_quest_progress(player_id, completed_at)
    WHERE completed_at IS NOT NULL;

-- Partial index for the "active quests" HUD panel (most reads are filtered
-- here). Negative `WHERE` is cheaper than scanning + filtering for a small
-- in-progress / large completed ratio at scale.
CREATE INDEX idx_player_quest_progress_active
    ON player_quest_progress(player_id, updated_at DESC)
    WHERE completed_at IS NULL;
