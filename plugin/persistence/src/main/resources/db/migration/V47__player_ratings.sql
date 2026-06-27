-- V47: Per-player global Elo rating (Wave 7b, ADR-0037).
--
-- Backs `match.application.port.outbound.rating.IPlayerRatingRepository`.
-- Overlays the in-memory default in `plugin:match` (priority 0) with a Postgres
-- impl (priority 100). Up-only per CLAUDE.md §Pre-release posture.
--
-- Schema notes
--   player_id : UUID PK mirrors PlayerId.uuid — one rating row per player.
--   rating    : INT — bounded [Rating.MIN, Rating.MAX] = [0, 5000] in the
--               domain; stored as a plain int (Rating value class does not
--               cross the persistence boundary).
--   updated_at: TIMESTAMPTZ — observability of the last rating change.

CREATE TABLE IF NOT EXISTS player_ratings (
    player_id  UUID        NOT NULL,
    rating     INTEGER     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT player_ratings_pkey PRIMARY KEY (player_id)
);
