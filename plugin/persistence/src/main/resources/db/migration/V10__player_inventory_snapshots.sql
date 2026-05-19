-- Wave 5e / RTM issue #5: per-player Bukkit-inventory snapshot.
-- One row per player; latest-wins (no history). `snapshot` is JSONB
-- carrying the four canonical regions (hotbar [9] / main [27] /
-- offhand [1] / armor [4]) per the PlayerInventorySnapshot domain.
-- `version` matches PlayerInventorySnapshot.CURRENT_VERSION; bump
-- when the InventoryItemSlot shape changes incompatibly.
CREATE TABLE player_inventory_snapshots (
    player_id   UUID PRIMARY KEY,
    snapshot    JSONB NOT NULL,
    version     INT  NOT NULL,
    saved_at    TIMESTAMP NOT NULL DEFAULT now()
);
