-- Match the in-memory season default so a fresh Postgres server records ranked results.
-- Preserve any existing season history and the operator's current season selection.
INSERT INTO seasons (id, label, started_at, ended_at)
SELECT 'season-1', 'Season 1', now(), NULL
WHERE NOT EXISTS (SELECT 1 FROM seasons);
