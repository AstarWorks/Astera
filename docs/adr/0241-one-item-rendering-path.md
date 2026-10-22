---
status: Accepted
date: 2026-10-23
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0241: One item-rendering path (blueprint + materializer) for stacks and GUI icons

## Status

Accepted

## Context

Founder bug: the admin item list showed items without their generated art, and name / lore / meta differed
slightly from the item actually received. Cause: two builders. `PaperItemMaterializer` (items plugin) built the
real stack (`item_model`, `use_cooldown` group, glint, hidden flags, attributes, PDC `item_id`), while core's
`PaperItemTemplateRenderer` built GUI icons from `ItemSpec.toAdapterTemplate` -> `IMcItemTemplate` (material, name,
lore, custom model data only) and `PaperPlayer.giveItem` was a third copy. Core cannot depend on the items plugin,
which is why the copy existed.

## Decision

1. `ItemBlueprintFactory` (core, application) is the only `ItemSpec` -> "what the stack carries" function. It
   returns an `ItemBlueprint` (material, `item_model`, name, lore via `ItemTooltip`, glint, flags inputs,
   cooldown group, attributes, PDC values). `ItemModelCatalog` moved to core with it.
2. `PaperItemMaterializer` moved to core (`adapter/paper/item`) and applies a blueprint to a Bukkit stack. The items
   plugin constructs it (so PDC keys keep the items-plugin namespace) and registers it in the `ServiceRegistry`.
3. `PaperItemTemplateRenderer` (admin catalog, shop, vault-adjacent menus, lobby kit, main menu, skill tester icons)
   and the hologram item display take the base stack from `PaperItemMaterializer.buildFor`; they only append GUI
   extras (price / stock / hint lore, reactive overrides, head skins).
4. Deleted: `IMcItemTemplate`, `ItemSpec.toAdapterTemplate`, `IMcPlayer.giveItem` / `PaperPlayer.giveItem`,
   `IMcInventory`, the renderer's `buildVanilla`, the hologram's material-only lookup. No shims.
5. Lobby kit icons now carry the real `item_id` PDC; `PaperItemIdResolver.inventoryItemIds` skips `MenuKitTag` stacks
   so they are not counted as owned items.

## Consequences

- Icon == item by construction; `ItemIconParityScenarioTest` asserts it headlessly on the blueprint.
- `gui-tile-admin` and `gui-tile-item-catalog` (ADR-0240) still have no generated art: `tools/item-art` has no
  design for them (add an `Icons.SPECS` entry and run `:tools:item-art:run`). The test pins this list.
