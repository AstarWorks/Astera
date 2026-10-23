# Model Forge: local 3D agent tool

One persistent project library (`core.Project.call`) drives CLI, loopback REST and MCP stdio.
Python 3.10+, numpy and Pillow; no Blender, Gradle, Docker, network services or API keys.
The old staged content recipes remain in [RECIPES.md](RECIPES.md), invoked through `recipe.py`.
Agent authors must follow [AGENT.md](AGENT.md).

## Start here

From the repository root:

```sh
python3 tools/model-forge/forge.py --project /tmp/hammer call import \
  '{"path":"content/models/items/verdict-hammer.model.json"}'
python3 tools/model-forge/forge.py --project /tmp/hammer call scene.get
python3 tools/model-forge/forge.py --project /tmp/hammer call history.snapshot '{"name":"round-1"}'
python3 tools/model-forge/forge.py --project /tmp/hammer call board '{"name":"before"}'
python3 tools/model-forge/forge.py --project /tmp/hammer serve --port 8765
```

CLI arguments are JSON, `@request.json`, or `-` for stdin. `python -m forge` works from
`tools/model-forge`. All successful calls print JSON; errors go to stderr and exit nonzero.
`validate` also exits nonzero for invalid state. No shell execution escape hatch is exposed.

REST uses the **same operation names and argument objects**:

```sh
curl -s http://127.0.0.1:8765/health
curl -s http://127.0.0.1:8765/call -H 'Content-Type: application/json' \
  -d '{"op":"scene.get","args":{"fields":["id","name","bbox"]}}'
curl -s http://127.0.0.1:8765/call -H 'Content-Type: application/json' \
  -d '{"op":"element.duplicate","args":{"id":"e0038","new_id":"extra_stud","offset":[0,1,0]}}'
curl -s http://127.0.0.1:8765/call -H 'Content-Type: application/json' \
  -d '{"op":"board","args":{"highlight":["extra_stud"],"base64":true}}'
curl -s http://127.0.0.1:8765/call -H 'Content-Type: application/json' \
  -d '{"op":"history.undo"}'
```

`POST /call` returns 200 or 400 with `{"error":"..."}`. `/health` is the only GET endpoint.
Requests are limited to 8 MiB. Bind is always `127.0.0.1`; browser Origin requests are rejected.
The server is a trusted local authoring tool with filesystem read/write access for import/export,
not a remotely exposed service. One project is selected at startup. No CORS or static file server.
Images return absolute `path`, plus PNG `base64` when requested. Generated files go into
`<project>/renders`; `name` chooses the filename and overwrites earlier output of that name.
Use a unique round name to keep evidence.

MCP stdio (newline JSON-RPC; initialize, ping, tools/list, tools/call):

```sh
python3 tools/model-forge/forge.py --project /tmp/hammer mcp
```

Configure an MCP client with command `python3` and arguments
`["/absolute/path/tools/model-forge/forge.py","--project","/tmp/hammer","mcp"]`.
The `forge` tool accepts `{op,args}`. stdout is protocol-only. Images are paths/optional base64
in JSON text, so the calling agent must open the path or decode the PNG.

## Operation reference

The following argument objects work identically in CLI, REST and MCP. Required fields are
shown; optional fields and defaults are described inline. IDs are stable across edits and history.

| Operation | Arguments / result |
|---|---|
| `import` | `{path, textures?: {resource_reference: png_path}}`; explicit cuboids, local Astera parents, PNGs and vanilla material references. Replaces current scene with undo. Custom texture aliases resolve into editable atlases; vanilla aliases retain references. |
| `scene.get` | `{fields?: ["id","name","group","from","to","rotation","faces","bbox"], root?: group_or_element_id}`; compact element rows, groups, texture key-to-resource map. Root groups include descendants. |
| `scene.element` | `{id}`; full element. |
| `element.add` | `{id?: stable_id, element: {name, from:[x,y,z], to:[x,y,z], faces:{south:{texture:"#0",uv:[u,v,U,V]},...}, rotation?, group?}}`; omitted ID is generated. |
| `element.update` | `{id, changes:{...}}`; shallow property replacement (a supplied `faces` replaces the face map). IDs cannot change. |
| `element.delete` | `{id}` |
| `element.duplicate` | `{id, new_id?, offset?:[x,y,z]}`; copies geometry, UVs and group; shifts rotation origin too. |
| `element.mirror` | `{id, axis:"x"|"y"|"z", origin?:8}`; in-place reflection about the coordinate plane, including rotation, face UV orientation and cullface. Duplicate first to keep the original. |
| `group.create` | `{id?, name, origin?:[8,8,8], parent?}`; organizational group, no geometry transform. |
| `group.move` | `{ids:[element_or_group_id,...], parent:group_id_or_null}`; reparent; cycles rejected. |
| `batch` | `{ops:[{op:"element.update",args:{...}},...]}`; one atomic geometry/texture transaction, one undo step. History/import/nested batches excluded. |
| `texture.create` | `{key, size?:[64,64], color?:"#00000000"}`; new RGBA atlas, dimensions 1–2048. Existing keys rejected. |
| `texture.get` | `{key, name?, base64?}`; custom PNG artifact. Vanilla slots return a read-only cache path, never an exported PNG. |
| `texture.palette` | `{key, limit?:32}`; most common RGBA colors with counts. |
| `texture.paint` | `{key, ops:[...]}`; pixel coordinates, Pillow color strings; forms below. |
| `texture.regions` | `{}`; element ID, face, texture key and UV rectangle for every face. |
| `material.apply` | `{ids:[element_id,...], material:catalog_name, faces?:["north",...], seed?:0}`; assign static vanilla material, crop at one texel/model unit, deterministic varied offsets, grain along longest local axis. Splits cuboids over 16 units; returns resulting IDs. Atomic and undoable, including splits. |
| `uv.set` | `{id, face, value:{texture:"#0",uv:[0,0,4,4],rotation?:90}}`; replace one face descriptor. |
| `uv.auto` | `{key, density?:2, ids?:[...]}`; shelf pack with one pixel gutters; texels/model unit. Atomic failure if atlas is full. Selection requires a dedicated texture unused by unselected faces. Repaint after packing. |
| `render` | See below; individual image paths and clipping diagnostics. |
| `board` | Same arguments; labelled board plus individual images; default eight views. |
| `compare` | Render arguments plus `{reference_image:path}` or `{references:{front:path,...},background?:[255,0,255]}`; reference/current/silhouette board, IoU and histogram distance. |
| `measure` | `{}`; transformed bbox, element count, per-part coordinate lengths, transformed edge lengths, length ratios and per-face texel density. |
| `validate` | `{}`; errors and structured warnings. |
| `history.snapshot` | `{name}`; named model+texture checkpoint; duplicate names rejected. |
| `history.list` | `{}`; names and undo depth. |
| `history.undo` | `{}`; undo last edit including texture pixels; snapshots do not consume undo steps. |
| `history.restore` | `{name}`; restore checkpoint, itself undoable. |
| `history.diff` | `{a:snapshot_name,b:snapshot_name_or_"current"}`; values before/after; textures represented by SHA-256 digests. |
| `export` | `{path:directory}`; `model.json` and custom `textures/*.png`. Rewrites custom references for the bundle; vanilla references stay unchanged, with no vanilla PNGs. |

All mutations return `{result,diff,validation}`. Invalid edits and failed batches roll back.
The canonical `project.json` contains current model, PNG bytes, undo states and snapshots;
an atomic rename commits it, and a file lock serializes CLI/REST/MCP operations on Linux.
`model.json` and `textures/*.png` are convenience mirrors regenerated after changes.
Read through the API; don't modify the mirrors. Full snapshots trade disk space for simplicity.
No redo, pruning, crash recovery for convenience mirrors, or database is needed for this local tool.

Paint operations (boxes include both endpoints):

```json
{"key":"0","ops":[
  {"type":"rect","box":[0,0,7,7],"color":"#e8c56a"},
  {"type":"line","points":[[0,0],[7,7]],"width":1,"color":"#ffffff"},
  {"type":"fill","point":[2,1],"color":"#537875"},
  {"type":"pixels","pixels":[[1,1,"#ffe8aa"],[2,2,"#223333"]]},
  {"type":"gradient-ramp","box":[8,0,23,7],"colors":["#143d40","#82e6d6"],"steps":5}
]}
```

Render arguments:

```json
{"views":["front","back","left","right","top","bottom","iso","iso_back"],
 "resolution":256,"mode":"textured","highlight":["e0033"],
 "overlay":["ids","grid","axes","bbox"],"name":"round-2","base64":false}
```

Modes: `textured`, `solid`, `wireframe`, `xray`, `uv`. For `uv`, supply `key` to view an
atlas with labelled face rectangles. Resolution is a square tile size, 32–1024 pixels.
For native inventory assessment render at 32/64 and inspect nearest-neighbor downscales
at 16 as well. Geometry views auto-frame with one shared span. Optional
`camera:{yaw,pitch,ortho,center:[x,y,z]}` overrides that frame; `ortho` is model units across
the tile. `distance` maps a 70° field of view to an equivalent orthographic span; it is **not
perspective projection**. For comparisons lock `camera.center` and `camera.ortho` across rounds.
The `grid` is a screen-aligned model-unit grid; bbox overlays are projected silhouette bounds.
Wireframe and xray show hidden edges; ID labels are diagnostic and may overlap.

Display views `gui`, `firstperson_righthand`, `thirdperson_righthand`, `ground`, `fixed`
apply the model's stored rotation, scale and translation. The hand is a labelled approximate
screen-space guide behind the transformed grip, not a Minecraft player renderer. Lighting,
cutout alpha and cameras are illustrative; no tint/glint/AO, perspective, cullface world context
or Minecraft runtime parity is claimed. Camera overrides replace view orientation.

Comparison expects aligned single-view reference crops (not an entire sheet). It resizes each
reference to the tile; transparent pixels and exact background-key pixels are excluded.
IoU is silhouette intersection/union. Color distance is total variation between normalized
8×8×8 RGB histograms (0 identical, 1 disjoint), not a perceptual or semantic quality score.
No automatic foreground extraction or concept generation is included.

Validation checks the conservative vanilla cuboid schema: coordinates −16..32, positive
volume, one-axis angles −45/−22.5/0/22.5/45, UV 0..16 and quarter-turn face rotation, references
and display bounds. Unlike the old recipe validator, it has **no artificial 64-element cap
or mandatory 2-texels/unit rule**. Empty texture rectangles are warnings. Overlap, possible
z-fighting at coplanar contact and disconnected components use transformed AABBs: conservative
warnings, not exact mesh intersection or proof of floating parts. Intentional bevel intersections
are common. Use xray and visual verification to adjudicate them.

Vanilla export drops editor IDs/groups; models with vanilla builtin/inaccessible parents must
be resolved to explicit cuboids first. Bundle texture names must be installed under matching
resource-pack namespace paths (or rewritten by your pack pipeline). `.bbmodel` export is not
implemented; Blockbench can import the vanilla JSON/PNG bundle.

## Vanilla materials

Choose semantic names from [materials.json](materials.json), for example
`wood.stripped_oak_log`, `metal.gold_block`, `stone.polished_blackstone`,
`gem.diamond_block`, or `cloth.brown_wool`. `material.apply` accepts catalog
names; imports also accept `minecraft:block/...` and `minecraft:item/...`.
Faces use `#slot` references to the model's `textures` map as usual.

```sh
python3 tools/model-forge/forge.py --project /tmp/hammer call material.apply \
  '{"ids":["e0001"],"material":"wood.stripped_dark_oak_log","seed":42}'
```

One local model unit spans one vanilla texel, including fractional units. We keep
this density before display transforms: scaling the entire held item scales its
material texels along with its geometry, as for vanilla items. We do not increase
density to compensate for small GUI/hand/ground scales. Element `rescale` similarly
scales its texels. Log side grain follows the longest local element axis; end faces
use the corresponding log top. Planks follow their horizontal grain. Cuboids over
16 units split into <=16-unit cells, omit internal faces, retain rotation/group and
interpolate unselected external UVs (including quarter turns and mirrors). The
first cell retains the ID, others use `_tileN`; inspect returned IDs before editing
again. Crop offsets depend on seed, original ID, cell and face, never global RNG.
Splits can introduce visible pattern seams; inspect and adjust `uv.set` if needed.

Only render/board/preview and explicit texture inspection read Mojang pixels.
Set `MODEL_FORGE_CLIENT_JAR` to a local client jar (default:
`/tmp/astera-hud-client-26.3.jar`). A missing jar produces an actionable error;
import, scene, measure, validation, history and export still work without it.
Static 16x16 PNGs are extracted on demand into ignored `tools/model-forge/.cache/`,
keyed by jar path/size/mtime. They never enter project images, history, exports or
`content/`. Do not commit the cache, copy Mojang PNGs into a pack, or publish an
HTML viewer with embedded vanilla pixels. Preview boards may show the materials.
Tint-dependent resources and known 26.3 animations (`animated-textures.json`) are
excluded without a jar; additional animations/non-16x16 resources are rejected
when resolving the client jar. Custom painting is reserved for emblems, glow lines
and runes; mix its atlas with vanilla slots in one model. Vanilla slots are read-only.

The pack builder copies model JSON verbatim. `make dev-pack` refreshes the pack
hash after a rebuild; it does not rebuild models or resolve PNG references. In the
26.3 client, `MaterialBaker.bake` tries the item atlas then the block atlas, but
`CuboidItemModelWrapper.validateAtlasUsage` rejects models spanning both atlases.
Install the custom atlas of a block-material model under `astera:blocks/...` /
`content/textures/blocks/`, covered by the builder's existing block atlas source.
For vanilla item materials use `astera:items/...`. Avoid mixing native block and
item sprites in one model: duplicate registration produces 26.3 warnings and is
scheduled for rejection by a future client. Do not duplicate vanilla atlas sources. Forge validation warns about recognized
block/item atlas mixtures; unknown installation namespaces still require client review. The runtime
`ItemModelCatalog` / `PaperItemMaterializer` select `astera:<id>`; texture slots
remain a client concern. See [pilot evidence and reproduction](batches/vanilla-material/review.md).

## Reproduce the recorded session

```sh
python3 tools/model-forge/examples/walkthrough.py --out /tmp/forge-demo
```

This runs real CLI operations, starts a REST server, edits through HTTP, renders, exports and
stops the server. It refuses to overwrite previous evidence. No test runner is used.
[examples/session/evidence.json](examples/session/evidence.json) records final metrics;
[examples/session/transcript.jsonl](examples/session/transcript.jsonl) records all calls/results.
Open [the final board](examples/session/project/renders/final.png),
[the regression comparison](examples/session/project/renders/baseline-diff.png), and
[the deliberately bad paint before undo](examples/session/project/renders/rest-paint.png).
See [the visual review](examples/REVIEW.md) for critic/verifier observations and limitations.

## Display-context finish check

A model-only board is insufficient for hand placement. Render with a player rest
pose, perspective camera, Java left-hand rotation/translation signs, dropped-item
spin and item-frame scale before shipping:

```sh
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/context_preview.py --out /tmp/item-contexts
# Or just selected items:
python3 tools/model-forge/context_preview.py --out /tmp/item-contexts rifle ember-gauntlet
```

This optional preview uses existing skill-viewer three.js/playwright-core packages,
Bun and local Chromium (`CHROMIUM_EXECUTABLE` overrides `/usr/bin/chromium`). It opens
only a loopback page, never Minecraft, and does not change assets. Each item gets
nine screenshots and a labelled board: first-person R/L, third-person R/L plus a
side view, ground at two spin angles, frame and GUI. No external assets or APIs.

Base transforms and left-hand sign handling follow
[Blockbench display mode](https://github.com/JannisX11/blockbench/blob/master/js/display_mode/display_mode.js).
The mannequin uses a 22.5-degree arm rest pose; first-person FOV is 70 degrees.
Lighting, bobbing height and the frame reference are illustrative. Swing/use,
FOV changes, shader packs, glint and exact live-client animation remain client
review items. Do not mistake a thin barrel in an isolated transform view for a
wrong in-game muzzle direction: inspect the player context and side view.

## Interactive all-angle self-review (no Minecraft)

Build a portable HTML snapshot from the **actual authored JSON and PNG atlases**:

```sh
(cd tools/skill-viewer && bun install --frozen-lockfile)
python3 tools/model-forge/context_preview.py --viewer --out tools/model-forge/work/reviewer
# Open tools/model-forge/work/reviewer/viewer.html directly in a browser.
```

The HTML embeds geometry, textures and JavaScript. No server, CDN, credentials,
Minecraft login or network connection is required. Rebuild after changing assets;
it is a snapshot, not a live link. An optional trailing list of item IDs limits
export. Each item's SHA256 covers the resolved model and embedded texture pixels.

Drag to orbit, scroll to zoom, right-drag to pan. Switch between raw model and all
item contexts. Pick a face or named element, isolate/hide it, focus it, or inspect
clay/wireframe/xray. Empty-space clicking clears selection. Top and bottom buttons
reach the OrbitControls pole limit. The FOV slider affects perspective cameras.
Saved PNG and camera JSON have separate download buttons.

Coplanar and <=0.021-unit near-parallel pairs can be selected in two colors.
Detection includes rotations and excludes edge-only contacts/opposite-facing solid
contacts. It does **not** classify visibility, alpha-masked area or intentional
seams; candidate counts are not visible defect counts. Nonparallel intersections
and distances above the threshold require visual inspection. No depth bias,
polygon offset or hidden-face deletion is applied to make the preview look clean.

### Agent captures

```sh
# 26 model directions (8 yaws × 3 elevations + 2 poles), then 9 item contexts.
bun tools/model-forge/review.mjs tools/model-forge/work/reviewer/viewer.html /tmp/model-review

# One model, a deliberately chosen angle and enlarged component.
bun tools/model-forge/review.mjs tools/model-forge/work/reviewer/viewer.html /tmp/rifle-detail \
  rifle --yaw 127 --pitch 18 --zoom 1.5 --element e0001 --mode wireframe

# First-person offhand, without orbiting away from its reference camera.
bun tools/model-forge/review.mjs tools/model-forge/work/reviewer/viewer.html /tmp/offhand \
  rifle --context firstperson_lefthand
```

Captures include `cameras.json` with asset identity, camera, display context,
mode, selection, hidden elements and viewport size, plus a screenshot of the UI.
Open the actual PNGs; a successful batch render is not a visual verdict. A finite
26-view sweep is a starting point, not proof of correctness from every direction.
Use closer views on ambiguous joins, then recapture the same views after an edit.

For browser automation the shared `window.review` API exposes `ready`, async
`load(item, context)`, `setCamera({yaw,pitch,zoom,target?})`, `setFov`, `setMode`,
`select([{id,face?}])`, `frameSelection`, `hide([id])`, `showAll`, `capture`, `state`,
and async `restore(savedMetadata)`. Restore rejects a changed asset hash. Keep the
same viewport dimensions when reproducing a saved image. IDs are e0001-based
source element indices and correspond to Forge imports of that model version.
All operations are read-only; export an edited Forge project to content before
rebuilding the snapshot.

### Choosing a Z-fighting fix

Prefer a single visible surface, not an unconditional inflate pass:

1. Remove a duplicate face or a face completely covered by opaque geometry.
   In Java cuboids, omit its entry from `faces`. Check the back and oblique views;
   alpha cutouts and incomplete coverage can make a deleted face leave a hole.
2. If surfaces overlap only partly, restructure/split the cuboids so their visible
   faces meet at an edge. Preserve UV scale/orientation while changing geometry.
3. Put flat decoration (lines, seams, insignia) in the texture of the base face.
4. Give actual layered ornament deliberate thickness/separation; a tiny offset
   remains useful when layering is intentional, but it is not the only remedy.

Renderer-side polygon offset/depth bias is a standard graphics solution, but
ordinary Java model JSON does not offer a per-face depth-bias control. Applying it
only in this viewer would hide defects without changing the resource pack.
References: [Blockbench tips](https://blockbench.net/wiki/guides/blockbench-overview-tips/)
and [Khronos polygon offset](https://wikis.khronos.org/opengl/Basics_Of_Polygon_Offset).
