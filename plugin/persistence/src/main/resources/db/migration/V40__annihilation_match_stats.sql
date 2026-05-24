-- Wave 5annihilation / ADR-0108: per-team Annihilation match stats overlay.
--
-- One row per (match_id, team_id) pair for every Annihilation match. Captures
-- the per-team Nexus state + score + elimination outcome so post-match audit /
-- leaderboard / Battle pass progression can read a single row per
-- participant team without re-deriving from event logs.
--
-- This is an additive overlay — it does NOT replace `matches` / `teams`
-- (those tables remain authoritative for the match identity). The
-- AnnihilationMatchTracker in-memory state is the runtime SSOT; this table
-- is populated at match-end (or on server-restart recovery in Wave 6) via a
-- single INSERT per surviving team and an UPDATE on the eliminated team's
-- row when their Tier 3 falls.
--
-- Why per-team-per-match (not per-pillar-per-match):
--   - The audit / leaderboard care about team-level outcomes.
--   - Per-pillar HP traces live in the event log (`NexusDamaged` /
--     `NexusDestroyed` rows in Wave 5e audit table). Re-projecting them as
--     pillar-row state here would duplicate the event log.
--   - 3 teams × 3 pillars per match = 9 rows / match; team-level is 3 / match
--     and keeps the leaderboard scan cheap.
--
-- The (match_id, team_id) compound key matches the Postgres convention used
-- by `loadouts`, `bet_escrows`, etc.

CREATE TABLE annihilation_match_stats (
    match_id                       VARCHAR(64) NOT NULL,
    team_id                        VARCHAR(64) NOT NULL,
    pillar_count_started           INT  NOT NULL CHECK (pillar_count_started >= 0),
    pillar_count_destroyed         INT  NOT NULL CHECK (pillar_count_destroyed >= 0),
    final_aggregate_hp             INT  NOT NULL CHECK (final_aggregate_hp >= 0),
    final_phase                    VARCHAR(32) NOT NULL,
    eliminated                     BOOLEAN NOT NULL DEFAULT FALSE,
    eliminated_at_tick             BIGINT,
    final_outcome                  VARCHAR(16) NOT NULL,
    -- Final outcome: 'winner' | 'loser' | 'eliminated' | 'draw'
    created_at                     TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, team_id),
    CONSTRAINT destroyed_le_started CHECK (pillar_count_destroyed <= pillar_count_started),
    CONSTRAINT eliminated_iff_tick CHECK (
        (eliminated = TRUE  AND eliminated_at_tick IS NOT NULL) OR
        (eliminated = FALSE AND eliminated_at_tick IS NULL)
    ),
    CONSTRAINT outcome_kind CHECK (
        final_outcome IN ('winner', 'loser', 'eliminated', 'draw')
    )
);

-- Index for the leaderboard scan: "winners by team in this date window".
CREATE INDEX idx_ann_stats_team_outcome
    ON annihilation_match_stats (team_id, final_outcome);

-- Index for the per-match read at audit-time.
CREATE INDEX idx_ann_stats_match
    ON annihilation_match_stats (match_id);
