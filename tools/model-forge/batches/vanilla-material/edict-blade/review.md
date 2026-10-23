# Vanilla materials — edict-blade

The existing model is two white gloves, brass cuffs and five golden chain links,
not a metal blade despite its item ID. Preserve that authored design: all 15
cuboids, rotations, face coverage and seven display transforms are unchanged.
No geometry splitting or custom PNG is needed.

| Parts | Material | Reason |
|---|---|---|
| white-glove-0/1, six finger cuboids | cloth.white_wool | Soft white glove fabric; neutral, low contrast grain. |
| brass-cuff-0/1, chain-0 through chain-4 | metal.gold_block | Warm polished metal, closest catalog material to the original brass. |

All material faces use one vanilla texel per local model unit, including fractional
finger/chain faces. Seed 20261023 chooses deterministic crops. Native block references
only; no Mojang PNG, copied vanilla atlas source, custom painted texture or shared
Forge implementation change.

## Review and correction

Opened before.png, after.png, contexts.png, contexts-player.png, inventory.png and
sweep.png. Eight Forge directions plus a 26-direction perspective sweep retain the
outline, cuff overhang and finger steps. GUI at 16/32/64px keeps the white/gold pair
recognizable; the thin chain becomes subpixel at the smallest size.

The initial material pass exposed the original coplanar fingertip/glove faces as
speckled patches. The second Forge batch aligns the up/north UVs on e0003–e0005 and
e0008–e0010 to their underlying gloves e0001/e0006 at the same density. Reopened the
same cameras: the patches disappear, including top/back views, without geometry or
silhouette edits. No new seams or stretched texels are visible. This makes coincident
faces sample the same material; it does not remove the pre-existing overlapping faces.

Forge validation: zero errors, 14 overlap warnings and one disconnected-components
warning, identical before/after. The latter is the original two partly separated chain
assemblies, not a newly disconnected part. No geometry cleanup is authorized here.

contexts.png uses Forge's approximate hands. contexts-player.png adds perspective,
both Java hand signs, third-person rest pose, side view, two ground spins, fixed and GUI.
The inherited first-person transforms put this small model at/below the lower viewport
edge in that illustrative preview. Display transforms are explicitly preserved by this
task; native evidence and its limits are recorded below.

## Reproduction

Save the original model from the parent commit, then use a fresh project:

```sh
git show <parent>:content/models/items/edict-blade.model.json > build/edict-before.json
python3 tools/model-forge/batches/vanilla-material/edict-blade/remake.py \
  --project build/edict-remake --before build/edict-before.json
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/context_preview.py --out build/edict-contexts edict-blade
python3 tools/model-forge/context_preview.py --viewer --out build/edict-viewer edict-blade
bun tools/model-forge/review.mjs build/edict-viewer/viewer.html build/edict-sweep edict-blade
python3 tools/model-forge/batches/vanilla-material/edict-blade/capture-pack.py \
  --out build/edict-blade-client/resourcepacks/vanilla-material
```

The viewer embeds Mojang pixels for local inspection only and stays in ignored build/.
The capture pack installs the exact authored model, its item definition and the same
custom blocks-directory atlas source as the pilot/builder; no vanilla PNGs are copied.
Use the pilot's installed portablemc 26.3 runtime, a fresh game directory containing
edict-blade, Xvfb -displayfd (1280×720), software GL with LP_NUM_THREADS=4, FOV 70,
GUI scale 2, view bob off and file/vanilla-material enabled. Hold the entire client
launch and capture under flock /tmp/claude-1000/astera-mc-client.lock.

## Native client review

Opened client.png and client-detail.png, both raw 1280×720 Xvfb root captures
from the actual 26.3 client (portablemc, llvmpipe). client.png keeps the authored
first-person pose: only the glove upper surfaces appear at the bottom-right edge.
This confirms the inherited placement limitation seen in the perspective preview;
it is not a missing-model cube. The hotbar shows the complete white/gold pair.

client-detail.png shows the same exact authored model in a native item_display
entity using its GUI display context, with entity scale 3 and translation [0,0.4,0]
for inspection. This supplemental world presentation does not edit the model or
its display transforms. White fabric and warm metal are clear, with no missing
textures, visible UV speckles or mixed-atlas error. The client log has no missing
texture, multiple-atlas, model-bake or duplicate-sprite warning. Offline-account
Realms authentication errors are unrelated to this local singleplayer capture.
The client was stopped and the lock released after capturing both images.

In the isolated creative superflat, issue these commands (FOV 70, GUI scale 2):

```mcfunction
/give @s minecraft:iron_sword[minecraft:item_model="astera:edict-blade"]
/time set noon
/summon minecraft:item_display ~ ~1 ~3 {item:{id:"minecraft:iron_sword",count:1,components:{"minecraft:item_model":"astera:edict-blade"}},item_display:"gui",billboard:"center",transformation:{translation:[0f,0.4f,0f],left_rotation:[0f,0f,0f,1f],scale:[3f,3f,3f],right_rotation:[0f,0f,0f,1f]}}
```

Capture client.png before summoning; switch to an empty hotbar slot for
client-detail.png. Use `import -window root` on the unique Xvfb display; do not
resize, paint, composite or crop either native screenshot.

## Remaining risks / catalog wish

- The inherited first-person pose clips nearly the entire model at the screen bottom,
  confirmed in the native client. Fixing hand placement requires a separately authorized
  display-transform change. This material task explicitly preserves those transforms.
- Muted brass is absent from the catalog; gold_block is the closest warm polished metal.
- Original coincident fingertip faces and chain separation remain as required; UV alignment
  removes the visible material discontinuity, not the underlying mesh overlap.
- Production resource-pack compilation, server deployment and animation/FOV variants
  remain outside this isolated material/capture task. No Gradle, tests, Docker, servers,
  e2e or push was run.
