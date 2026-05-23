-- Wave 5flex / ADR-0102 surface #3: PublicProfileCard snapshot table.
--
-- One row per player, upserted by Core's RecomputeProfileCardUseCase whenever
-- a contributing event fires (AchievementUnlocked / ItemInstanceRefined /
-- PowerIndexComputed). The Wave 8b REST adapter reads this row directly so
-- /api/players/{id}/profile is a single-key lookup.
--
-- # Summary columns
--
-- Each contributing plugin owns one slim *summary* — total counts plus the
-- single most-prominent id. The Wave 8a Web admin view can drill down via
-- the per-plugin endpoints (achievements, items, cosmetics) when the player
-- clicks "view all".
--
-- # Visibility column
--
-- `visibility` is a denormalised copy of Wave 5e PlayerSettings.publicProfileEnabled.
-- Storing it on the snapshot row lets the REST layer enforce privacy with a
-- single SQL read instead of joining player_settings on every request. The
-- recomputation use case re-reads PlayerSettings on every refresh so the
-- denormalised value stays in sync.
--
-- # No JSONB
--
-- Per ADR-0014 JSONB-usage policy: filterable columns are first-class
-- columns; we never query inside JSONB except for variant-shaped payload.
-- The card's structure is fixed at the SQL boundary so flat columns win.
CREATE TABLE profile_card_snapshots (
    player_id              UUID PRIMARY KEY,
    display_name           VARCHAR(64) NOT NULL,
    visibility             VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
        CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    equipment_item_count   INTEGER NOT NULL DEFAULT 0 CHECK (equipment_item_count >= 0),
    equipment_featured_id  VARCHAR(128),
    titles_unlocked_count  INTEGER NOT NULL DEFAULT 0 CHECK (titles_unlocked_count >= 0),
    titles_equipped_id     VARCHAR(128),
    cosmetics_unlocked_count INTEGER NOT NULL DEFAULT 0 CHECK (cosmetics_unlocked_count >= 0),
    cosmetics_featured_id  VARCHAR(128),
    power_index_total      BIGINT NOT NULL DEFAULT 0 CHECK (power_index_total >= 0),
    computed_at            TIMESTAMP NOT NULL DEFAULT now()
);

-- Partial index over PUBLIC profiles for the Web admin landing page
-- (showcase of every public card). PRIVATE rows are excluded so the scan
-- is cheap even at scale.
CREATE INDEX idx_profile_card_public_computed_at
    ON profile_card_snapshots (computed_at DESC)
    WHERE visibility = 'PUBLIC';
