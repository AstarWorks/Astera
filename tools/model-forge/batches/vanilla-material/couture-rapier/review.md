# Couture rapier — vanilla materials

- Blades and cutting edges (`e0001–e0006`, `e0012–e0017`): `metal.iron_block`.
- Open handles (`e0007–e0011`, `e0018–e0022`): `stone.polished_blackstone`.
- Pale flower pivot (`e0023–e0026`): `metal.iron_block`.
- No custom PNGs or shipped Mojang PNGs. All resources use the native block atlas.

Geometry, names, rotations, element order and every display transform match the original exactly (26 elements; no splits needed). Vanilla UV spans retain one texel per local model unit. Thin blade side faces use quarter-turn crops inside the iron block texture: its subtle lines run along the blade instead of forming distracting transverse bands. Crops on opposite blade faces match, preserving a coherent finish. Blackstone is the closest opaque dark catalog material to the original black handles. Wish: a smooth opaque dark metal; `metal.anvil` has transparent pixels unsuitable for these thin closed handle bars.

## Visual review

Opened and inspected `before.png`, final `after.png` (eight identical Forge cameras), `forge-contexts.png`, `contexts.png` (Java left-hand signs and mannequin contexts), `all-angles.png` (26 directions) and `inventory.png` (16/32/64 px). The silhouette, blade steps, flower and handle joins are retained. No newly floating parts, cutout holes or stretched blade texels are visible. Iron is brighter than the original painted gunmetal, improving inventory readability. The black handles have low contrast on the dark offline background, as the original does.

Critic: initial iron crops made horizontal bands; gold handles and purple petals departed from the original palette. An anvil handle trial exposed alpha holes. Fixes: iron side UVs rotated 90 degrees with swapped spans; opaque polished-blackstone handles; pale iron flower. Verifier: the final blades have longitudinal detail, all handle bars are continuous, and the flower retains its pale color. Front/back/oblique/underside views preserve outline and joins. No concept image was supplied; the original model is regression evidence only.

Forge validation: zero errors, 54 conservative overlap warnings before and after. Original edge/bevel and flower intersections remain; this material-only edit is not a complete z-fighting audit. No geometry was added to meet the generic hero-prop element target.

## Reproduction

Save the original model from this commit's parent, then run:

```sh
git show <parent>:content/models/items/couture-rapier.model.json > /tmp/couture-rapier-before.json
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/batches/vanilla-material/couture-rapier/remake.py \
  --project /tmp/couture-rapier-fresh --before /tmp/couture-rapier-before.json
python3 tools/model-forge/context_preview.py --viewer --out build/couture-rapier-reviewer couture-rapier
bun tools/model-forge/review.mjs build/couture-rapier-reviewer/viewer.html build/couture-rapier-review couture-rapier
```

`operations.json` records Forge operations, including crop adjustments, snapshots, validation and export. `cameras.json` preserves the 35 reviewer cameras; `all-angles.png` is a contact sheet of the orbit captures. Viewer HTML embeds cached vanilla pixels and remains under ignored `build/`.

## Native client and remaining limits

`client.png` and `client-thirdperson.png` were opened and inspected. The native log contains no missing-model or mixed-atlas errors. `client.png` is an unedited native 1280×720 screenshot from Minecraft Java 26.3, portablemc, Xvfb and software GL, in an isolated creative superflat. The capture pack contains the exact final model, its item definition and the pilot's builder-equivalent block atlas directory source. Assemble it with this directory's `capture-pack.py --out <fresh-pack-directory>`. Launch and capture under `flock /tmp/claude-1000/astera-mc-client.lock`, using a free Xvfb display and a game directory containing `couture-rapier`.

The original first-person transforms put the handles and pivot mostly below the screen and the paired blades edge-on; preserved per scope. The offline side context and GUI show the paired silhouette; the native front-facing third-person view is nearly edge-on under the preserved transforms. Offline hand/lighting approximations cannot establish swing, bobbing, varying FOV or shader behavior. Native capture is singleplayer evidence, not a production pack or Astera server deployment. Production pack/integration verification remains with the manager. No Gradle, tests, E2E, Docker, server launch or push was run.
