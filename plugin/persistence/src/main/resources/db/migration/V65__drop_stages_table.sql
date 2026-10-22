-- ADR-0226: stages are content (content/stages/*.yaml, StageContents). The
-- Postgres stages table was never populated and the overlay is gone; matches
-- keep only the stage id as plain text.
ALTER TABLE matches DROP CONSTRAINT IF EXISTS matches_stage_id_fkey;
DROP TABLE IF EXISTS stages;
