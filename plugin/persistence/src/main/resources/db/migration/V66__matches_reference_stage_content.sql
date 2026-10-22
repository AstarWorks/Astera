-- ADR-0235: stages are authored content, not rows that must be mirrored into Postgres.
ALTER TABLE matches DROP CONSTRAINT matches_stage_id_fkey;
