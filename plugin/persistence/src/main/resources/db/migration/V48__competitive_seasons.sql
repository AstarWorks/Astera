-- V48: Competitive seasons + per-season leaderboard (Wave 7b, ADR-0037).
--
-- Backs `stats.application.port.outbound.ISeasonRepository` (season lifecycle)
-- and `stats.application.port.outbound.ILeaderboardRepository` (per-season
-- ranking). Postgres overlays (priority 100) of the in-memory defaults in
-- `plugin:stats`. Up-only per CLAUDE.md §Pre-release posture.
--
-- seasons
--   id        : VARCHAR(64) PK mirrors SeasonId (lower-kebab-case slug, e.g.
--               `season-2026-summer`).
--   label     : VARCHAR — human-facing season label (Season.label, not blank).
--   started_at: TIMESTAMPTZ — season open instant.
--   ended_at  : TIMESTAMPTZ NULL — close instant; NULL while active. At most
--               one active (ended_at IS NULL) season at a time, enforced by the
--               repository's `start` (closes prior active in the same txn).
--
-- leaderboard_rankings
--   (season_id, player_id) composite PK — entries are partitioned by season, so
--   a new season starts with an empty ranking without deleting prior history.
--   rating          : INT — latest rating snapshot for the player this season.
--   wins / losses   : BIGINT — cumulative tally (matches LeaderboardEntry.wins/
--                     losses: Long). Each `apply` increments one of them.
--   updated_at      : TIMESTAMPTZ — last apply instant.
--   leaderboard_rankings_season_rating_idx covers topN (ORDER BY rating DESC
--   within a season) and rankOf (count of strictly-higher-rated rows).

CREATE TABLE IF NOT EXISTS seasons (
    id         VARCHAR(64) NOT NULL,
    label      VARCHAR     NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ NULL,
    CONSTRAINT seasons_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS leaderboard_rankings (
    season_id  VARCHAR(64) NOT NULL,
    player_id  UUID        NOT NULL,
    rating     INTEGER     NOT NULL,
    wins       BIGINT      NOT NULL DEFAULT 0,
    losses     BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leaderboard_rankings_pkey PRIMARY KEY (season_id, player_id)
);

CREATE INDEX IF NOT EXISTS leaderboard_rankings_season_rating_idx
    ON leaderboard_rankings (season_id, rating DESC);
