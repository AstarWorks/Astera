# Model Forge agent round protocol

Use `python3 tools/model-forge/forge.py --project <dir> call <operation> '<JSON>'`
or the identical REST/MCP operation. Read README.md for arguments. Never edit project.json
behind the tool: history and validation must describe every model/texture change.

1. **Observe and checkpoint.** `scene.get` (request `faces` when needed), `scene.element`
   for the IDs you will touch, `measure`, `validate`, then `history.snapshot` with a unique
   round name. Resolve errors; record intentional overlap warnings rather than hiding them.
2. **Look.** `board` with all eight canonical views; inspect the returned PNG using your
   image-reading tool. Also inspect GUI, both hand contexts, ground and fixed at least once
   each round. Use `highlight`, wireframe/xray, and `mode:uv` for ambiguous joins or UVs.
   A successful render call without opening the image is not a visual review.
3. **Compare against the locked image-generated concept.** Pass its matching per-view
   crops to `compare.references`. Use transparent or explicitly keyed backgrounds, fixed
   framing and matching cameras. Keep the original concept unmodified. Do not substitute
   a render of your own model as the design target. The bundled baseline comparison is
   regression evidence only; a single perspective concept cannot judge all eight views.
4. **CRITIC.** Write yes/no/unknown answers with visible evidence and element IDs:
   - Is the silhouette faithful in every supplied concept view?
   - Are the major part lengths and ratios within the brief's exact tolerances?
   - Do bevel steps and ornament pieces read in front, side and oblique views?
   - Are joins intentional, with no unintended floating pieces or visible fighting faces?
   - Are UV orientation, seams, palette and texture coverage correct?
   - Does the item read at 16/32/64-pixel inventory scale and fit its display contexts?
   Propose **at most three** concrete fixes: affected IDs, current defect, numeric edit,
   expected visible change, and the measurement/view that will confirm it.
5. **Edit.** Apply one `batch` for those fixes. Read its diff and validation warnings.
   A failed batch changes nothing. Do not change the concept/camera to improve a score.
6. **Look again.** Re-render the same board/cameras and compare. Open the actual PNGs.
7. **VERIFIER, separate from the critic's proposal.** Inspect `history.diff`, `measure`,
   `validate` and the new images. For each proposed fix explicitly answer: did the requested
   property change, did the visible defect improve, and did another view regress? Do not
   infer success from API success, a high IoU, or the generator's explanation. If worse,
   `history.restore` the round snapshot and verify restoration with a fresh board.
8. **Stop.** Export only when the checklist passes and all unknowns are resolved, or stop
   with an honest incomplete report on a two-round plateau or the default six-round cap.
   Preserve snapshots, critic/verifier notes, concept identity, comparison metrics and images.

Detail targets for a finished hero prop: **80–200 elements** and deliberate stepped
bevels/ornament; never add invisible cubes to meet a target. Texture faces by
**material**, choosing semantic names from `materials.json` via `material.apply`.
Reference vanilla `minecraft:block/...` / `minecraft:item/...` resources in JSON;
never copy Mojang PNGs into the public repo or shipped pack. Use one local model
unit per vanilla texel, crop offsets varied by face and grain along the long axis.
Faces over 16 units must tile through split geometry; inspect split seams. Display
transforms scale material pixels with the model (no compensating density increase).
Use custom-painted atlases only for emblems, glow lines or runes with no vanilla
material equivalent; one model can mix both. Exclude tinted and unverified animated
textures. The client jar/cache is preview input only; never export cached PNGs.
See README for `MODEL_FORGE_CLIENT_JAR`, read-only materials and the density rule.

Architecture references: [BlenderMCP](https://github.com/ahujasid/mcp-for-blender) for compact
state query and visual inspection; [Blockbench MCP](https://jasonjgardner.github.io/blockbench-mcp-plugin/)
for explicit element/texture actions and undo. The founder's supplied LL3M/BlenderGym,
ArtisanCAD, CADSmith and CADCodeVerify research informed the critic/verifier separation,
eight-view board, exact measurements and binary visual checklist. This tool supplies the
observations and edits; the calling agent performs the visual judgments.

## Display contexts are part of completion

Do not finish at an attractive mesh. Run `context_preview.py` (README) and actually
open its boards for every changed item. Check both hands in first/third person,
model-space grip versus the hand anchor, muzzle/finger direction, a clear screen
center, dropped-item size and spin pivot, and frame centering/depth. The Java
left-hand signs and player base rotation must be included; isolated TRS previews
cannot establish these properties. Keep 16/32/64px inventory review as well.
Record client-only animation/FOV questions explicitly; do not claim an offline
board proves live-client behavior. Preserve before/after evidence with the assets.

## All-angle evidence and surface cleanup

Build the standalone reviewer (`context_preview.py --viewer`) from the current
assets, run `review.mjs`, and open its actual captures. The 26-direction sweep and
9 contexts are an initial screen, not the final verdict. Use arbitrary yaw/pitch,
zoom, element/face selection, isolation and xray on every ambiguous join; save
camera metadata and compare the same camera after edits. Raw model view supplies
free orbit; a rotated hand context is no longer the in-game camera. Never count
rendered but unopened images as reviewed evidence.

Treat coplanar/near-parallel detections as candidates, including hidden faces.
For Z-fighting choose duplicate/covered-face removal, cuboid restructuring or
texture-only detail before a generic inflate pass. Use offsets for intentional
layers, with an explicit reason. Do not use viewer depth bias to suppress defects.
Do not auto-delete partially covered or alpha-cutout faces. Review backside,
underside and edge views after cleanup. Record unresolved questions, and retain
the distinction between an offline geometry review and live-client rendering.
