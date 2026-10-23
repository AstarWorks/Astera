# Frost staff vanilla materials

The original 165-element staff becomes 166 elements: only the 20-unit wooden shaft
splits into 16 + 4 units with internal faces omitted. Bounds, rotation origins,
silhouette and all seven display transforms are retained. Unsplit coordinates agree
within 1e-12 (Forge's floating-point split arithmetic); no shape edits were made.

| Parts | Catalog material |
|---|---|
| Wooden shaft, spiral wood ridge/return | `wood.stripped_dark_oak_log` (automatic end caps) |
| Leather grip, raised wraps | `cloth.brown_wool` |
| Bronze collars/bevels, roots/tips, lower bronze stepped pommel facets | `metal.gold_block` |
| Original silver collars/bevels, silver spiral, socket bezel | `metal.iron_block` |
| Ice shards, upper/lower ice facets, gems, pommel ice inlays | `gem.diamond_block` |
| Pale ice facets and frost fractures | `metal.iron_block` |

A clean replay produced byte-identical model JSON. All faces use `material.apply` seed 20261024 and one vanilla texel per local model
unit, including fractional units. Wood shaft grain follows Y. No custom textures or
Mojang PNGs are installed or committed. The particle slot uses diamond block.
The original atlas remains untouched but is no longer referenced by this model.

## Visual review

This is a material-only brief with the existing model as the geometry constraint;
no separate concept image was supplied. Before/after comparison is regression
evidence, not a claim of matching a generated concept.

- Opened before/after eight-camera boards: silhouette and part proportions agree.
  Cyan crystal, dark wood, brown grip and gold/silver fittings remain distinct.
- Critic found an initial material assignment incorrectly made the original silver
  socket cyan/gold. The final rule recognizes the original silver UV swatch and
  assigns iron to the socket and matching collars. Verifier reopened the final
  head-detail and context images: silver socket restored, crystal outline unchanged.
- Opened player-context board: both first-person hands leave the center clear;
  both third-person hands meet the grip; ground/spin and fixed frame retain the
  original placement. These are illustrative offline cameras, not client parity.
- Opened all 26 directions in `sweep.png`, plus the oblique head-detail capture.
  Stepped crystal facets and gold roots remain readable from the back and underside.
  `review-cameras.json` records the sweep and selected close-up camera.
- Opened `inventory.png`: 32/64px preserve the crystal crown and grip; 16px keeps a
  cyan tip/dark shaft/gold base, but the prongs, silver spirals and wraps alias away.
- Forge validation: zero errors. Before: 414 AABB-overlap warnings and one contact;
  after: 416 overlaps and two contacts. These are conservative candidate warnings,
  including intentional original bevel/ornament intersections and the shaft split.
  This material pass does not claim a full geometric z-fighting audit.

## Reproduction

Save the model from this commit's parent, then run from the repository root:

```sh
git show <parent>:content/models/items/frost-staff.model.json > build/frost-staff-before.json
python3 tools/model-forge/batches/vanilla-material/frost-staff/remake.py \
  --project build/frost-staff-replay --before build/frost-staff-before.json
python3 tools/model-forge/context_preview.py --viewer --out build/frost-staff-viewer frost-staff
bun tools/model-forge/review.mjs build/frost-staff-viewer/viewer.html build/frost-staff-sweep frost-staff
```

Install the existing `tools/skill-viewer` dependencies with
`bun install --frozen-lockfile` first. Keep the generated HTML/data local: they
embed Mojang pixels for review and must not be published or committed.

## Client capture

Opened `client.png`: native Minecraft Java 26.3 screenshot at 1280×720, Xvfb and
llvmpipe, default FOV 70, bobbing off, GUI scale 2. Cyan/white stepped crystals,
silver socket, gold roots/collars and dark wooden shaft render normally. The lower
grip continues below the frame with the original first-person transform. No
missing-model, missing-texture, mixed-atlas or duplicate-sprite warnings were found.
`client-log.txt` preserves the relevant version, renderer, atlas and give-command
lines; unrelated offline Realms authentication/audio-device messages are omitted.

The entire launch/capture session held
`flock /tmp/claude-1000/astera-mc-client.lock`. Xvfb `-displayfd` allocated a unique
free display (:0 for this session). The game directory was
`build/frost-staff/client-frost-staff`, using the existing portablemc runtime at
`/home/node/.cache/astera-vfx-shot/{venv,mcdir}` and software GL
(`LIBGL_ALWAYS_SOFTWARE=1`, `LP_NUM_THREADS=4`). Only this client's process group
and its Xvfb were stopped afterward, releasing the shared lock.

The minimal pack was assembled by the model-local `capture-pack.py`; its model
bytes match the authored JSON exactly. It registers the builder-equivalent custom
block directory source and ships no PNG files. This is an isolated singleplayer
capture, not a production pack/server deployment.

Recreate the pack under a fresh game directory with:

```sh
python3 tools/model-forge/batches/vanilla-material/frost-staff/capture-pack.py \
  --out build/frost-staff-client/resourcepacks/vanilla-material
```

While holding the client lock, launch portablemc 26.3 under a unique Xvfb at
1280×720, with the game directory above. Enable `file/vanilla-material`. Create
`Frost Staff Material Capture`: creative, commands enabled, superflat, seed
20261024, structures off. Use these commands:

```text
/give @s minecraft:iron_axe[minecraft:item_model="astera:frost-staff"]
/difficulty peaceful
/time set noon
/tp @s 0 -60 0 0 0
/weather clear
```

Let the chat fade, keep the HUD and held item visible, then use ImageMagick
`import -window root client.png` on that display. The committed screenshot is
that direct root-window capture, without cropping, resizing or painting.

## Catalog wishes and remaining risks

- Wish: a verified static `packed_ice` / `blue_ice` catalog material. Diamond is the
  closest existing cold cyan crystal; it reads brighter/greener than the original
  blue. Iron supplies the pale facets without noisy wool or animated ice effects.
- Gold stands in for the original bronze; brown wool stands in for leather.
- Existing stepped-crystal overlap/separation and very thin silver/fracture pieces
  remain. Original hand transforms are unchanged; swing, glint and varied FOV
  are outside this static capture.
- Production pack compilation and server deployment remain manager verification.
  No Gradle tasks, test runners, make e2e, Docker or external servers were run.
