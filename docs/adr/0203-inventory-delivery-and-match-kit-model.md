# ADR-0203: Inventory model in practice - one item delivery path, starter grant, match loaner kit, match sandbox

Status: Accepted (inventory domain audit). Builds on ADR-0187 (hand-off), ADR-0198 (entry snapshot) and the WP12
inventory decision (loadout = pre-match kit, snapshot = per-match save/restore, vault = ender chest).

## Problems found

1. **Two item delivery paths, one broken.** `ShopPurchaseUseCase` delivered through `IMcPlayer.giveItem`
   (`PaperPlayer`), which tags the stack with PDC `astera:item-id`; the Items plugin reads `astera-items:item_id`.
   A bought weapon was an inert vanilla stack: no skill bindings, no attributes, no cooldown group.
2. **The starter grant was never delivered.** `InMemoryPlayerProfileService` resolved `default_loadout` into a map
   nothing read, so a new player started with an empty inventory.
3. **Unarmed match entry.** A player with no weapon entered a match with nothing to fight with.
4. **Snapshot holes.** (a) A vault window open while a match starts: items taken out after the entry snapshot were
   erased by the match-end restore. (b) Dropping an item in a match leaves an entity that survives the restore
   (duplicate). (c) Shop purchases in a match are overwritten by the restore while the currency stays spent.
   (d) Reopening the vault while a window is still open builds the new window from the stale stored copy
   (duplicate when an item was taken out of the old one).

## Decisions

- `IItemDelivery` / `DeliveryResult` / `ItemOrigin` move from the Items plugin to core
  (`core.application.port.outbound.item`). Items still owns the Paper implementation (`PaperItemDelivery`,
  `PaperItemMaterializer`) and registers it. Every grant of an Astera item - shop, starter grant, loaner kit,
  admin - goes through it. `IMcPlayer.giveItem` is no longer used for content items. `ShopPurchaseUseCase`
  resolves the port lazily and refuses before the debit when it is missing.
- `DeliverStarterItemsListener` hands out the starter items on `FirstTimeProfileCreated` (deferred to
  `PlayerArrivedOnServer` while the session is `Loading`).
- **Loadout = what the player carries.** There is no separate selection screen (WP12 removed it); spares live in
  the vault. The match kit is therefore the inventory at entry. `MatchLoanerKit` closes the one hole: after the entry
  snapshot, a player carrying no item with a skill binding is lent the starter weapons. They are not in the snapshot,
  so the match-end restore removes them (cannot be kept or duplicated). Core learns what is carried through the new
  `IOwnedItemQuery` port, implemented by Items from the PDC item id (lobby training-rack stacks are excluded).
- `PaperPlayerInventoryGateway.capture/apply` close any open container window first (vault, GUI), which also saves the
  vault and returns the cursor stack.
- `MatchItemSandboxListener` cancels `PlayerDropItemEvent` for players whose entry snapshot is held. Pickups stay
  allowed (objective items use the same Bukkit event).
- `ShopPurchaseUseCase` fails with `BlockedInMatch` while the entry snapshot is held.
- `IVaultWindow.closeFor` runs before the vault is read for a new window.

## Consequences / not done

- `Loadout`, `LoadoutSlot`, `ILoadoutRepository`, the Postgres table and the `loadout:` GUI binding are still in the
  tree with no writer (removal touches the GUI DSL owned by HUD/UI; follow-up).
- The PvP death policy mutator (`IInventoryMutator`) has no Paper implementation, so `DropAll` / `DropEquipped` are
  no-ops: Astera handles death itself (health floor 1), inventories are untouched. With the snapshot sandbox that is
  the consistent behaviour; a real drop rule would need the dropped entities cleaned up at match end.
- Containers in the match world (chests) are not blocked; a stage must not contain them.
