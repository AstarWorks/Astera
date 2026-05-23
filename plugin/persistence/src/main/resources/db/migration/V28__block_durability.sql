-- Wave 5x (ADR-0090) — cross-restart custom-block durability + per-block-type maxHp.
--
-- The in-memory IBlockStateGateway has tracked per-position state since
-- Wave 5d, and Wave _persist-1 (ADR-0068) closed the cross-server
-- persistence boundary for session / friendship / party state. Custom
-- blocks placed in the open world need the same durability guarantee:
-- server restart must NOT delete a player's hardened-soil column.
--
-- The IBlockDurabilityRepository (priority 100 overlay) takes a per-world
-- snapshot from the in-memory tracker and bulk-upserts on a periodic
-- flush + on server shutdown. On boot, AsteraCorePlugin hydrates the
-- tracker from this table before any feature plugin queries land.
--
-- Composite primary key (world_id, pos_x, pos_y, pos_z) matches the
-- in-memory tracker's `(WorldId, Vec3)` addressing. Vec3 floats are
-- truncated to INT because block coordinates are integral; placing two
-- custom blocks at fractional coordinates is not a supported use case
-- (the gateway's `track(world, pos, state)` does the truncation).
--
-- max_hp lives alongside current_hp because custom blocks may have
-- per-type max values >> the Wave 5d 100-HP default (e.g. 5000-HP
-- hardened soil). Restoring a tracked block reads its own maxHp, not
-- a constant.
--
-- damaged_by is the most-recent attacker for kill / break attribution;
-- nullable because environmental damage (Wave 6 BukkitEventAdapter
-- routes lava / projectile-from-nowhere) carries no player.

CREATE TABLE block_durability (
    world_id          VARCHAR(64) NOT NULL,
    pos_x             INTEGER NOT NULL,
    pos_y             INTEGER NOT NULL,
    pos_z             INTEGER NOT NULL,
    block_type        VARCHAR(128) NOT NULL,
    current_hp        INTEGER NOT NULL CHECK (current_hp >= 0),
    max_hp            INTEGER NOT NULL CHECK (max_hp >= 1),
    last_damaged_tick BIGINT NOT NULL CHECK (last_damaged_tick >= 0),
    damaged_by        UUID NULL,
    PRIMARY KEY (world_id, pos_x, pos_y, pos_z)
);

CREATE INDEX idx_block_durability_world
    ON block_durability (world_id);

-- Auxiliary lookup for the post-match restore path: when a match ends in
-- a per-match world, we delete every tracked position scoped to that
-- world in one statement (vs. iterating one DELETE per position).
CREATE INDEX idx_block_durability_world_type
    ON block_durability (world_id, block_type);
