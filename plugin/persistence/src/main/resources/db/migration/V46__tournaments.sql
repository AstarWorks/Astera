-- V46: Tournament brackets (Wave 7b, ADR-0037).
--
-- Single-elimination tournament state. Mirrors `match.domain.tournament.Tournament`
-- and overlays the in-memory default registered by `plugin:match` at priority 0
-- (`PostgresTournamentRepository`, priority 100). Up-only per CLAUDE.md
-- §Pre-release posture — no down migration, no dual-write shim.
--
-- Schema notes
--   id        : VARCHAR(64) mirrors TournamentId (lower-kebab-case string;
--               same id discipline as MatchId / TeamId — see V<matches>).
--   name      : VARCHAR — human-facing tournament name (Tournament.name,
--               require(name.isNotBlank())).
--   status    : VARCHAR(16) — TournamentStatus enum name (PENDING / ACTIVE /
--               CONCLUDED), stored as the enum constant name.
--   bracket   : JSONB — single payload carrying both seeds (ordered seeding)
--               and rounds/matches (ADR-0014). The bracket-advance algorithm
--               keeps Tournament a pure serialisable value (Konsist rule 6 —
--               no kotlinx.serialization in domain), so the persistence layer
--               encodes seeds + rounds via `TournamentBracketJson` and stores
--               the result here. JSONB keeps the round/match shape forward-
--               flexible without an ALTER TABLE.
--   updated_at: TIMESTAMPTZ — observability + future janitor jobs.

CREATE TABLE IF NOT EXISTS tournaments (
    id         VARCHAR(64) NOT NULL,
    name       VARCHAR     NOT NULL,
    status     VARCHAR(16) NOT NULL,
    bracket    JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tournaments_pkey PRIMARY KEY (id)
);
