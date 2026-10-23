# Crimson chain axe — vanilla materials

| Parts (original IDs) | Catalog material |
| --- | --- |
| crimson haft (e0001) | wood.stripped_dark_oak_log, Y grain and log-top end caps |
| crimson crescent (e0003–e0074, every third element) | metal.copper_block |
| raised blade ribs, socket, crown spike, bridge, rims, ruby seat | metal.anvil |
| cutting bevels, ferrules, chain, rivets, blade pins, white stars, ruby glint | metal.iron_block |
| ruby crown and facets (e0081–e0083) | cloth.red_wool |

Seed 20261023; all material faces use one vanilla texel per model unit, including
fractional dimensions. All references use the native block atlas. No custom PNG is
needed and no Mojang PNG is shipped. The closest warm metal is copper: the red blades
become orange-brown. Catalog wishes: static deep-crimson metal and ruby-red gem.
Red wool supplies the tiny ruby facets' red color; it is an approximation, not a gem material.
Iron replaces the chain's painted steel; using the cutout chain sprite on already-modeled
sub-unit links would introduce unnecessary transparency/noise.

## Critic and verifier

Opened and reviewed before.png, after.png, contexts.png, player-contexts.jpg,
sweep.png (26 directions including both poles), inventory.png and native client.png.
The eight-view Forge before/after uses matching 384px tiles and unchanged geometry framing.
The separate player board includes both first/third-person hands, side view, both ground
spins, fixed and GUI; cameras.json records the viewer captures. These offline boards
are illustrative, not a substitute for native rendering.

- Silhouette and proportions: yes, retained against the original asset. No external
  concept image was supplied; the source is the regression reference, not a new concept.
- Bevels and ornament: yes, the staggered blade outline, raised ribs, chains and white
  socket stars remain visible in front/back/oblique views. Existing stepped blade seams,
  small tip/bridge separations and star-to-socket gaps remain; no geometry cleanup.
- Geometry and transforms: verified numerically within 1e-12 model units. Only the
  20-unit haft splits at Y=16 (163 → 164 elements). Internal split faces are omitted.
  Display transforms are identical. Export round-off on the ruby seat is below 2e-16.
- UVs and seams: yes, no stretched broad faces or exposed haft-split end caps observed.
  Wood grain follows Y. Copper crop differences reinforce the existing blade steps;
  the blade still reads as two metal crescents. Thin iron bevels and links stay simple.
- Inventory: axe outline and warm/cool separation read at 16/32/64px; the socket star
  and individual chain links lose detail at 16px. No claim of legible tiny ornament.
- Hand contexts: the source has scale [0,0,0] in BOTH first/third-person hands. These
  remain invisible, as confirmed by the empty offline hand contexts and native held
  item before hiding the GUI. This material-only task preserves those transforms.
- Native verification: client.png shows the exact authored asset in fixed context on
  an item_display in an isolated creative superflat, default FOV 70, GUI hidden.
  No missing-model cube, missing textures, mixed-atlas or duplicate-sprite warnings.
  Copper is darker than the offline preview but the iron chain and red pommel remain distinct.

Forge validation has zero errors. Conservative warnings: before 297 overlaps / 5
contacts / 1 disconnected-component report; after 299 / 6 / 1. The added contact is
the haft split. These are candidates, not a proof of visible z-fighting. No geometry,
display, shared tool/catalog, other model or original texture was intentionally changed.

## Reproduction

From the repository root, preserve the original and use a fresh Forge directory:

```sh
git show 36743b70a5074a438dcc20e4d58beb774be06d65:content/models/items/crimson-chain-axe.model.json > /tmp/crimson-chain-axe-before.json
python3 tools/model-forge/batches/vanilla-material/crimson-chain-axe/remake.py --project /tmp/crimson-chain-axe-fresh --before /tmp/crimson-chain-axe-before.json
python3 tools/model-forge/context_preview.py --out build/crimson-chain-axe-contexts crimson-chain-axe
python3 tools/model-forge/context_preview.py --viewer --out build/crimson-chain-axe-viewer crimson-chain-axe
bun tools/model-forge/review.mjs build/crimson-chain-axe-viewer/viewer.html build/crimson-chain-axe-review crimson-chain-axe
python3 tools/model-forge/batches/vanilla-material/crimson-chain-axe/capture-pack.py --out build/crimson-chain-axe-client/resourcepacks/vanilla-material
```

The capture pack refuses to overwrite an existing output. Use the installed portablemc
at ~/.cache/astera-vfx-shot/venv/bin/portablemc with --main-dir
~/.cache/astera-vfx-shot/mcdir, start 26.3, --mc-dir pointing to the unique
build/crimson-chain-axe-client directory, -u MaterialAxe and --resolution 1280x720.
Wrap the ENTIRE launch/capture lifetime in
`flock /tmp/claude-1000/astera-mc-client.lock bash -c '…'`.
Use Xvfb -displayfd for a unique display, LIBGL_ALWAYS_SOFTWARE=1 and LP_NUM_THREADS=4.
Enable file/vanilla-material, GUI scale 2, view bob off, FOV 70. Create a fresh creative
superflat with commands enabled and structures disabled; set noon, clear weather and peaceful.

Give `minecraft:iron_axe[minecraft:item_model="astera:crimson-chain-axe"]`.
Teleport to `0 -60 3 180 0`. Summon the native fixed-context showcase:

```text
/summon minecraft:item_display 0 -59 0 {item:{id:"minecraft:iron_axe",count:1,components:{"minecraft:item_model":"astera:crimson-chain-axe"}},item_display:"fixed",transformation:{translation:[0f,0.6f,0f],scale:[1.7f,1.7f,1.7f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}
```

Press F1 and capture the root window with ImageMagick import; client.png is the native
1280×720 output, without resizing, painting or compositing. The entity scale enlarges
the showcase only; the model's fixed transform is unchanged. The capture holds the
same asset in the inventory, but its native hand transform intentionally hides it.

## Remaining risks

The original invisible held contexts and ornament gaps remain. Copper and red wool
approximate colors absent from the catalog. Production pack compilation and manager CI
remain unexecuted; this verifies the exact JSON in a minimal client pack, not a deployed
Astera pack. No Gradle tests, E2E, Docker, standalone server or push was run.
