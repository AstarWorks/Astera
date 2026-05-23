-- Wave 5engage (ADR-0104): per-player engagement dashboard snapshot.
--
-- One row per player; latest-write-wins (the dashboard service overwrites on
-- each recompute). `snapshot` JSONB carries the raw inputs (playtime, streak,
-- skill diversity, refinement attempts, cosmetic diversity, first/last seen)
-- plus the derived 0.0..1.0 engagement score. `computed_at` is on the row
-- (not in JSONB) so admin queries can find stale rows without parsing JSONB.
--
-- Index choice: B-tree on `computed_at` so the dashboard service's "scan all
-- rows older than X" maintenance query stays cheap as the table grows; the
-- main read path (`SELECT … WHERE player_id = ?`) uses the PK.
CREATE TABLE player_engagement_snapshots (
    player_id    UUID PRIMARY KEY,
    snapshot     JSONB NOT NULL,
    computed_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_player_engagement_snapshots_computed_at
    ON player_engagement_snapshots (computed_at);
