-- Wave 5p (ADR-0088): Custom Advancement + NPC Dialog tree + Tutorial sequencer.
--
-- All three subsystems are content-driven (YAML registries); the only
-- per-player state that needs persistence in Phase 1 PoC is **advancement
-- progress** (cumulative counters + completion latches that survive
-- cross-server proxy hops, per ADR-0068).
--
-- Dialog & tutorial **runtime sessions** are intentionally NOT persisted in
-- V26: a dropped session restarts cleanly on next interaction (UX-acceptable
-- for these surfaces; revisit if proxy hops mid-dialog become common in
-- Phase 4 — Wave 5e's `IPlayerTutorialStateRepository` already exists as the
-- forward pointer for tutorial state if/when that need materialises).
--
-- # player_advancement_progress
-- One row per (player, advancement) tuple. The use case stores both a row
-- under the canonical advancement_id (latched on completion) AND per-leaf
-- sub-rows keyed `<canonical>-l<index>` to track composite criteria
-- counters separately. The HUD / IAdvancementService.completed() filters
-- out the synthetic sub-rows.
CREATE TABLE player_advancement_progress (
    player_id       UUID NOT NULL,
    advancement_id  TEXT NOT NULL,
    current_count   INTEGER NOT NULL DEFAULT 0,
    target_count    INTEGER NOT NULL,
    completed_at    TIMESTAMP NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, advancement_id)
);

CREATE INDEX idx_player_advancement_progress_player
    ON player_advancement_progress(player_id);

CREATE INDEX idx_player_advancement_progress_completed
    ON player_advancement_progress(player_id, completed_at)
    WHERE completed_at IS NOT NULL;
