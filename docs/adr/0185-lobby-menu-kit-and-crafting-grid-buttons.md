---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0185: Lobby menu kit (hotbar) and crafting-grid menu buttons

## Status

Accepted. Revives the intent of the compass listeners deleted as dead code in `ad6a149`
(ADR-0119: "right-click the menu compass -> main menu") with a mechanism that actually works.

## Context

Founder: "make the compass and the main menu reachable from the crafting screen". Until now the
main menu was only reachable through `/astera menu`; the compass item existed but nothing bound it
to a click (the old design waited on a skill-cast event the compass never produced).

## Decision

### Content-defined kit

`content/gui/kit/lobby-kit.yaml` (the `kit/` sub-directory keeps it out of the non-recursive GUI
spec loader) lists entries `{id, area, slot, item, intent, payload, lore_key}`:

- `hotbar` (slots 0..8): right-click dispatches the intent.
- `crafting_grid` (0..3, row-major) and `crafting_result` (0): buttons in the player's own
  inventory screen; click closes the screen and dispatches the intent.

Icons and names come from `content/items/` (inline en/ja), hints from
`astera.lobby-kit.hint.*`. Every action is a `GuiIntent` routed by `IGuiIntentRouter`
(`lobby.queue.show`, `lobby.shop.open`, `lobby.profile.show`, `lobby.settings.show`,
`lobby.customize.open`) plus the new built-in `gui.open {gui: <id>}` that opens a GUI spec
(`main-menu` falls back to the built-in spec, like `/astera menu`). Shipped layout: compass (main
menu), queue, shop, customize in hotbar 0-3, profile 7, settings 8; grid = menu / profile / shop /
settings, result slot = queue.

### When the kit exists

`LobbyMenuKitPolicy.active(role, inMatch)`: `serverRole.hostsLobby && !inMatch`. `inMatch` is
`MatchInventorySnapshotListener.isHeld` (the per-match entry snapshot, WP12). The kit is refreshed
(strip, then give) on join, respawn, world change, game-mode change, inventory close, snapshot
restore, and on every `MatchStarted` / `MatchEnded` (an `ALL` backend swaps kit and match inventory).
A `GAME` backend never loads or shows it.

### The crafting grid is server state

A player's own inventory is a `CRAFTING` view even while no screen is open, so the buttons are
written into `player.openInventory.topInventory` and stay there; no open event is needed (there is
none for the own inventory). Vanilla writes the result slot on every matrix change, so
`PrepareItemCraftEvent` pins the result to the Play button while buttons are in the grid. Vanilla
returns the grid to the inventory on close, so the grid is cleared inside `InventoryCloseEvent`
(before vanilla's close) and re-written next tick.

### Exploit-proofing

Every kit stack carries PDC `astera:menu_kit = <entry id>` (max stack 1). Locks (decisions in the
pure `LobbyKitLockRules`, wired in `PaperLobbyMenuKit`):

- click / creative click: cancel when the clicked stack, the cursor, the number-key hotbar item or
  the offhand-swap item is a kit item; cancel everything in the crafting zone while buttons are
  shown; cancel double-click gather on the own crafting screen. Shift-click is covered because the
  clicked stack is a kit item.
- drag: cancel when carrying a kit item, spreading over the shown grid, or landing on a kit slot.
- `CraftItemEvent` cancelled while the matrix holds a kit item; `PlayerDropItemEvent`,
  `PlayerSwapHandItemsEvent`, `BlockPlaceEvent`, `PlayerInteractEntityEvent` cancelled for kit items.
- death: kit stacks are removed from drops / `itemsToKeep` and the grid is cleared.
- quit (LOWEST) and plugin disable strip the kit so the vanilla playerdata never stores it; a crash
  leaves stale kit stacks only until the next refresh, which strips before giving (no duplicates).
- `PaperInventorySnapshotSerializer` encodes kit stacks as empty and ignores them on restore, so the
  entry snapshot, the quit snapshot and the vault never contain the kit; after a restore it calls
  `onRestored`, which re-applies the kit (returning from a match).

## Consequences

- Label language is fixed at apply time; a locale change shows after the next refresh (close the
  inventory or rejoin).
- `content/gui/kit/lobby-kit.yaml` is read at boot only (no `/astera reload` support yet).
- Items displaced by a hotbar kit slot move to the first free inventory slot (dropped if full).
- Only the 2x2 grid of the player's own inventory is used; workbench views are untouched.
