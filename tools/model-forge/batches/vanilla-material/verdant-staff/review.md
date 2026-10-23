# Vanilla materials — verdant-staff

153 elements retained, with identical bounds, rotations, names and display transforms.
Only face materials/UVs and the texture-slot map change. No oversized parts needed
splitting. All shipped references use the native block atlas; no custom or Mojang
PNG is added.

| Parts | Catalog material |
|---|---|
| Crooked shaft, curled loop, root branches/hooks, twigs, seed stem | `wood.stripped_spruce_log` |
| Raised loop bark veins | `wood.stripped_oak_log` |
| Stepped crystal facets | `gem.emerald_block` |
| Stepped leaves, front/rear winding vines | `cloth.green_wool` |
| Leaf veins, seed bright hearts | `cloth.lime_wool` |
| Collars, collar bevels, bronze pins | `metal.exposed_copper` |

`material.apply` uses seed 20261024 and one vanilla texel per model unit, including
fractional dimensions. Grain follows each wood element's longest local axis, with
log tops on its end caps. Display scale also scales the texels.

## Visual review

Opened the actual before/after eight-view boards, seven-context Forge board,
[player-contexts.jpg](player-contexts.jpg), and [sweep.jpg](sweep.jpg) showing the
35 captures (26 model directions and nine contexts). Cameras and asset hashes are
retained in [cameras.json](cameras.json). The HTML viewer contains preview-only
vanilla pixels and stays in ignored build output.

- Silhouette and proportions: unchanged in matching before/after views; direct
  JSON comparison also confirms every original element and display transform.
- Joins and ornament: loop, branching roots, wrapping vines and stepped crystal
  remain readable from front/back/oblique views. The staff is intentionally thin
  edge-on. Original crystal/loop separation and overlapping ornament are retained.
- UVs: no obvious stretched grain, missing coverage or newly exposed seams in the
  inspected boards. Spruce's local grain follows the individual crooked segments.
- Palette: emerald is more saturated than the original pale yellow-green seed;
  it remains the focal point against muted wood and foliage. Copper collars are
  restrained; wool avoids tint-dependent leaves and overly noisy thin vines.
- Contexts: both hands, GUI, fixed and ground retain the original placement. The
  player board includes Java left-hand signs and the third-person base rotation.
  Opened [inventory.png](inventory.png) at 16/32/64px: the loop and emerald remain distinct at 32/64px; at 16px the emerald becomes a pale green mark and the silhouette a thin diagonal. Tiny veins and pins disappear as expected.
- No locked concept image was supplied. Before/after is regression evidence only;
  no claim of concept-image agreement or a comprehensive z-fighting audit.

Critic: no material defect requiring a second round identified in these views.
Verifier: geometry and display equality confirmed independently of Forge success;
final validation has zero errors, with the same 348 overlap candidates, two contact
candidates and one disconnected-component report as before. The temporary mixed
atlas warning inside the batch came from the unused original particle slot; the
packaging re-import replaces it with spruce, removes unused slots and clears it.

## Reproduction

Preserve the source model from this commit's parent into a scratch file, then:

```sh
python3 tools/model-forge/batches/vanilla-material/verdant-staff/remake.py \
  --project /tmp/verdant-staff-fresh --before /tmp/verdant-staff-before.json
python3 tools/model-forge/context_preview.py --out build/verdant-staff-contexts verdant-staff
python3 tools/model-forge/context_preview.py --viewer --out build/verdant-staff-viewer verdant-staff
bun tools/model-forge/review.mjs build/verdant-staff-viewer/viewer.html build/verdant-staff-review verdant-staff
python3 tools/model-forge/batches/vanilla-material/verdant-staff/capture-pack.py \
  --out build/verdant-staff-client/resourcepacks/vanilla-material
```

Use a fresh output directory for the capture pack. Forge's client jar defaults to
`/tmp/astera-hud-client-26.3.jar`; override with `MODEL_FORGE_CLIENT_JAR` if needed.

## Native 26.3 verification

Opened [client.png](client.png): raw 1280×720 Xvfb root screenshot, no resizing,
painting or compositing. The whole portablemc launch/capture lifetime held
`flock /tmp/claude-1000/astera-mc-client.lock`; Xvfb `-displayfd` chose display :0.
Game directory: this worktree's `build/verdant-staff-client`. Renderer: llvmpipe,
`LIBGL_ALWAYS_SOFTWARE=1`, `LP_NUM_THREADS=4`. Used the installed launcher at
`~/.cache/astera-vfx-shot/venv/bin/portablemc`, `--main-dir
~/.cache/astera-vfx-shot/mcdir start 26.3`, `--mc-dir` pointing to that unique
directory, `-u VerdantStaff --resolution 1280x720`.

Fresh creative superflat, structures disabled, commands enabled, peaceful,
noon/clear weather, FOV 70, GUI scale 2 and view bob off. Enabled
`file/vanilla-material`, teleported with `/tp @s 0 -60 0 0 0` and held
`minecraft:stick[minecraft:item_model="astera:verdant-staff"]`. Captured with
`DISPLAY=:0 import -window root <client.png>` after command chat/toasts faded.
The launcher and Xvfb were shut down and the lock released after capture.

The native view keeps the emerald and foliage distinct against the wooden loop,
and the thin vine wraps and copper collars remain visible. The handle extends
below the screen with the source transforms, like the pilot; the center is clear.
No missing-model cube, missing sprites, mixed-atlas errors or duplicate-sprite
warnings appeared. The unrelated offline-account Realms authentication error is
present; singleplayer and material loading succeeded. Offline lighting/camera
still differs from native perspective. Other contexts were inspected offline,
not claimed as native captures.

## Catalog wishes and limits

A static untinted foliage material and bronze would better match the source's roles;
green/lime wool and exposed copper are the closest chosen catalog entries. No shared
catalog/tool changes are needed. Existing geometry overlaps remain. Production pack
compilation and deployment are deferred to the manager; the minimal native capture
pack verifies the exact authored JSON, not a deployed Astera pack. No unit tests,
Gradle, E2E, Docker, standalone servers or push were run.
