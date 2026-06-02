-- V45: Block change journal (Wave 6c, ADR-0160).
--
-- Write-through journal for `PaperBlockStateGateway` per-cell block state.
-- Distinct from V20 `block_durability` (Wave 5x, ADR-0090): the durability
-- table was wired for a periodic-flush model that the gateway never
-- consumed; this table is the new write-through journal that survives
-- `/restart` mid-match. Forward-only break per CLAUDE.md
-- §Pre-release posture — no fallback shim, no dual-write to V20.
--
-- Schema notes
--   world_id : VARCHAR(64) mirrors BlockKey.worldId (currently a UUID
--              string; the column stays VARCHAR so non-vanilla world
--              identifiers — eg. SkyBlock instance ids — remain valid
--              without an enum tax later).
--   pos_*    : INT — Vec3 fractional axes are floored at the BlockKey
--              construction site, so storage is integral cell coords.
--   state    : JSONB — single-row BlockState payload (blockType,
--              durability, lastDamagedTick, damagedBy, maxHp). JSONB
--              keeps the schema forward-flexible if a future BlockState
--              variant carries additional fields without an ALTER TABLE.
--   updated_at: TIMESTAMPTZ — observability + future janitor jobs
--              (a Wave 6c-stub job could TTL-prune rows for matches
--              that ended >30d ago).
--
-- Composite primary key matches BlockKey's identity (worldId, x, y, z);
-- the lookup path `loadAll(worldId)` is supported by the implicit prefix
-- match on the PK plus an explicit index on `world_id` for clarity.

CREATE TABLE IF NOT EXISTS block_change_journal (
    world_id   VARCHAR(64) NOT NULL,
    pos_x      INTEGER     NOT NULL,
    pos_y      INTEGER     NOT NULL,
    pos_z      INTEGER     NOT NULL,
    state      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT block_change_journal_pkey
        PRIMARY KEY (world_id, pos_x, pos_y, pos_z)
);

CREATE INDEX IF NOT EXISTS block_change_journal_world_idx
    ON block_change_journal (world_id);
