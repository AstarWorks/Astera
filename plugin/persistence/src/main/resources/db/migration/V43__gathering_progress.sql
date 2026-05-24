-- Wave 5pve-survival / ADR-0107 §Persistence: per-(player, spot) harvest log.
--
-- One row per [com.astarworks.astera.core.domain.survival.GatheringProgress].
--
-- # Composite primary key
--
-- (player_id, spot_id) is the natural identity — the use case looks up
-- exactly one progress row per harvest event. Composite PK avoids a
-- surrogate key + UNIQUE index pair.
--
-- # Spot id as VARCHAR
--
-- Mirrors GatheringSpotId (lower-kebab-case). No FK to a `gathering_spots`
-- table because spots are YAML-loaded — the registry is the SSOT, not
-- the database. Orphan progress rows (spot deleted from YAML) are
-- harmless and discoverable via a periodic reconcile job.
--
-- # Why a separate table from party_islands
--
-- Per-(player, spot) gather counts are append-mostly hot data, completely
-- decoupled from island lifecycle. Same separation rationale as
-- bet_escrows ⟷ betting_matches in V38/V39 — different write rhythms,
-- different audit lifecycles.
CREATE TABLE gathering_progress (
    player_id          UUID NOT NULL,
    spot_id            VARCHAR(128) NOT NULL,
    last_harvested_at  TIMESTAMP NOT NULL,
    total_harvests     BIGINT NOT NULL DEFAULT 0 CHECK (total_harvests >= 0),
    PRIMARY KEY (player_id, spot_id)
);

-- # Indexes
--
-- forPlayer scan (player history view); spot-only scan (per-spot
-- leaderboards in analytics).
CREATE INDEX idx_gathering_progress_player_last
    ON gathering_progress (player_id, last_harvested_at DESC);
CREATE INDEX idx_gathering_progress_spot
    ON gathering_progress (spot_id);
