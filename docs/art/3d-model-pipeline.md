# Astera held-prop pipeline

Adopted 2026-10-23. Designer-side Python only; no Gradle integration or service calls.
The deliverable is a **volumetric held item**, with inventory readability as a secondary constraint.

## Research and decisions

Professional prop workflows separate design, proportion, topology, UV/material work and presentation.
We adopt those checkpoints rather than asking a generator for one finished mesh. Orthographic reference
images remove perspective ambiguity; Blender supports reference images restricted to the relevant
orthographic view ([Blender reference-image documentation](https://docs.staging.blender.org/manual/en/latest/modeling/empties.html)).
Our turnaround therefore contains front (+Z), side (+X), top (+Y) and three-quarter views, all at a locked
scale. The fourth view checks volume, not just the front outline. Concept silhouettes are drawn before
model refinement, from existing weapon-sheet art and the item definition. A short style brief records
material and readability intent. These are construction diagrams, not photorealistic concept paintings.

Blockbench offers cuboids, UV templates, pixel painting and display editing, and its style guide calls
for restrained element counts, coherent pixel scale and deliberate rather than noisy texture marks
([Minecraft style guide](https://blockbench.net/wiki/guides/minecraft-style-guide/)). We use explicit Java
cuboids, readable silhouette cuts, four-tone material ramps, and inspect at native inventory sizes.
The guide's approximately one pixel per model unit is a useful baseline; Astera uses **two** for its
hero held props. The same density across materials matters more than atlas dimensions. Density is
texture pixels divided by face length; excessive density wastes UV area, and inconsistent density
makes objects disagree visually ([Maxon texel-density documentation](https://help.maxon.net/c4d/2025/en-us/Content/html/Texel_Density.html)).

Shared material strips are appropriate for repeated metal bands, grips and shafts. This is the trim-sheet
principle: reuse regions while preserving density, rather than painting a separate large map per face
([Roblox staff trim-texture workflow](https://devforum.roblox.com/t/trim-textures-3d-modeling-and-unwrapping-trim-sheets/3171223)).
Here each item has one atlas: 64x64 for the hammer, 128x128 for the others. Reused patches are intentional;
unique silhouette details are geometry, while grain, wrap rhythm and small highlights are pixels.
No normal maps, PBR shader, baked glow or paid texture service is required. Bright flame pixels are not
vanilla emissive lighting. We do not bake particles into floating geometry.

Blockbench's reference images, pivot controls, z-fighting guidance and display reference models provide
the relevant editor workflow ([Overview & Tips](https://blockbench.net/wiki/guides/blockbench-overview-tips/)).
Import the final JSON as a Java Block/Item model, load its PNG, check each face/rotation, then inspect the
hand, ground and frame slots in Display mode. Keep geometry pivots and display pivots distinct.
Generic mesh export, Bedrock bones and arbitrary mesh triangles are not this asset format.

AI agent integrations such as [MCP for Blender](https://github.com/ahujasid/mcp-for-blender) expose scene
inspection and scripted scene manipulation to an agent. That is useful plumbing, not proof that a
one-shot mesh meets an art brief. Our adopted agent loop is **render -> actually view the image ->
compare to the independent target -> describe a specific defect -> edit -> render again**. Local
numpy/Pillow replaces Blender/MCP here to keep memory and service costs low. No Blender installation,
remote mesh service, or external image API was used. The model, atlas and feedback remain reviewable.

### Version distinction

The founder requested coordinates -16..32, a single rotation axis and angles -45/-22.5/0/22.5/45.
These are enforced as the **Astera conservative authoring profile**, not a claim about all 26.x limits.
Mojang already permitted arbitrary angles from -45 to +45 in 1.21.6
([official release notes, Block Models](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6)).
The repository's ADR-0193 and pack builder are authoritative for our output paths and selection.

## Production sequence and review gates

1. **Brief and target.** Read `content/items/<id>.yaml`, inspect the existing icon and its source-sheet
   cell. Name the signature feature, palette, grip and approximate dimensions. Draw independent
   front/side/top/three-quarter construction polygons. `concept` saves their parameters, YAML hash,
   source context and brief. Lock `concept.json`; never derive a target mask from the candidate model.
   This tool supplies four authored designs, not a general natural-language image model. For another
   item the agent authors a `--design` JSON from that item's brief. Changing the target requires an
   explicit new output directory and a documented reason; do not move it to improve a score.
2. **Blockout.** Two to five large masses, neutral clay. Check total length, head-to-grip ratio, weapon
   category and side depth. Do not spend time on engraving before these read correctly.
3. **Shape.** Add bevels, tapered silhouettes and negative space. Review all four views, especially
   rapier finger clearance and staff crystal supports. Keep joins continuous. A good front silhouette
   cannot compensate for a wafer-thin or over-thick side.
4. **Details.** Add only identifying features: hammer bands/seal, rapier bow/gem, staff fork/core,
   greatsword crest/fuller. Keep bright trim separate from the main mass. Avoid coplanar overlay faces;
   offset overlays or omit covered faces. Inspect the clay renders for holes and disconnected parts.
5. **Texture.** Maintain 2 texels/model unit (5% tolerance), nearest sampling, 0..16 UV space and a
   restrained palette. Build large light/dark regions first, then a few pixel accents. Reinspect the
   actual textured views: a thin face may sample only the brightest strip of a seemingly good atlas.
6. **Polish.** Tune stored display transforms without auto-fit; inspect GUI at **16, 32 and 64 pixels**.
   At 16px the needle or wraps may disappear; preserve class silhouette and palette rather than adding
   more subpixel ornament. This is screen-size simplification, not a runtime LOD system. We ship one
   geometry model, under a 64-element budget, rather than unsupported automatic LOD switching.
7. **Finish.** Run `validate`, inspect the final comparison and inventory panel, then `finalize`.
   Preserve final sheet, target, hashes, round metrics and human/agent review notes. Commit model and
   atlas to content paths. A real client is the remaining release gate for camera/hand interaction.

### Silhouette measurements

Front/side/top/iso share a 36-unit square viewport, center `[8,13,8]`, 256 pixels. No per-image bounding
box fitting, cropping, translation search or scale optimization. `concept.json` holds the camera
contract. The target is rasterized from independent polygon construction data, not cuboid elements.
Flat magenta `#ff00ff` is excluded exactly; candidate occupancy is its alpha-tested z-buffer mask.
IoU = intersecting occupied pixels / union. Overlay: grey overlap, pink target-only, cyan model-only.
Hand/GUI/ground/fixed views have **no target IoU**; their diagnostics are coverage and clipping.

The finish gate is **IoU >= 0.80 for every construction view**, valid schema and density, <=64 elements,
no clipped required display view, no visually disconnected silhouette components, and recognizable
category at 32px. 16px is a stress check, not a promise that tiny ornament survives. Also inspect material
identity, actual source-art resemblance, safe grip placement and stable intersections. A high IoU says
nothing about texture quality. Pillow's polygon edge coverage differs slightly from triangle-center
coverage; very thin blades are sensitive to a single pixel. The 0.80 gate is an Astera working choice,
not an industry standard or a comparison score against the original diagonal icon.

## Runtime mapping and known limits

`content/models/items/<id>.model.json` -> `assets/astera/models/items/<id>.json`.
`content/textures/items/<id>.png` -> `assets/astera/textures/items/<id>.png`.
The JSON texture is `astera:items/<id>`. Existing generated model filenames remain present, so
`addGeneratedItemDefinitions` selects the same-named Studio model per ADR-0193 section 4. No manifest,
item YAML, generated icon, item_model component or Gradle change is needed.

The preview is an opaque/cutout orthographic reference rasterizer, not Minecraft. It implements element
rotation/rescale, Java face UV orientation/rotation, nearest sampling, depth and stored display TRS.
It uses mild illustrative lighting; no AO, glint, tint provider, perspective hand camera, animation,
translucent blending, vanilla built-in mesh generation or engine item-context transforms. Local Astera
parent models and texture aliases are resolved; other parents fail explicitly. JSON Schema covers our
resolved cuboid profile, not every possible modern item definition or special renderer.

Client review must check pack loading, all four item-model overrides, both hands, idle/swing/use poses,
FOV and GUI scale, grip position/occlusion, ground bobbing, item frames, back faces, glint on hammer and
greatsword, and z-fighting under motion. The local previews cannot certify those properties.
