-- Wave 5pve-boss / ADR-0098: boss-kill audit table.
--
-- One row per resolved boss instance. Written by IBossKillAuditWriter when
-- IBossLifecycleService.onDeath fires AND BossSpec.recordKill = true. The
-- Phase 1 in-core writer (InMemoryBossKillAuditWriter) keeps rows in
-- memory; the persistence plugin overlays a Postgres-backed impl at
-- priority 100 once Wave 6c wires it.
--
-- Per ADR-0068 (cross-server persistence boundary), boss kills are
-- server-instance bound (the kill happened on **this** server) but the row
-- crosses to the central Postgres so leaderboards + weekly-clear gates +
-- speedrun timing see every server's kills.
--
-- Idempotency: `mob_id` is the natural unique key. The lifecycle service
-- may retry on transient failure; the UNIQUE constraint + ON CONFLICT DO
-- NOTHING in the impl prevents duplicate rows for the same mob instance.
--
-- Columns:
--  - `id` — surrogate PK (UUID v7-ish friendly, set by impl).
--  - `mob_id` — runtime MobId.value (UUID string) of the killed boss. UNIQUE.
--  - `boss_id` — BossId.value (e.g. `astera:scorched-titan`).
--  - `killer_id` — player credited with the killing blow. Nullable for
--    environment / suicide death (rare in raid context but possible).
--  - `party_id` — raid party that claimed the boss. Nullable iff the boss's
--    RaidLockPolicy was Open (admin spawn / tutorial).
--  - `final_phase` — 0-based phase index the boss was in at death.
--  - `fight_duration_ticks` — server ticks from spawn to death.
--  - `killed_at` — wall-clock instant.
--  - `at_tick` — server tick at death (paired with killed_at; tick rolls over
--    on server restart whereas killed_at is monotonic).
--
-- Indexes:
--  - `idx_boss_kills_boss_recent` — leaderboard "fastest 10 kills of boss X"
--  - `idx_boss_kills_party` — "weekly clears by party Y"
--  - `idx_boss_kills_killer` — "first-clear history for player Z"
CREATE TABLE boss_kills (
    id                    UUID PRIMARY KEY,
    mob_id                VARCHAR(64) NOT NULL UNIQUE,
    boss_id               VARCHAR(128) NOT NULL,
    killer_id             UUID,
    party_id              VARCHAR(64),
    final_phase           SMALLINT NOT NULL CHECK (final_phase >= 0),
    fight_duration_ticks  BIGINT NOT NULL CHECK (fight_duration_ticks >= 0),
    killed_at             TIMESTAMP NOT NULL DEFAULT now(),
    at_tick               BIGINT NOT NULL CHECK (at_tick >= 0)
);

CREATE INDEX idx_boss_kills_boss_recent
    ON boss_kills (boss_id, fight_duration_ticks ASC, killed_at DESC);

CREATE INDEX idx_boss_kills_party
    ON boss_kills (party_id, killed_at DESC)
    WHERE party_id IS NOT NULL;

CREATE INDEX idx_boss_kills_killer
    ON boss_kills (killer_id, killed_at DESC)
    WHERE killer_id IS NOT NULL;
