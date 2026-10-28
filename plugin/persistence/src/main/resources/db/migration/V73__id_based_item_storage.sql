-- ADR-0302: inventories and vaults store item ids, not serialized ItemStacks.
-- The old rows hold Base64 Bukkit ItemStack bytes (snapshot `type: bukkit`, vault `nbt`) that no code reads
-- any more; they cannot be converted to ids in SQL, so they are discarded (pre-release posture, up-only).
-- The next quit / vault close writes the id-based form.
DELETE FROM player_inventory_snapshots;
DELETE FROM personal_vaults;
