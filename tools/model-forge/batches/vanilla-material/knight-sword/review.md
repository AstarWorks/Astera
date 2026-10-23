# Knight sword — vanilla materials

The 108-element original becomes 117 elements through Forge's >16-unit blade
splits. Bounds, named/rotated cuboid volumes and every display transform are
unchanged. The original is the regression reference; no concept image was supplied.
No custom texture was needed, and no Mojang PNG is included in the pack or commit.

| Parts | Catalog material |
|---|---|
| Blade web, ricasso, long bevels, taper, silver lips, nick highlights | `metal.iron_block` |
| Guard, quillons, collars, wheel pommel, bosses, pins and gold borders | `metal.gold_block` |
| Fuller and rivet bezels | `stone.polished_blackstone` |
| Grip | `cloth.brown_wool` |
| Diagonal grip binding | `cloth.black_wool` |

The pieces named `stepped crystal facet` were gold in the original atlas; they
remain part of the gold wheel pommel. Brown/black wool is the nearest catalog
substitute for leather. Catalog wish: a static brown leather-like block material.
No catalog or shared Forge implementation was changed.

## Visual review

- `before.png` and `after.png`: the same eight canonical cameras, opened and
  reviewed. Blade, stepped tip, curled guard and pommel retain the original outline.
  The dark fuller distinguishes the iron blade from its pale background highlights.
  Foreground masks match exactly across the before/after board (zero changed pixels).
- `contexts.png`: pilot-style GUI, both first/third person hands, ground and fixed.
- `contexts-faithful.jpg`: the existing Java hand-sign/player-base renderer's nine
  contexts, opened and reviewed. Grip meets the hand in both third-person views;
  the point clears the center in both first-person previews. Ground and fixed
  contexts remain centered. These are offline resting-pose approximations.
- `orbits.png`: all 26 reviewer orbit cameras, including underside, backside and
  thin edge views, opened and reviewed. `cameras.json` retains camera metadata.
  No newly floating pieces or missing external split faces were visible.

Iron block shading produces pale rectangular patches; it is intentionally more
varied than the former flat blue-gray atlas. At the blade split and narrow bevels,
material crops change but texels retain their local size. Thin gold and cloth
pieces sample only fractions of a texel, so they read mainly by value/silhouette.
Every face uses one vanilla texel per model unit, including fractional dimensions;
no UV exceeds 16 units. The materials all share the native block atlas.

Forge validation has zero errors. Original conservative warnings: 156 overlapping
AABBs and 44 possible coplanar contacts. Final warnings: 172 and 57 respectively;
blade cells introduce extra candidate pairs. The transient batch reports a mixed
atlas warning because the unused original slot is still present; packaging removes
that slot, and the final model has only `minecraft:block/` references. Original
intentional overlaps/contact candidates remain. This is not a complete z-fighting
audit; static images cannot establish motion behavior.

## Reproduce

Save `content/models/items/knight-sword.model.json` from this commit's parent to an
absolute temporary path, then run with a fresh project directory:

```sh
bun install --cwd tools/skill-viewer --ignore-scripts --frozen-lockfile
python3 tools/model-forge/batches/vanilla-material/knight-sword/remake.py \
  --project /tmp/knight-sword-forge-replay --before /tmp/knight-sword-before.json
python3 tools/model-forge/batches/vanilla-material/knight-sword/capture-pack.py \
  --out build/knight-sword-client/resourcepacks/vanilla-material
```

The remake imports, observes every original element, snapshots, assigns materials
in one batch (seed 20261023), packages used slots, reimports, snapshots, renders,
validates and exports through Forge. It also runs the existing context renderer and
35-view reviewer. Embedded vanilla preview data remains in the temporary project;
only rendered evidence and camera metadata are committed. Use
`MODEL_FORGE_CLIENT_JAR` to select a different local jar.

## Native client

`client.png` is the raw 1280×720 Xvfb root-window capture from Minecraft Java 26.3,
portablemc and llvmpipe. Opened and reviewed: the iron blade, black fuller, gold
crossguard and brown/black wrap render clearly. No missing-model cube, missing
texture, mixed-atlas error or duplicate-sprite warning was present. Native lighting
is darker and smoother than Forge's board; the blade's rectangular iron crops are
less pronounced. The unchanged first-person transform extends the grip below the
screen; the blade and crossguard remain visible and clear of the crosshair.

Launch the existing `/home/node/.cache/astera-vfx-shot/venv/bin/portablemc` under
`flock /tmp/claude-1000/astera-mc-client.lock` with
`--main-dir /home/node/.cache/astera-vfx-shot/mcdir start 26.3`,
`--mc-dir "$PWD/build/knight-sword-client" -u KnightCapture --resolution 1280x720`.
Keep that lock through launch, capture and client shutdown. Use a fresh
`Xvfb -displayfd 1 -screen 0 1280x720x24 -nolisten tcp`,
`LIBGL_ALWAYS_SOFTWARE=1`, `LP_NUM_THREADS=4`, FOV 70, GUI scale 2, view bob off,
and `resourcePacks:["vanilla","file/vanilla-material"]` in the isolated options.
Create an isolated creative superflat with commands enabled and structures off.
Give `/give @s minecraft:iron_sword[minecraft:item_model="astera:knight-sword"]`,
set `/time set noon`, `/weather clear`, and `/tp @s ~ ~ ~ 0 0`. Allow chat to fade,
then use `import -window root` without resizing/painting. The pack's exact model
bytes were compared with the committed model before launch. Only native block
references are shipped. This verifies the minimal capture pack, not production
pack compilation or an Astera deployment.

## Remaining risks

- Production resource-pack build and server deployment remain manager/CI work.
- Iron block's border pattern is visible on narrow blade strips; a brushed steel
  block material would provide a quieter alternative if the catalog gains one.
- Existing bevel overlaps and coplanar candidates are preserved, as required.
- Swing/use animation and changing FOV require additional in-game review.

No Gradle, tests, E2E, Docker, dedicated server, push or shared-content edits were run.
