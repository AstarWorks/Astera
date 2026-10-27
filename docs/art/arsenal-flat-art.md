# Arsenal flat art — 2026-10-28

Founder direction supersedes the volumetric default in `docs/art/3d-model-pipeline.md` for this arsenal: generated flat handheld tools/weapons, generated currency icons, native vanilla cube blocks. No new pixels, texture overrides, dependencies or placed-block state hijacks.

## Item model mapping

Each row selects `astera:generated/items/<id>` from `content/models/generated/items/<id>.model.json`, textured by the unchanged `content/textures/generated/items/<id>.png`.

| Item id | Parent | Source sheet |
| --- | --- | --- |
| `stone-pickaxe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `stone-axe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `stone-shovel` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `iron-pickaxe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `iron-axe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `iron-shovel` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `diamond-pickaxe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `diamond-axe` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `diamond-shovel` | `minecraft:item/handheld` | `arsenal-tools-v1` |
| `ore-iron` | `minecraft:item/generated` | `arsenal-tools-v1` |
| `ore-gold` | `minecraft:item/generated` | `arsenal-tools-v1` |
| `ore-diamond` | `minecraft:item/generated` | `arsenal-tools-v1` |
| `ore-emerald` | `minecraft:item/generated` | `arsenal-tools-v1` |
| `iron-blade` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `war-axe` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `spear` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `mace` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `twin-daggers` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `katana` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `halberd` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `rapier` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `cleaver` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `scythe` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `club` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `revolver` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `smg` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `sniper` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `harpoon` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `grappling-hook` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `throwing-knives` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `sticky-bomb` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `venom-flask` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `smoke-bomb` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `delay-mine` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `frag-grenade` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `flash-bang` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `shock-wand` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `ember-rod` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `frost-rod` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `fireball-charge` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `glacier-wand` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `sanctuary-staff` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `chain-rod` | `minecraft:item/handheld` | `arsenal-weapons-art2` |
| `gale-fan` | `minecraft:item/handheld` | `arsenal-weapons-art2` |

The sheets are `content/textures/sources/sheets/arsenal-tools-v1.png` and `content/textures/sources/sheets/arsenal-weapons-art2.png`; generation specs remain under `tools/item-art/sheets/`. `items.yaml` explicitly registers handheld parents. All 44 icon PNGs are pixel-identical to the base commit.

## Block → placed vanilla material / model

These seven items have no custom `item_model` component: Minecraft resolves their native item definitions to the following block model references. Removing the generated model also removes the catalog entry; removing the authored illustration prevents pack fallback from selecting it. This preserves native inventory, hand and dropped-block transforms without redundant wrapper JSONs.

| Block item | Placed vanilla material | Model reference |
| --- | --- | --- |
| `reinforced-planks-block` | `DARK_OAK_PLANKS` | `minecraft:block/dark_oak_planks` |
| `stone-bulwark-block` | `STONE_BRICKS` | `minecraft:block/stone_bricks` |
| `obsidian-plate-block` | `OBSIDIAN` | `minecraft:block/obsidian` |
| `packed-clay-block` | `CLAY` | `minecraft:block/clay` |
| `tempered-glass-block` | `GLASS` | `minecraft:block/glass` |
| `hardened-soil-block` | `ROOTED_DIRT` | `minecraft:block/rooted_dirt` |
| `wool-block` | `WHITE_WOOL` | `minecraft:block/white_wool` |

Five custom covers declare `placed_from` in `content/blocks/`. `CustomBlockYamlConfig.toSpec()` carries `base_bukkit_material` into `CustomBlockSpec`; `PaperBlockStateGateway.place()` assigns that material directly to the world block. Hardened soil has no `placed_from` binding; its item material and custom-cover material both use ROOTED_DIRT, so ordinary placement agrees. Wool follows ordinary WHITE_WOOL placement.

ROOTED_DIRT is visibly root-speckled rather than compacted soil. If the founder wants a closer semantic match, propose PACKED_MUD for hardened soil (it already represents sandbags, so registry material collisions need review). Current art intentionally follows the shipped ROOTED_DIRT, without changing placement. The other six materials are reasonable matches. Bows, crossbows and shield-bulwark retain native use-state rendering.

## Review and verification

- Before: base `836d56c81`; inspected all native 16/32 px GUI previews and all context pages.
- After: inspected all 51 rows in `/tmp/claude-1000/codex/arsenal-flat-art/contact.png` (split into `contact-1.png` … `contact-5.png`), with 16/32 px GUI, third-person and first-person right hand, plus ground. Native cube shapes and generated silhouettes confirmed. Offline context lighting and generic first-person framing are approximate; sprite extrusion exists only in preview data.
- Weak spots: dark stone tools / chain-rod lose small details at 16 px on dark backgrounds; transparent glass is intentionally subtle. No regeneration needed to correct the rejected model shape.
- Real 26.3 client: inspected `client-tools-first.png`, `client-tools-third.png`, `client-blocks-first.png`, `client-blocks-third.png`, `client-weapons-first.png`, `client-weapons-third.png` under the same evidence directory. All three hotbars and held art resolve; native blocks are isometric cubes. Captured against a disposable, plugin-free local Paper process; no live containers touched. Pack SHA-1: `87d2c94e2afd50e4e4fe543430d37b326b3ece8d`. No missing model/texture warnings for the changed items; existing unused `items/swords/amethyst_luxury_longsword` and `items/bows/test_simple_bow_v24` warn about unresolved particle textures.
- Compile-only: `:tools:e2e:compileTestKotlin` passed. `:tools:resource-pack:buildResourcePack` passed; inspected its ZIP references for all 51 changed items. E2e expectations updated in `ArsenalArtE2eTest` and `ArsenalWeaponArtE2eTest`; no tests executed.
- Live refresh caveat: `PaperItemMaterializer.refreshVisuals()` only assigns `itemModel` when the new stack has one. It does not clear the previous component after a model is removed, despite its documented “re-apply models” contract. Existing block stacks therefore need re-granting, or the gameplay owner must clear a removed item model during refresh. Freshly granted blocks are covered by E2e; this track does not edit core gameplay.
- Generator blocker in the base: `astera.item.tooltip` is defined twice in both language files. YAML keeps the later uncoloured rarity labels, while `TooltipArt.colours()` requires colours. Original `:tools:item-art:run` fails with “rarity common needs a lore colour”. Ran the same pipeline against a scratch content copy with only those labels coloured, copied the 44 requested model outputs, and verified all 44 icon pixel arrays unchanged. The owner must reconcile the language/template contract before normal regeneration succeeds.

## Documentation discrepancies observed

- `3d-model-pipeline.md` calls the deliverable volumetric; the founder's 2026-10-28 task explicitly supersedes that default for these items.
- The same document describes one custom atlas per hero prop; current arsenal models being removed referenced vanilla materials instead. No custom atlas is introduced here.
- `.claude/rules/coding-posture.md` describes Kotlin 2.2; the actual resolved toolchain uses Kotlin 2.3.21. Harness files were left to their owner.
- The duplicate language tooltip definition conflicts with the colour-bearing values expected by the generator, as above.
- `PaperItemMaterializer.refreshVisuals()` promises to re-apply models, but retains an obsolete component when the current blueprint has none (see refresh caveat).
