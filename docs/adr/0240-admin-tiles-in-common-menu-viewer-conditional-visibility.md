---
status: Accepted
date: 2026-10-23
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0240: Admin tiles in the common menu (per-viewer conditional visibility)

## Status

Accepted

## Context

The admin hub (ADR-0183) and its item catalog page are reachable by `/astera admin panel` and by sneak+F
(ADR-0233), but the main menu and the lobby kit had no entry for them (the gap noted in ADR-0233). The founder
asked: "How do I open the admin item-list GUI? For admins, make it a special entry in the common menu."
GUI specs and the lobby kit were viewer-independent, so a tile for ops only needed a visibility mechanism.

## Decision

1. **`visible_if` condition** on `GuiSlot.Static` (`content/gui/*.yaml`) and on lobby-kit entries
   (`lobby-kit.yaml`). Grammar `permission:<node>` only; anything else is rejected at load time, and an
   unrecognised condition evaluates to hidden (fail closed). Domain helper: `GuiVisibility`.
2. **Who sees it:** the viewer holds the node or is a server operator (`ViewerVisibility`, the same rule as
   `AdminPanelGuard.canUse`).
3. **Where it is applied:** `ViewerFilteringGuiAdapter` decorates `IGuiAdapter`, so every `open`/`update`
   (menus, `/astera menu`, back buttons, any role) strips hidden slots before rendering; the delegate's
   current spec, used to resolve clicks, is exactly what is on screen. The lobby kit resolves per viewer via
   `LobbyMenuKit.forViewer` in `PaperLobbyMenuKit` (give, press, craft-result, keybinds).
4. **Tiles:** `main-menu.yaml` slot 0 "Admin Panel" (`admin.hub.open`) and slot 8 "Item Catalog"
   (`admin.items.open {category: all}`), both `permission:astera.admin.panel`. In the full 2x2 crafting grid
   a visible conditional entry **replaces** the unconditional entry at the same slot: ops get Admin and Item
   Catalog in place of Shop and Settings (both still reachable from the main menu / customize menu).
5. **Compass lore:** `extra_lore` entries with their own `visible_if`; ops see "Sneak+F: admin panel".
6. The `admin.*` handlers still re-check the permission on every click (ADR-0183); visibility is
   convenience, not authorization.

## Consequences

- Kit refresh (join, respawn, inventory close, `reload lobby-kit`) picks up permission changes; a stale
  admin button pressed after losing the permission is ignored and the kit is rebuilt.
- The lobby kit still follows ADR-0185/0232 (lobby-hosting backends, never in a match); the main-menu tiles
  work on every role because they are applied in the GUI layer.
- Verified by `AdminMenuTilesScenarioTest` (game-sim): admin and op see and use the tiles, a normal player
  does not and cannot open the catalog; kit swap and compass hint per viewer.
