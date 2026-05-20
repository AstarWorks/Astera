-- Wave 5t (ADR-0086) — V24 player feedback queue.
--
-- Backs IPlayerFeedbackQueue. Players submit via `/astera report …`;
-- GMs triage via `/astera feedback list` + `/astera feedback triage`.
-- Composes with Wave 5e audit log for the unified per-player history view.
--
-- `category_key` is the FeedbackCategory.key value ("cheating" / "bug" /
-- "harassment" / "suggestion" / "other"). `target_player_id` is nullable
-- because `Suggestion` and `Other` don't necessarily name a target.
-- `comment` is capped at 1024 chars by the application-side validator;
-- a TEXT column is fine here and lets us extend the cap without migration.

CREATE TABLE player_feedback (
    id                 UUID         PRIMARY KEY,
    submitted_by       UUID         NOT NULL,
    target_player_id   UUID,
    category_key       TEXT         NOT NULL,
    category_freeform  TEXT,                          -- non-null only for "other"
    comment            TEXT         NOT NULL,
    submitted_at       TIMESTAMP(3) NOT NULL,
    status             TEXT         NOT NULL          -- open / in_progress / resolved / dismissed
);

CREATE INDEX idx_player_feedback_status_submitted
    ON player_feedback (status, submitted_at DESC);

CREATE INDEX idx_player_feedback_submitted_by
    ON player_feedback (submitted_by);

CREATE INDEX idx_player_feedback_target
    ON player_feedback (target_player_id)
    WHERE target_player_id IS NOT NULL;
