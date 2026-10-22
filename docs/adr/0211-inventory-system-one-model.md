---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (Inventory System owner)
supersedes: null
superseded_by: null
---

# ADR-0211: The inventory system as one model - player inventory, vault and shop

## Status

Accepted. Builds on ADR-0198 (entry snapshot), ADR-0199 (currency roles, shop GUI), ADR-0202 (movement log) and
ADR-0203 (delivery, loaner kit). Replaces the "loadout" idea from ADR-0027 / WP12 for good.

## Context

Three surfaces own an item at some point - the player inventory (hotbar + bag), the personal vault (ender chest)
and the GUI shop - and each had grown its own rules: item lore was hand-written per YAML and drifted from the
numbers, the vault was one 27-slot page, a shop offer could be bought endlessly, the shop capped at 45 offers,
the player could not see what they had bought, the lobby kit (7 hotbar buttons) left no room for weapons, and a
dead `Loadout` model (no writer) lingered with a table and a GUI binding.

## Decision

### 1. One model, three places, one renderer

```
  shop (lobby only) --buy/claim--> player inventory <--move freely--> vault (lobby only, 3 pages)
                                         |
                                  match entry: snapshot (ADR-0198) + loaner kit (ADR-0203);
                                  nothing in a match is kept, bought, dropped or stored
```

| Place | Lobby / training | Match |
|---|---|---|
| Inventory | weapons in hotbar 0..4, locked menu kit in 5..8 | no kit; owned weapons or the loaner kit, all 9 slots |
| Vault | open, 3 pages x 45 slots, sort per page | refused (`BlockedInMatch`) |
| Shop | open, browse / buy / claim / history | refused (`BlockedInMatch`) |

**Item rendering is derived, never hand-written.** `ItemTooltip` (core, `application/service/item`) renders the
lore for a gameplay item from its `ItemSpec`: rarity + level header, the YAML flavor / usage lines, then stat
lines (damage, speed, armor, magazine, ammo / energy cost, ...). It is used by `PaperItemMaterializer` (inventory,
vault, shop-bought stacks) and `toAdapterTemplate` (shop / admin / menu GUI icons). Plain items (panes, tiles,
indicators: no skills, stats or resource) get only their own lore. The hand-written `rarity: x / level: y` lore
lines were removed from `content/items`.

### 2. Ownership: weapons are unlocks, consumables are goods

`ShopOffer.limit` is `once` (default) or `unlimited`. A `once` offer is an unlock: paid once, recorded in the
append-only `shop_purchases` ledger (V64, `IShopPurchaseRepository`), shown as **Owned**. Re-clicking an owned
offer hands out a **free replica** unless the player still carries the item (`AlreadyCarrying`), so a lost or
dropped weapon never costs money twice and a weapon is never a scarce object (weapon "scarcity" lives in
price / level, not in duplicates). `unlimited` offers charge every time. No sell-back: an unlock has nothing to
sell back (see Not done).

The same table is the **player-facing history**: the shop footer has a history button (`shop.history`) listing the
last 45 purchases (item, time, price paid); `shop.open` returns. The currency view stays the admin audit log
(ADR-0202).

### 3. Shop paging and categories

45 offers per page; the footer row holds previous page (0), category cycle (2), wallet (4), history (6) and
next page (8). `ShopOffer.category` is free text (`gun`, `sword`, ...; label `astera.economy.shop.category.<id>`
or `all`). The browse use case remembers each player's page / category so the in-place repaint after a purchase
stays on the page.

### 4. Vault capacity, pages, sorting

`PersonalVault.PAGE_SIZE = 45`, `MAX_PAGES = 3` (capacity 135). Global slot = page x 45 + local slot, so the old 27
slots are page 0 unchanged (no data migration). The window is a 6-row chest: 45 item slots + a locked navigation
row (previous, sort, page indicator, next). Closing saves only the open page (`SaveVaultUseCase.savePage`);
navigating closes (= saves) and reopens next tick. Sort merges stacks and orders by material / name.
Every click, drag, number-key swap and double-click gather that could touch the navigation row is cancelled.

### 5. Hotbar layout policy (`HotbarLayout`)

The lobby kit is compact: **hotbar slots 5..8** (customize/vault, shop, queue, compass). Weapons - starter grant,
shop, training rack, admin give - take 0..4 left to right (Bukkit `addItem` already fills the hotbar from 0, so
delivery needs no special case while the kit stays out of the weapon zone). `LobbyMenuKit` rejects a hotbar entry
below slot 5 at load time. In a match there is no kit. Everything else the old kit offered (profile, settings,
training) is one click away in the compass menu / crafting-grid buttons; the main menu gained a Training tile.
`tools/e2e` expresses the policy (kit in 5..8, starter weapons in 0..4, no lobby-kit item in the match hotbar).

### 6. Dead code removed

`Loadout`, `LoadoutSlot`, `ILoadoutRepository`, the Postgres repo / table object / IT, the `player.loadout.<slot>`
GUI binding and its i18n, the `loadout` NPC naming (now `gear-keeper-npc`), and the starter-grant key
`default_loadout` (now `starter_items`). Migration `V63__drop_loadouts.sql` drops the table (V5 untouched).

## Consequences

- Designers write flavor only; a stat change in `ItemSpec` shows everywhere at once.
- One extra table row per purchase; the ledger is the source of truth for "do I own this".
- Players with items in vault slots 0..26 see them on page 0 exactly as before.
- Kit buttons moved: muscle memory for slot 0 (compass) changes to slot 8.

## Not done / follow-ups

- `PvpDeathRule.DropAll` / `DropEquipped` stay a no-op policy slot: Astera owns death (ADR-0149) and the match
  sandbox restores inventories, so a real drop rule needs a loot mode with entity cleanup - a separate decision.
- Sell-back and upgrade sinks: no economy-doc rule justifies them yet; GEM stays reserved for cosmetics /
  convenience (still no source and no sink defined).
- Per-period purchase limits ("2 per day") and per-player stock for limited offers.
- Quest / advancement currency rewards bypass `CurrencyChanged` and so the movement log (ADR-0202).
- Vault page unlocks as a sink (page 2 / 3 for GEM or STAR); today all 3 pages are open.
- Shop GUI categories use one fixed icon; per-category icons.
