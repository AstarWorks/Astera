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
| `import` | `{path, textures?: {resource_reference: png_path}}`; explicit cuboids, local Astera parents, PNGs. Replaces current scene with undo. Texture aliases resolve into independent editable atlases. |
| `scene.get` | `{fields?: ["id","name","group","from","to","rotation","faces","bbox"], root?: group_or_element_id}`; compact element rows, groups, texture keys. Root groups include descendants. |
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
| `texture.get` | `{key, name?, base64?}`; PNG artifact. |
| `texture.palette` | `{key, limit?:32}`; most common RGBA colors with counts. |
| `texture.paint` | `{key, ops:[...]}`; pixel coordinates, Pillow color strings; forms below. |
| `texture.regions` | `{}`; element ID, face, texture key and UV rectangle for every face. |
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
| `export` | `{path:directory}`; `model.json` and `textures/*.png`. Rewrites texture resource references for the bundle. |

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
