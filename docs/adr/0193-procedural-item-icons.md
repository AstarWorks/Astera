---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0193: Procedural item icons and the `item_model` component

## Status

Accepted. Builds on [[adr/0182-resource-pack-pipeline]] (the pack builder) and respects
[[adr/0010-postpone-generative-ai-to-phase-6]].

## Context

Founder: "I want custom textures for items like the fire staff, just auto-generate them." Weapons showed
as their vanilla base material (a blaze rod for the fire staff). Studio-authored art
(`content/models/**`, `content/textures/items/swords`) exists for a few items and is edited by hand, so
generated assets must neither overwrite it nor be overwritten by regeneration.

## Decision

1. **`:tools:item-art` draws the icons from code** (no generative AI, ADR-0010): 32x32 (the Studio
   size), a part-based painter with top-left bevel shading, seeded dither, outline and glow. Archetypes:
   gun silhouettes (pistol / rifle / shotgun), blades (grip, guard, fuller), staffs with an element head.
   Palettes per element: fire red/orange/yellow with ember glow, frost cyan/white, thunder violet/yellow,
   verdant greens, steel greys. The item id seeds the variation, so output is deterministic.
2. **Weapons = items that bind at least one skill.** The generator reads `content/items/*.yaml`; a design
   is hand-picked per id, with a fallback by base material (`*_SWORD`, `*_HORSE_ARMOR`, `*_ROD`) and id
   keywords so a new weapon never breaks the build.
3. **Separate namespace.** Output goes to `content/textures/generated/items/<id>.png` and
   `content/models/generated/items/<id>.model.json` (`item/handheld` for blades and staffs,
   `item/generated` for guns). Existing Studio files are never touched. `./gradlew :tools:item-art:run`
   regenerates them and `docs/assets/item-art-preview.png`; `checkItemArt` (part of `check`) fails on drift.
4. **Pack item definitions.** The pack builder writes `assets/astera/items/<id>.json` for every generated
   model. A Studio model under `models/items/**` whose file name equals the item id overrides the generated
   one: the definition then points at the Studio model.
5. **Paper side.** `PaperItemMaterializer` sets `item_model` = `astera:<id>` for every item listed by
   `ItemModelCatalog` (the generated model files in the content dir). The base material stays.

## Consequences

- A client that has not loaded the pack renders items whose `item_model` has no definition as the missing
  model; Astera servers already require the pack (ADR-0182), so this is accepted.
- Items bound to no skill (GUI tiles) get no generated art; extend `ItemArt.weapons` when that changes.
- The materializer wiring itself is not unit-testable without a Bukkit server (no MockBukkit in the repo);
  the catalog is covered by tests and the effect is checked live.
