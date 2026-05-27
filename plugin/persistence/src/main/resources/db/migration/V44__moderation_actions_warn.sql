-- Wave 5complete / ADR-0117 §Axis 3: extend `moderation_actions.kind`
-- CHECK constraint to accept the new `'warn'` variant. Forward-only
-- (Phase 1 PoC posture); no down migration. Existing rows are untouched
-- (the existing four kinds remain valid).
--
-- The CHECK is dropped + recreated because Postgres doesn't ship an
-- in-place "alter check constraint" — the only forward path is drop +
-- recreate with the expanded value set.
ALTER TABLE moderation_actions DROP CONSTRAINT moderation_actions_kind_check;
ALTER TABLE moderation_actions
    ADD CONSTRAINT moderation_actions_kind_check
    CHECK (kind IN ('mute', 'kick', 'ban', 'chat_rate_limit', 'warn'));
