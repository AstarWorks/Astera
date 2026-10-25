---
status: Accepted
date: 2026-10-26
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0261: Weapon TTK bands, reachable flagships, and a demo flag instead of a demo folder

## Status

Accepted. Applies [[adr/0073-item-centric-content-model-and-skill-binding]] (item-owned bindings and cooldown overrides).

## Context

1. The audit (modes-combat, MODE-03/08/09) found no systematic time-to-kill pass for the 20-HP player, ten flagship
   (Limbus + orrery) weapons at level 4-5 although the shop already sells them, and demo content (`rtm-blaster`,
   `simple-shoot`, `vfx-demo-*`, `mechanics-demo-quake`, `anim-demo-*`, `skill-tester`) mixed into the production
   catalogs, kits and the training rack.
2. `content/items` and `content/skills` are loaded flat by many tests (game-sim, e2e, `Shipped*`); moving files into a
   subfolder would silently drop them from those runs.

## Decision

1. **TTK bands per archetype**, computed from content (`scripts/ttk-table.py`) and enforced by
   `ShippedWeaponTtkBandsTest`: basic gun 1.0-3.0 s, basic sword 1.0-3.5 s, basic staff 3.0-5.0 s, flagship s1 3.0-6.0 s,
   nominal DPS <= 10.5, flagship s1 DPS <= the lowest basic gun/sword DPS. Outliers are fixed through the item
   binding's `cooldown_override_ticks`, not by editing Limbus skill damage. Table and intent: `docs/game-design/weapons/ttk.md`.
2. **Flagship level requirements** are capped at 4 (four of them at 3), consistent with the XP curve and 400-600 G shop prices.
3. **Demo content is flagged, not moved.** Items carry `pdc_tags: astera:demo: "true"`; skills carry the `demo` tag.
   `AdminItemCatalog.isCatalogItem` (admin catalog, full kit, training rack) and `AdminSkillCatalog.entries` skip them.
   Id-based paths (`items give`, `skill cast`, `skill list`, tests, e2e) are unchanged.

## Consequences

- A new demo item/skill is hidden by adding the flag; `ShippedWeaponTtkBandsTest` pins the exact demo item set so an
  unflagged demo (or a flagged production weapon) fails the build.
- `level_requirement` is still display-only; real gating stays with GOLD prices (economy track).
