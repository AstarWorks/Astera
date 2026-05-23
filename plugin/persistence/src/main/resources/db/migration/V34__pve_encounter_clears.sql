-- Wave 5pve-content / ADR-0099: PvE encounter zone clear audit.
--
-- One row per (player, zone, clear-instance) — written when a player
-- completes the encounter goal for a zone (boss kill for boss-anchored
-- zones; quota-met for mob-grinder zones, decided by the Phase 4
-- IEncounterClearService).
--
-- Wave 5pve-content Phase 1 ships the SCHEMA only — the V34 table is
-- ready to receive rows but the writer impl lands in Wave 5pve-fix /
-- Wave 6c (similar pattern to V31 boss_kills shipping in Wave 5pve-boss
-- ahead of PaperBossLifecycleService).
--
-- Per ADR-0068 (cross-server persistence boundary), zone clears are
-- attribution-bound (the clear happened on **this** server) but the row
-- crosses to central Postgres so weekly-reset gates + first-clear
-- announcements + cross-server progress tracking see every server's clears.
--
-- Columns:
--  - `id` — surrogate PK (UUID v7-ish friendly, set by impl).
--  - `player_id` — player credited with the clear.
--  - `zone_id` — EncounterZoneId.value (e.g. `astera:blazing-pit`).
--  - `boss_kill_id` — FK to boss_kills.id when the clear was driven by a
--    boss kill (the kill row carries party / fight duration); nullable for
--    mob-grinder zone clears.
--  - `party_id` — party at clear time (nullable iff solo or open zone).
--  - `cleared_at` — wall-clock instant.
--  - `at_tick` — server tick at clear.
--
-- Indexes:
--  - `idx_pve_clears_player_recent` — "my recent clears" timeline
--  - `idx_pve_clears_zone_first` — "first to clear zone X" leaderboard
--  - `idx_pve_clears_player_zone` — fast lookup for "has player cleared this
--    zone this week" weekly-reset gates (driving query for Phase 4 reset job)
CREATE TABLE pve_encounter_clears (
    id            UUID PRIMARY KEY,
    player_id     UUID NOT NULL,
    zone_id       VARCHAR(128) NOT NULL,
    boss_kill_id  UUID,
    party_id      VARCHAR(64),
    cleared_at    TIMESTAMP NOT NULL DEFAULT now(),
    at_tick       BIGINT NOT NULL CHECK (at_tick >= 0),
    CONSTRAINT fk_pve_clears_boss_kill
        FOREIGN KEY (boss_kill_id) REFERENCES boss_kills (id) ON DELETE SET NULL
);

CREATE INDEX idx_pve_clears_player_recent
    ON pve_encounter_clears (player_id, cleared_at DESC);

CREATE INDEX idx_pve_clears_zone_first
    ON pve_encounter_clears (zone_id, cleared_at ASC);

CREATE INDEX idx_pve_clears_player_zone
    ON pve_encounter_clears (player_id, zone_id, cleared_at DESC);
