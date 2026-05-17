-- Wave 3 (R2) / ADR-0020: matches + stages persistence.
-- `teams` / `score` / `regions` are JSONB blobs — see ADR-0014 (aggregate
-- payloads that travel together belong in JSON rather than child tables).
--
-- `matches.id` is TEXT because MatchId is a kebab-case identifier, not a UUID
-- (mirrors StageId / TeamId / PlotId conventions in plugin/core).

CREATE TABLE stages (
    id                TEXT PRIMARY KEY,
    display_name_key  TEXT NOT NULL,
    regions           JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE matches (
    id                TEXT PRIMARY KEY,
    phase             TEXT NOT NULL,
    stage_id          TEXT NOT NULL REFERENCES stages(id),
    started_at_tick   BIGINT NOT NULL,
    teams             JSONB NOT NULL,
    score             JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_matches_phase ON matches(phase);
