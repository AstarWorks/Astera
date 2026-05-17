-- Wave 4.5 (R3.5) / ADR-0027: persistent Norma (mission) progress.
-- One row per (player, norma) tuple; period rollover (daily/weekly) is the
-- responsibility of the loader/scheduler in Phase C — this table is the
-- raw "where is this player on this norma" store.
CREATE TABLE norma_progress (
    player_id     UUID NOT NULL,
    norma_id      TEXT NOT NULL,
    current_count BIGINT NOT NULL,
    completed     BOOLEAN NOT NULL DEFAULT false,
    completed_at  TIMESTAMP NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, norma_id)
);

CREATE INDEX idx_norma_progress_player ON norma_progress(player_id);
