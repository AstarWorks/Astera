# Armor art v1

Generated with the built-in Codex image tool using the prompts emitted by:

- `python3 tools/item-art/scripts/gen_sheet.py tools/item-art/sheets/armor-v1.yaml --prompt-only`
- `python3 tools/item-art/scripts/gen_sheet.py tools/item-art/sheets/armor-surfaces-v1.yaml --prompt-only`

The original generated PNGs are committed in `content/textures/sources/sheets/`.
No downloaded art or Mojang PNGs are used. The local client jar is read only for
inventory-parent display transforms during offline preview.

`./gradlew :tools:item-art:run` converts the sixteen inventory cells through the
existing source manifest. The worn layers are NOT generated: the eight files
`content/textures/equipment/{humanoid,humanoid_leggings}/<set>.png` are
hand-authored from the concept (round 2, `88036829d`) and committed as assets;
the item-art run never writes them. `armor-surfaces-v1` is concept reference only.
Outer boots are transparent above texel 7 so leggings remain visible. Paper retains
equip_slot and armor_piece; equipment_asset only chooses the worn art.

## Visual rounds

1. Reviewed every source icon, surface tile, and front/back/side/oblique worn
   render. Area averaging blurred black visors and mixed cyan/gold into muddy
   green. Fix: nearest sampling and a shared 24-color palette per layer.
2. Reviewed the same cameras after that change. All sixteen unchanged icons
   were rendered and checked again in the shared GUI/hand contexts; no icon
   silhouette or palette correction was needed. Visors, crossed scout straps,
   dark chain, silver plate, and cyan/gold ornament now remain distinct. A small
   walking pose (+/-3 degrees at the limbs, same vanilla cube dimensions and
   dilations) also exposes the boot/leggings boundary. No model geometry or
   depth bias is used to conceal overlaps.

Evidence:

- `/tmp/claude-1000/codex/armor-round1/armor-contact-sheet.png`
- `/tmp/claude-1000/codex/armor-round2/armor-contact-sheet.png`
- `/tmp/claude-1000/codex/armor-final/armor-contact-sheet.png`
- `/tmp/claude-1000/codex/armor-contexts-round1/armor-held-contact-sheet.png`
- `/tmp/claude-1000/codex/armor-contexts-round2/armor-held-contact-sheet.png`

Reproduce worn views with `python3 tools/item-art/scripts/equipment_preview.py
--out <directory>`. It reuses Model Forge's rasterizer with Java cube UVs and
outer dilation 1 / leggings .5, including mirrored left limbs.

The generated-item context adapter reuses `tools/model-forge/context-preview.mjs`
with alpha-extruded sprites and display transforms read from an installed client
jar. Its first-person right-hand view is nearly edge-on for vanilla generated
parents; this is an unresolved limitation of the offline context renderer, not
native-client evidence. Do not tune the shipped vanilla parent to hide it.

`tools/vfx-shot` exists, but its capture entry point casts a registered skill and
restages/clears the caster's equipment; it has no armor-set staging hook. A native
armor capture needs that harness extension. Worn evidence here establishes UV
mapping and shape/color separation, not client pack acceptance or animation.

Manager verification:

- `ArmorArtE2eTest`: all sixteen real materialized items retain their item art,
  armor_piece and correct equippable asset_id/slot after wearing and refresh.
- `ArmorSetScenarioTest`: four sets retain the asset through the blueprint and
  still reduce damage through the real ItemArmorEquipmentProvider wiring.
- `ShippedArmorPackTest`: shipped YAMLs assemble into four equipment definitions,
  eight distinct 64x32 worn layers and sixteen inventory definitions.

The task brief names plugin/items as the implementation site; the actual shared
DTO, domain item, blueprint and Paper materializer live in core/feature-gameplay.
The implementation follows that existing shared route (no duplicate materializer).

## Committed asset map

| Item id | Inventory texture | Inventory model |
|---|---|---|
| leather-scout-helmet | `content/textures/generated/items/leather-scout-helmet.png` | `content/models/generated/items/leather-scout-helmet.model.json` |
| leather-scout-chestplate | `content/textures/generated/items/leather-scout-chestplate.png` | `content/models/generated/items/leather-scout-chestplate.model.json` |
| leather-scout-leggings | `content/textures/generated/items/leather-scout-leggings.png` | `content/models/generated/items/leather-scout-leggings.model.json` |
| leather-scout-boots | `content/textures/generated/items/leather-scout-boots.png` | `content/models/generated/items/leather-scout-boots.model.json` |
| chain-guard-helmet | `content/textures/generated/items/chain-guard-helmet.png` | `content/models/generated/items/chain-guard-helmet.model.json` |
| chain-guard-chestplate | `content/textures/generated/items/chain-guard-chestplate.png` | `content/models/generated/items/chain-guard-chestplate.model.json` |
| chain-guard-leggings | `content/textures/generated/items/chain-guard-leggings.png` | `content/models/generated/items/chain-guard-leggings.model.json` |
| chain-guard-boots | `content/textures/generated/items/chain-guard-boots.png` | `content/models/generated/items/chain-guard-boots.model.json` |
| iron-warden-helmet | `content/textures/generated/items/iron-warden-helmet.png` | `content/models/generated/items/iron-warden-helmet.model.json` |
| iron-warden-chestplate | `content/textures/generated/items/iron-warden-chestplate.png` | `content/models/generated/items/iron-warden-chestplate.model.json` |
| iron-warden-leggings | `content/textures/generated/items/iron-warden-leggings.png` | `content/models/generated/items/iron-warden-leggings.model.json` |
| iron-warden-boots | `content/textures/generated/items/iron-warden-boots.png` | `content/models/generated/items/iron-warden-boots.model.json` |
| diamond-champion-helmet | `content/textures/generated/items/diamond-champion-helmet.png` | `content/models/generated/items/diamond-champion-helmet.model.json` |
| diamond-champion-chestplate | `content/textures/generated/items/diamond-champion-chestplate.png` | `content/models/generated/items/diamond-champion-chestplate.model.json` |
| diamond-champion-leggings | `content/textures/generated/items/diamond-champion-leggings.png` | `content/models/generated/items/diamond-champion-leggings.model.json` |
| diamond-champion-boots | `content/textures/generated/items/diamond-champion-boots.png` | `content/models/generated/items/diamond-champion-boots.model.json` |

For each set (`leather-scout`, `chain-guard`, `iron-warden`, `diamond-champion`),
the worn images are `content/textures/equipment/humanoid/<set>.png` and
`content/textures/equipment/humanoid_leggings/<set>.png`. The pack emits
`assets/astera/equipment/<set>.json` and references both textures under
`assets/astera/textures/entity/equipment/`.
