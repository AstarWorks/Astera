---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0199: Procedural GUI / kit / skill-kind icons and pack validation

## Status

Accepted. Extends [[adr/0193-procedural-item-icons]] (weapon art) under
[[adr/0010-postpone-generative-ai-to-phase-6]]; pack shape per [[adr/0182-resource-pack-pipeline]].

## Context

ADR-0193 only drew items that bind a skill. Every other player-visible item (lobby kit, main-menu
tiles, admin panel, skill-kind icons, the skill tester, indicators) still showed its vanilla base
material (an emerald for the shop, TNT for area skills, a repeater for settings).

## Decision

1. **`:tools:item-art` also draws icons** for the non-weapon items listed in `Icons.SPECS`
   (id -> glyph + theme). Style: bevelled 32x32 plaque (theme border, flat fill) with a glyph on it.
   Themes carry meaning: gold = player-facing tiles, blue = admin, violet/orange = skills,
   red = destructive, green = confirm/health. Glyphs are polygon/disc compositions (no image input,
   no generative AI), the item id seeds the dither, output is deterministic.
2. **Exhaustive by test.** Every item under `content/items` is a weapon, has an icon design, or is in
   `Icons.VANILLA` (filler panes, player heads that render a skin, the empty spacer). A new item that
   is none of these fails `ItemArtTest`, so nothing silently stays vanilla.
3. Output uses the ADR-0193 namespace (`content/{textures,models}/generated/items/<id>`), model parent
   `item/generated`; `ItemModelCatalog` and the pack builder pick them up unchanged. Contact sheets:
   `docs/assets/item-art-preview.png` (weapons) and `docs/assets/item-icons-preview.png` (icons).
4. **Weapon art**: an optional enchant halo and a glint on blades, a muzzle/energy-cell glow on guns,
   `rtm-blaster` as a rifle-class gun with a magenta cell (previously the fallback arcane staff).
5. **Pack validation** (`PackValidationTest`): every `astera:` texture and parent a packed model
   references is in the pack, every generated model has an item definition, every definition points
   at a packed model, no file exceeds its size limit (PNG 256 KB, OGG 2 MB, other 512 KB; pack under
   32 MB uncompressed), icons are square and at most 64 px.

## Consequences

- Per-skill testers (`skill-tester-<skill>`, ADR-0196) are templated at runtime and have no catalog
  entry; they keep the vanilla look unless the materializer maps them to `skill-tester`.
- Studio-authored models are not packed yet: `content/models/manifest.json` lists models whose
  `.model.json` is not in the repo, so no mapping to wire exists until Studio exports them.
- `content/textures/items/swords/*_v01.png` are unwired proof-of-concept images (see its README).
