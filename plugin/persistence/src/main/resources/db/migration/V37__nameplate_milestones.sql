-- Wave 5flex / ADR-0102 surface #5: per-player current milestone for the
-- match nameplate.
--
-- The nameplate badge in-memory cache is rebuilt on join + every refresh
-- event, which is fine for the badge's recent-power-index value. The
-- *milestone* is different: it's a player-prestige signal that should
-- survive a server restart so the player still sees their "kills-1000"
-- highlight after the next match starts.
--
-- # Why a row per player, not per achievement
--
-- The nameplate only ever shows ONE milestone at a time (the most recent
-- noteworthy unlock). A history of every milestone is already covered by
-- per-player achievement records (Wave 5e — not yet persisted as of
-- Wave 5flex but planned). This table is intentionally the live "current
-- display" only.
--
-- # Update semantics
--
-- The Wave 5flex listener
-- (RefreshNameplateOnAchievementUnlockedListener) upserts on player_id;
-- only the most recent qualifying milestone is retained. An empty
-- (cleared) milestone is represented by a NULL achievement_id.
--
-- Phase 1 PoC: persistence-side reads happen on player join only; the
-- nameplate refresh uses the in-memory cache for the hot path.
CREATE TABLE nameplate_milestones (
    player_id        UUID PRIMARY KEY,
    achievement_id   VARCHAR(128),
    milestone_key    VARCHAR(192),
    set_at           TIMESTAMP NOT NULL DEFAULT now()
);
