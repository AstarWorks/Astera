---
status: Accepted
date: 2026-10-28
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0302: Items are stored as ids and rebuilt on load (ECO-12)

## Status

Accepted (founder 2026-10-28, ECO-12). The brief reserved number 0299, but `0299-chat-ampersand-decoration-by-rank.md`
already exists on `main`; this ADR takes the next free number.

## Context

Founder 2026-10-28: when a player leaves the server the whole inventory becomes an id list, joining rebuilds the
`ItemStack`s from that list (so they are always current); the personal vault (ender chest) works the same, and so
must the shop and every other store. An item turns into id space the moment it is put in.

Observed problem: after restarts, weapons in the founder's inventory and vault kept their old stacks, so the new
rarity `tooltip_style` and the hidden vanilla metadata (`PaperItemMaterializer.buildFor` / `refreshVisuals`,
a653735d5) were never applied.

### Survey: every place an item stack is persisted or survives a restart

| Place | Before | Now |
|---|---|---|
| Player inventory (join/quit, hand-off, per-match entry snapshot): `PaperInventorySnapshotSerializer` -> `player_inventory_snapshots.snapshot` JSONB (V10) | every slot a Base64 `BukkitObjectOutputStream` ItemStack (`SerializedBukkit`) | id slots: `ContentItem{itemId,count,state}` / `VanillaItem{materialKey,count,state}` |
| Personal vault: `PaperVaultWindow` -> `personal_vaults.items` JSONB (V7) | `itemId` = material key plus Base64 `serializeAsBytes()` in `nbt` | `VaultItem{slot,itemId,count,state}`, no bytes |
| Vanilla playerdata (`world/playerdata`) | holds the live inventory; only read when no snapshot exists | unchanged; it is not authoritative once a snapshot exists (`PlayerHandoffService.applySnapshot` restores the snapshot on every join) |
| Shop (`content/shops`, `shop_purchases` V64) | stores ids and prices only; goods are built through `IItemDelivery` / the materializer on purchase and on display | already id based, nothing to change |
| Marketplace listings (V12), loadouts (V5), item instances / refinement (V33) | rows hold `item_id` strings, no stack bytes | already id based |
| `PaperToolBlockGateway.heldTool` | serializes the held tool into a Base64 string | transient: lives in a cover-block break request of the running match, never in the DB; left as is |
| Ground drops, item frames, training rack | world entities, not item storage; the rack is rebuilt from `IItemRegistry` | out of scope |

Per-instance state that really exists on a stack: durability damage and vanilla enchantments (the enchanting
table is allowed for `enchantable` items). Refinement layers live in `item_instances` by instance id, not on the
stack. Everything else (name, lore, rarity frame, models, attributes, PDC flags, cooldown group, glint) is derived
from `content/items` by `PaperItemMaterializer`.

## Decision

The persisted form of a stack is `{itemId, count, state}` with `state = StoredItemState(damage, enchants)`.

- `PaperStoredItemCodec` (core, Paper adapter) is the single stack <-> id conversion used by the inventory
  snapshot and the vault. Encoding reads the `item_id` PDC string; decoding calls
  `PaperItemMaterializer.buildFor(spec, owner, count)` against the **current** `IItemRegistry` and then applies the
  state. The hidden glint enchantment (`luck_of_the_sea` 1) of Astera stacks is not stored; the materializer re-adds it.
- Vault: the window converts to ids on close (the moment an item is put in and the window closes), and rebuilds
  every slot from ids on open.
- Inventory: join restores the snapshot (already the path), so every Astera stack is re-materialized from its id.
- A stored id whose definition was deleted is dropped with a WARN (`<where>: item id '<id>' (xN) has no current definition; dropped`).
- Non-Astera items are stored as the vanilla material key (`minecraft:...`) plus count and state. Names, lore and any
  other metadata on vanilla stacks are lost by design. A vault item id starting with `minecraft:` is vanilla.
- `InventoryItemSlot.SerializedBukkit`, `VaultItem.nbt` and the unused `IContentItemRegistryLookup` (+ impl, fake) are deleted.
- Migration V73 (up-only) deletes all `player_inventory_snapshots` and `personal_vaults` rows: old rows are Base64
  bytes that cannot become ids in SQL, and there is no legacy decoder (pre-release posture). Players keep their
  live inventory through vanilla playerdata (no snapshot means "inventory untouched") and the first quit writes the
  id-based snapshot. Vault contents are lost.

## Consequences

### Positive
- Editing `content/items` (name, lore, rarity, model) reaches every stored item on the next join / vault open; no
  `items refresh` needed for stored items.
- Stored data is small, human-readable and independent of the Minecraft data version.

### Negative / Trade-offs
- Custom per-stack edits (anvil rename, custom lore, vanilla item metadata, attribute-modifier tweaks) do not
  survive storing. Only durability damage and enchantments do.
- A restore with `PaperItemMaterializer` or `IItemRegistry` unavailable (the items plugin disabled) drops Astera
  slots; the slot is then saved empty on the next quit.
- Existing vault contents are discarded by V73.

### Mitigations
- Items plugin is a hard prerequisite of inventory-bearing gameplay; join happens after every plugin has enabled.
- New per-instance state, if ever needed, is added to `StoredItemState` (one DTO, one codec).

## Alternatives Considered

- **Keep ItemStack bytes and re-run `refreshVisuals` on load**: fixes the visuals but keeps stale data (renamed or
  deleted items, changed material) alive; contradicts the founder's id-space model.
- **SQL conversion of old vault rows**: the Astera id sits inside Base64 NBT; not expressible in SQL.
- **Legacy decoder for `SerializedBukkit`**: forbidden by the pre-release posture.

## References

- Tests: `tools/e2e` `ItemIdStorageE2eTest`; `PostgresPlayerInventorySnapshotRepositoryIT`, `PostgresPersonalVaultRepositoryIT`.
- 関連 ADR: [[adr/0073-item-centric-content]], [[adr/0158-paper-inventory-snapshot]], [[adr/0211-personal-vault-pages]], [[adr/0224-held-item-refresh]], [[adr/0241-item-materializer-single-source]]
