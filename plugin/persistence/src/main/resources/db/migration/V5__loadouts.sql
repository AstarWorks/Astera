-- Wave 4.5 (R3.5) / ADR-0027: per-player loadout assignments survive restarts.
-- Postgres-backed overlay of InMemoryLoadoutRepository registered by the
-- weapons plugin at priority 0. Persistence overlays at priority 100 when
-- ASTERA_PG_* env vars are present, following the Wave 3 (ADR-0020) pattern.
--
-- One row per (player, slot). The (player_id, slot) compound PK enforces the
-- "one item per slot" domain invariant at the DB layer so any direct write
-- also respects it. `slot` stores the LoadoutSlot enum name (MAIN / SUB /
-- MOVE / SPECIAL / ARMOR / ULTIMATE) as TEXT for forward compatibility — new
-- slots added in Phase 2+ do not require an ALTER TYPE. `item_id` stores the
-- ItemId.value (lower-kebab-case) as TEXT.
CREATE TABLE loadouts (
    player_id   UUID NOT NULL,
    slot        TEXT NOT NULL,
    item_id     TEXT NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, slot)
);

CREATE INDEX idx_loadouts_player ON loadouts(player_id);
