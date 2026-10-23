# Fire staff — vanilla materials

| Parts | Catalog material |
| --- | --- |
| Wooden shaft, spiral wood ridge/return | `wood.stripped_dark_oak_log` |
| Leather grip and raised leather wrap | `cloth.brown_wool` |
| Flame contour and curl point | `metal.copper_block` |
| Stepped crystal facets (flame core and pommel), collars/bevels, cradle/fork, socket and engraved ridges, facet glints | `metal.gold_block` |
| Socket and pommel rubies | `cloth.red_wool` |

Seed 20261023. All faces use `material.apply` crops at one vanilla texel per local
model unit, including fractional dimensions. Grain follows each wood element's longest
axis. All references stay on the native block atlas; no custom atlas or Mojang PNG is
installed. Gold retains the flame's bright core against the warm copper outline.
Catalog wishes: static amber/orange crystal and ruby-red gem. Copper/gold approximate
the flame crystal; the sub-unit ruby accents use wool for their red color.

## Visual review and verification

Opened before.png, after.png, contexts.png, player-contexts.jpg, sweep.png and
inventory.png. The matching eight-view Forge boards preserve the existing silhouette.
The player board includes both first/third-person hands, third-person side, two ground
spins, fixed and GUI. The 26-direction sweep and its nine contexts are recorded in
cameras.json. No concept image was supplied; the original is a regression reference.

- Silhouette/proportions: retained. The only split is the 20-unit wooden shaft at Y=16,
  yielding 113 → 114 elements, with internal faces omitted. Display transforms match
  exactly. Other cuboid coordinates match within 5.56e-17 export rounding; rotations
  are unchanged. measure.json records the final geometry and validation.
- Bevels/joins: the gold fork, collar steps, spiral wood, flame curl and ruby accents
  remain visible in front/back/oblique views. Existing layered facet seams and ornament
  separations remain; this task does not redesign geometry.
- UVs/palette: no stretched texels or exposed shaft split cap observed. Thin gold
  fittings remain simple, and shaft grain runs lengthwise. The first material attempt
  assigned copper to all crystal facets and lost the bright flame core; the final map
  restores gold to those facets and the pommel. Both sides improved with no silhouette
  change or hand-context regression.
- Inventory/contexts: the flame, dark shaft and gold fittings read at 32/64px. At 16px
  the staff outline and warm head remain identifiable but tiny rubies and ridges merge.
  inventory.png shows 16px nearest-neighbor downsample of the 32px Forge GUI render,
  plus native 32/64px renders enlarged for inspection. The shaft continues below the
  first-person screen as in the original transform; the center remains clear.

Forge validation: zero errors. Before: 199 AABB overlaps, 2 possible coplanar contacts,
1 disconnected-component report. After: 201 / 3 / 1. The added contact is the shaft split;
these conservative diagnostics are candidates, not a complete z-fighting audit.

## Reproduction

From the repository root, save the original from the parent commit and use a fresh project:

```sh
git show 659893b9fd76639a41dda706ac14adf6ff24ebab:content/models/items/fire-staff.model.json > /tmp/fire-staff-before.json
python3 tools/model-forge/batches/vanilla-material/fire-staff/remake.py --project /tmp/fire-staff-fresh --before /tmp/fire-staff-before.json
python3 tools/model-forge/context_preview.py --out build/fire-staff-contexts fire-staff
python3 tools/model-forge/context_preview.py --viewer --out build/fire-staff-viewer fire-staff
bun tools/model-forge/review.mjs build/fire-staff-viewer/viewer.html build/fire-staff-review fire-staff
python3 tools/model-forge/batches/vanilla-material/fire-staff/capture-pack.py --out build/fire-staff-client/resourcepacks/vanilla-material
```

The last command requires a fresh pack directory. Set MODEL_FORGE_CLIENT_JAR to a local
26.3 jar if the default `/tmp/astera-hud-client-26.3.jar` is unavailable. Preview caches
and the standalone viewer remain ignored; only review images are committed.

## Native client capture

Opened and reviewed [client.png](client.png): native 26.3, portablemc, Xvfb/llvmpipe,
1280×720 first-person right hand. Copper is darker than Forge lighting, but the gold
core, fork and collars stay distinct, ruby accents remain red and wood grain follows
the shaft. No missing model/texture, mixed-atlas or duplicate-sprite diagnostics were
found for this model. The capture is a raw root-window screenshot, without resizing,
painting or compositing. HUD remains enabled so the held item is visible.

The complete launch/capture lifetime held `/tmp/claude-1000/astera-mc-client.lock`.
Xvfb `-displayfd` assigned unique display :0. The unique game directory is
`build/fire-staff-client`; its creative superflat is an isolated copy of an earlier
capture world, with the prior item display and inventory removed. The pack contains
only the exact authored fire-staff JSON, item definition and builder-equivalent block
atlas source. No Mojang PNGs are present.

Use the installed `~/.cache/astera-vfx-shot/venv/bin/portablemc` with main directory
`~/.cache/astera-vfx-shot/mcdir`, `start 26.3`, the unique `--mc-dir`, username
`FireStaffReview`, resolution 1280x720, software GL and `LP_NUM_THREADS=4`.
Enable `file/vanilla-material`, view bob off, GUI scale 2 and FOV 70. In an isolated
creative superflat with commands enabled:

```text
/kill @e[type=minecraft:item_display]
/clear @s
/give @s minecraft:iron_axe[minecraft:item_model="astera:fire-staff"]
/time set noon
/weather clear
/tp @s 0 -60 3 180 0
```

After chat fades, capture the Xvfb root with ImageMagick `import -window root`.
The exact minimal-pack model was verified byte-for-byte against the authored asset.
This does not establish production pack deployment or server behavior.

## Remaining risks

Copper and gold are static material approximations of the flame; they do not animate
or emit light. Wool approximates the ruby accents. Original bevel intersections and
small ornament gaps remain. Production pack compilation, server deployment and manager
CI remain unexecuted. No Gradle tasks, unit tests, E2E, Docker, server or push was run.
