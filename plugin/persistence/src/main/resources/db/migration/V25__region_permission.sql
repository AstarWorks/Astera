-- Wave 5n (World env + Per-match config + Spatial index + Position query) ADR-0080.
--
-- Persists per-region permission rules used by IRegionPermissionService. A
-- "rule" is the tuple (region_id, permission_key, scope, player_id?).
--
-- permission_key encodes the [com.astarworks.astera.core.domain.region.RegionPermission]
-- variant as a stable string. Object variants (Build / Break / Interact / PvP
-- / Pickup / Drop) use their lowercase name; the `Use(itemId)` variant uses
-- the form `use:<itemId>` to keep the column key-shaped.
--
-- scope ∈ ('allow', 'deny') — the rule's effect when matched.
--
-- player_id NULL means "applies to everyone"; a non-null UUID restricts the
-- rule to that single player. Multi-player rules are stored as multiple rows
-- (one per player) so the table stays first-normal.
--
-- (region_id, permission_key, player_id) is the natural key. player_id is
-- nullable so the PK becomes (region_id, permission_key, COALESCE(player_id,
-- '00000000-0000-0000-0000-000000000000')) — but Postgres treats NULL as
-- distinct in unique constraints, so we use a partial unique index instead.
--
-- Lookup pattern (hot path):
--   SELECT scope FROM region_permission
--   WHERE region_id = ? AND permission_key = ?
--     AND (player_id IS NULL OR player_id = ?)
--   ORDER BY player_id NULLS LAST  -- player-specific wins over everyone
--   LIMIT 1;
CREATE TABLE region_permission (
    region_id        VARCHAR(64)  NOT NULL,
    permission_key   VARCHAR(96)  NOT NULL,
    scope            VARCHAR(8)   NOT NULL CHECK (scope IN ('allow', 'deny')),
    player_id        UUID         NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial unique index: at most one (region, permission, player) row, and
-- separately at most one (region, permission) everyone-row.
CREATE UNIQUE INDEX region_permission_player_uq
    ON region_permission (region_id, permission_key, player_id)
    WHERE player_id IS NOT NULL;

CREATE UNIQUE INDEX region_permission_everyone_uq
    ON region_permission (region_id, permission_key)
    WHERE player_id IS NULL;

-- Hot-path lookup index — region+permission+player resolves O(1) via the
-- partial uniques above; this covers the "list rules for region" admin path.
CREATE INDEX region_permission_region_idx
    ON region_permission (region_id);
