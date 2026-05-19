-- Wave 5e / RTM issue #3: per-player persistent EnderChest equivalent.
-- One row per player; `items` is a JSONB array of {itemId, count, nbt?}
-- per the PersonalVault.VaultItem domain. Latest-write-wins; capacity
-- enforcement is application-layer (no DB CHECK on JSONB array length).
CREATE TABLE personal_vaults (
    player_id   UUID PRIMARY KEY,
    items       JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
