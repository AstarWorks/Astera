# Vanilla materials — pistol

All 82 cuboids retain their original geometry, names, silhouette and display
transforms. No split is needed. Only face materials/UVs and texture references
change; no custom atlas or Mojang PNG is shipped.

| Parts | Catalog material |
|---|---|
| Slide, lower/grip frames, channel recesses, guard, cooling fins, scored grip edges, rear cap | `metal.netherite_block` |
| Bevels, polished edges, pins, trigger, slide serrations, sights, magazine heel, corner steps | `metal.iron_block` |
| Walnut grip insets | `wood.stripped_dark_oak_log` |
| Cyan emitter, side strips, lower diodes | `gem.diamond_block` |

`material.apply` uses seed 20261024 and one vanilla texel per model unit,
including fractional faces (measured density 0.9999999999999902–1.0000000000000029
on all 492 faces). Wood grain follows each inset's long local Y axis;
the corresponding log ends cover the ends. All used references are native block
sprites, and unused item-atlas slots are removed during packaging/re-import.
The intermediate batch's mixed-atlas warning comes from that unused original
slot; the installed model validates without an atlas warning.

Opened and reviewed `before.png`, `after.png` (same eight cameras), `contexts.png`
(Forge), `player-contexts.jpg` (perspective both hands, player rest pose, ground,
frame and GUI), `angles.jpg` (26 directions, `cameras.json`), and `inventory.png`
(16/32/64px enlarged with nearest-neighbor sampling). The first material pass
made the fine serrations and sights too dark; assigning those pieces, corner
steps and the magazine heel to iron restores the original bright outline.
Dark scored edges separate the wood grip bands. Both hands leave screen center
clear. At 16px small pins disappear while the grip, bright rails and cyan line
remain readable. Top and underside retain the native netherite's coarse pattern;
no stretched texels or newly introduced gaps were visible. No UV override needed.

Independent real-asset comparison confirmed identical non-face element fields
and display transforms. Forge validation: zero errors, 97 overlap-AABB warnings,
20 possible coplanar contacts, one disconnected-component report, identical to
the original. These retained contacts are not a complete z-fighting audit.

Reproduce from the repository root with fresh output directories:

```sh
git show HEAD^:content/models/items/pistol.model.json > /tmp/pistol-before.json
python3 tools/model-forge/batches/vanilla-material/pistol/remake.py \
  --project build/pistol-reproduction --before /tmp/pistol-before.json
python3 tools/model-forge/batches/vanilla-material/pistol/capture-pack.py \
  --out build/pistol-capture-pack
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/pistol/capture-client.py
```

The client helper uses the installed pilot runtime and a unique Xvfb display;
its game directory is `build/pistol-client`. Enable `file/vanilla-material`,
create an isolated creative superflat with commands, and give
`minecraft:iron_hoe[minecraft:item_model="astera:pistol"]`. Use xdotool on the
reported `display`, then create `capture` and `stop` files in that game directory.
The helper holds the shared lock until native capture and shutdown. Its minimal
pack contains the exact model, item definition and builder-equivalent blocks
atlas directory source, without vanilla PNGs.

`client.png` was opened and reviewed: raw 1280×720 Xvfb root screenshot,
Minecraft Java 26.3, portablemc and llvmpipe, isolated creative superflat, peaceful,
noon/clear, FOV 70, view bob off, GUI scale 2, yaw/pitch 0/0. Actual display was
`:0`, dynamically allocated with `-displayfd`. The native model shows dark metal,
bright iron edges and serrations, cyan strip and wood grip without missing faces
or stretched textures. Screen center stays clear. As in the unchanged original
transform, the lower grip continues below the native first-person screen.
No missing-model/texture, mixed-atlas or duplicate-sprite errors were found in the
client log. Offline Realms authentication errors are unrelated to the pack.
This capture checks the exact authored model in a minimal pack, not a deployed
Astera server or production pack ZIP. Other FOVs, use/swing and shader packs remain
unverified.

Catalog wishes: none required. Native diamond is adequate for the cyan hardware;
it is ordinary lit material, without emissive/shader behavior. Existing bevel
contacts and tiny gaps are retained. Production pack/deployment and integration
checks remain manager/CI work. No Gradle, tests, E2E, Docker, external server or
push was run.
