-- V62: period rollover for daily / weekly normas (ADR-0199). period_key names the
-- window the row belongs to (e.g. 2026-10-22, 2026-W43); a row whose key differs from
-- the current window is stale and counts as 0 progress. '' = a pre-V62 / non-resetting row.
ALTER TABLE norma_progress ADD COLUMN period_key TEXT NOT NULL DEFAULT '';
