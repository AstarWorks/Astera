# Forge C — weapon model batch

Branch: `wp/forge-c`. Initial `git merge --no-edit feat/model-forge`: already up to date.
Seven locally authored items, each committed separately, followed by a final surface/material audit.
No Gradle, Docker, tests, push or CI. Built-in imagegen supplied all seven independent visual targets;
no external paid modeling service, mesh API or image-generation CLI was used.

| Item | Modeling rounds | Elements | Local verdict | Main remaining weaknesses | Item commit |
|---|---:|---:|---|---|---|
| verdict-hammer | 5 + final audit | 161 | Accept for founder/client review | Stepped medallions; thin haft fades at 16px; gem shading simpler than painting | `95ff1cf2` |
| pistol | 5 + final audit | 82 | Accept for founder/client review | Grip slimmer and metal edges brighter than concept; tiny controls vanish at 16px | `e9b620fc` |
| rifle | 5 + final audit | 137 | Accept for founder/client review | Angular magazine/stock; tiny green optic is angle-dependent | `fe5f9e31` |
| rtm-blaster | 5 + final audit | 177 | Accept for founder/client review | Lower housing slimmer than concept; painted purple has no bloom/emission | `033f3683` |
| shotgun | 5 + final audit | 124 | Accept for founder/client review | Fine barrel facet seams in iso; two shells merge at 16px | `25fa50c9` |
| sigil-rifle | 5 + final audit | 174 | Accept for founder/client review | Octagonal ring; symbolic rune strokes; ring collapses edge-on at 16px | `780e17bc` |
| vendetta-rifle | 5 + final audit | 178 | Accept for founder/client review | Stepped bayonet curve; angular stock; orange optic weak side-on at 16px | `bd062cef` |

“Accept” is an authoring judgment against the generated reference, not founder approval or a claim
that the local rasterizer proves in-game quality. Every final model remains below 200 elements.

## Deliverables and evidence

For each ID above:

- `content/models/items/<id>.model.json`: explicit Java cuboids, fractional coordinates, bevel/facet
  sections, separate ornament and 22.5°/45° diagonal pieces where appropriate.
- `content/textures/items/<id>.png`: 128×128 pixel atlas, material ramps, painted edges/grain/wear;
  JSON references `astera:items/<id>`. Density is consistently 2 texels/model unit, the forge's profile.
- `tools/model-forge/work/<id>/ai-concept.png`: actual built-in imagegen turnaround based on item YAML,
  weapons-v1 description and source sheet cell. Prompt briefs and interpretation caveats are in `review.md`.
- `final-sheet.png`: AI target beside front/side/top/iso, GUI, both first-person and third-person hands,
  ground/fixed and 16/32/64px inventory previews.
- `redo-1` through `redo-5`: preserved model, atlas, comparison and numeric review snapshots for the
  five sequential modeling steps. Each sheet was opened and observations recorded before advancing.
- `redo-6`: final surface attachment/material audit on a lighter neutral comparison background;
  not counted as a sixth detail-building stage. Snapshot geometry/atlas is the shipped result.
- `final-review.json`, `production-validation.json`, `review.md`: checks, hashes and concrete observed
  differences. Recipes are under item work directories; shared `tools/model-forge/*.py` is untouched.

The hammer's original `concept.json` and old construction `concept-sheet.png` are legacy artifacts
from the merged branch. They were **not** the target for this redo. Use `ai-concept.png` and the new
final sheet. No IoU score against a model-derived or code-drawn diagram is used for acceptance.

Imagegen's initial hammer top panel was an elevation; a second imagegen call corrected it before
modeling. The pistol's first request was rejected by the service; an explicitly nonviolent game-prop
request succeeded. Some generated gun overheads retain illustrative side surfaces/scale drift;
these are documented interpretation limits rather than ground-truth engineering projections.

## Validation and reproduction

The unchanged forge renderer and validator are imported by the item recipes. The old CLI's hard-coded
64-element default and code-polygon target gate conflict with this batch's requested detail level and
AI-reference review. The recipes call `validate(..., budget=250, density=2)` directly and compare the
actual generated raster beside forge renders. They do not monkey-patch or edit shared tools.

Run from this worktree:

```sh
python3 tools/model-forge/work/verdict-hammer/audit.py
python3 tools/model-forge/work/pistol/recipe.py 6
# Substitute any of the seven IDs for pistol to regenerate its final audit preview.
# --finish also copies the reviewed model/atlas into the item's content paths.
```

All seven actual content files pass schema, positive volume, coordinate/rotation, UV/density,
texture resolution, namespace and display-slot validation with the 250-element cap. All eleven
render views per item are unclipped. Guns use model +X as the long axis, rotated toward forward -Z
in both hand slots; previews are isolated stored-transform views, not player-hand simulations.

The hammer helper supplies small atlas/cuboid/review functions; the rifle and pistol recipes supply
batch-local reusable parts. Sibling recipes import those files, so retain all committed recipes.
Snapshots retain exact intermediate observations even where later recipe edits improved earlier stages.

## Remaining release checks

Load the pack in the real client: both hands, grip alignment, FOV/GUI scale, use/swing animations,
back faces, glint, fine facet seams and z-fighting under motion. The forge omits engine perspective,
player arms, animation, AO and glint. Tiny trim and rune legibility are not promised at 16px.
No client or premium-pack comparison certification is claimed.

## Tool ideas for the manager (not implemented)

Expose budget/density flags in the shared CLI; support imported AI turnaround sheets directly without
polygon masks; offer neutral/light preview backgrounds; add a player-hand/perspective reference scene;
add geometric attachment and coplanar-overlap diagnostics; support per-item material-patch editing.
