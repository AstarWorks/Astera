# Model Forge

Local designer tooling for Astera Java cuboid held items. Python 3.10+, numpy, Pillow and PyYAML;
all were already installed on the authoring host. `requirements.txt` lists those dependencies.
No Gradle task, server process, Blender, paid service, API key, test runner or CI is involved.

Read [the production procedure](../../docs/art/3d-model-pipeline.md) before authoring.
Run commands from the repository root. The script finds content paths relative to itself.

## Commands

```sh
# Lock a construction target, including the existing icon and source-sheet crop.
python3 tools/model-forge/recipe.py concept fire-staff \
  --brief 'Wooden haft, red-orange flame crystal, open fork, restrained gold bindings'

# One round at a time. View comparison.png before advancing; do not batch all stages blindly.
python3 tools/model-forge/recipe.py build fire-staff --stage 1
python3 tools/model-forge/recipe.py render fire-staff \
  --model tools/model-forge/work/fire-staff/round-1/model.json \
  --texture tools/model-forge/work/fire-staff/round-1/texture.png \
  --out tools/model-forge/work/fire-staff/round-1 --clay

# Stages: 1 blockout, 2 shape, 3 details, 4 texture, 5 polish.
# After visual review and edits, build/render the next stage with its corresponding paths.
python3 tools/model-forge/recipe.py validate fire-staff \
  --model tools/model-forge/work/fire-staff/round-5/model.json \
  --texture tools/model-forge/work/fire-staff/round-5/texture.png

# Publish reviewed local assets into content, rejecting low IoU or clipped display views.
python3 tools/model-forge/recipe.py finalize fire-staff \
  --model tools/model-forge/work/fire-staff/round-5/model.json \
  --texture tools/model-forge/work/fire-staff/round-5/texture.png

# Inspect the committed model using its real resource texture references.
python3 tools/model-forge/recipe.py validate fire-staff
python3 tools/model-forge/recipe.py render fire-staff
```

The four delivered items already have locked concepts; rerunning `concept` in their default directories
fails deliberately. Use `--out tools/model-forge/work/<id>/alternative-concept` for an explicit revision,
and `render --concept <path>/concept.json` to compare it. `finalize` always uses the locked item target.
`build` overwrites only its stage's work files; choose `--out` to retain an alternative. `finalize`
overwrites that item's content model/atlas and final artifacts, so inspect the candidate first.

`render` outputs front, side, top, iso, gui, firstperson_righthand, thirdperson_righthand, ground and
fixed PNGs, `comparison.png`, `inventory.png`, and machine-readable `review.json`. Camera frames are
fixed; clipping is reported rather than hidden by auto-fit. Atlas loading follows the pack mapping,
including texture aliases and multiple atlases. `--texture` deliberately replaces every atlas for a
single-atlas work model. Astera model-parent inheritance is resolved, with cycle detection. Vanilla
built-in parents must first be resolved to explicit cuboids; unsupported parents fail rather than
producing a misleading image.

`validate` checks `model.schema.json`, coords -16..32, ordered positive cuboids, one-axis rotations in
our conservative angle set, UV bounds/quarter-turns, resolvable PNGs, display ranges, required slots,
2 texels/unit (+/-5%) and the 64-element budget. It returns a nonzero exit status on failure. It checks
transformed face lengths when rescale is enabled. Rotated bounds beyond the coordinate box and partial
alpha produce warnings. The dependency-free schema walker implements exactly the keywords in the
bundled schema; it is not a general JSON Schema library. The schema itself is ordinary Draft 2020-12
JSON Schema for use in editors or external validators.

## Authoring and concept generation

`author.py` contains explicit staged recipes for the four props. `hammer.py` retains the useful seed
geometry and carefully painted 64px atlas; `raster.py` adapts its rasterization math. The old seed,
its logs, ad hoc validator and redundant bbmodel were removed after migration.

The agent authors construction polygons and pixel textures directly with Python. `concept.py` reads
item YAML, records its hash/material, draws front/side/top/3/4 on magenta, and reuses both the generated
icon and the correct crop from `weapons-v1.png`. The supplied designs interpret the source image;
they are not generated from `author.py` or candidate model geometry. The brief is the agent's art
direction, stored for review; there is no NLP parser promising arbitrary art from prose.

For a new item, inspect its YAML and art, then supply `concept --design <json>` with this structure:

```json
{"parts":[{"polygon":[[7,0],[9,0],[9,20],[7,20]],"depth":2,"z":8,"color":"#70422b"}]}
```

Each polygon describes an independently drawn XY contour with a specified depth and center Z.
This supports concave guards and non-cuboid target contours. The concept painter is a simple
surface-depth-sorted diagram painter: its isometric overlapping colors can be imperfect, but its
occupied silhouette is a union of projected polygons. Do not treat it as the final material renderer.
For items outside the existing source-sheet manifest, add a reference crop entry or adapt that read;
the current four-item generation path has both references available.

`work/<id>/round-*` contains local model/texture snapshots, previews and metric records. Work files are
ignored except **final-sheet.png, final-review.json, concept-sheet.png, concept.json and review.md**.
Those retained files are the final review package. Final review JSON includes each observed round's
scores, target hash and artifact hashes. Earlier rounds are historical observations of an evolving
recipe, not promises that today's earlier stage reproduces every past pixel. Stage 5 reproduces the
shipped asset. The target hash stays the same across rounds.

## Review and limits

Use vision to open the target and comparison image after every round. Write the defect and intended
edit in `review.md`. Check silhouette AND palette/joins/negative space; inspect the existing icon too.
The final gate is >=0.80 IoU in each of four construction views, valid model and density, no clipped
preview and the visual checklist in the pipeline document. A gate pass does not mean client approval.

The renderer uses `T * Rx * Ry * Rz * S`, rotating about `[8,8,8]` for display views. Geometry views use
`[8,13,8]`, a 36-unit span and no display transform. GUI uses a 16-unit span; the remaining display
previews use 32. Hand previews show isolated stored transforms, not an actual hand or perspective
camera. Illustrative lighting, binary cutout alpha and no tint/glint/AO are deliberate limitations.
No automatic runtime LOD, UV packing optimizer, mesh inference, animation or texture synthesis API.
The final models can be imported directly into Blockbench as Java Block/Item JSON with their PNGs.
