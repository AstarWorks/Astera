# Astera rarity tooltip art

`PaperItemMaterializer` sets `minecraft:tooltip_style=astera:<rarity>` on every
Astera item, including GUI icons and refreshed stacks. Minecraft adds `tooltip/`
when resolving the two sprites. `astera:tooltip/<rarity>` would incorrectly look
under `tooltip/tooltip/`.

Sources: `tools/item-art/sheets/tooltip-v1.yaml` and
`content/textures/sources/sheets/tooltip-v1.png`. The sheet prompt was emitted by
`gen_sheet.py --prompt-only` and generated with the built-in image tool. The
manifest is `content/textures/sources/tooltips.yaml`.

Regenerate only this lane (no item models or other art is rewritten):

```
./gradlew :tools:item-art:run --args='tooltips content build/tooltip-art/contact-sheet.png'
./gradlew :tools:resource-pack:buildResourcePack
bash tools/item-art/scripts/tooltip-shot.sh orrery-staff
```

`TooltipArt` uses the established cell extraction and palette quantisation,
retains corner art, makes each stretched edge uniform, and darkens the generated
interior with alpha 245/255. Both sprites are 64px, nine-sliced with an 8px border.
The normal item-art write/check path also includes these files. The pack builder
already copies PNGs and converts `*.mcmeta.json` to `*.png.mcmeta`.

Colour comes from the shipped `astera.item.tooltip.rarity` MiniMessage strings
in `content/languages/en.yaml`, shared with lore. Current accents:
common #FFFFFF, uncommon #55FF55, rare #55FFFF, epic #FF55FF, legendary #FFAA00.
Regenerate after changing that palette; keep ja/en rarity tags consistent.

Readability: the background's lightest possible composite over white is
#181A1F. Contrast is >= 17:1 for white, >= 7.4:1 for gray #AAAAAA, and >= 5.4:1
for red #FF5555; the rarity colours are brighter. Existing dark_gray #555555
cannot reach 4.5:1 even on pure black; use gray for text the player must read.
The contact sheet includes both layers at 220x100 and 340x150 with sample lore.
The shot helper preserves the normal font (the VFX overlay normally hides ASCII),
uses one client, and tears down its own stack on exit.
