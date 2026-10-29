---
status: Proposed
date: 2026-10-29
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0304: Author sprite animation as YAML node graphs

## Status

Proposed. Track `vfx-nodegraph-a`; founder requested YAML authoring on 2026-10-29.

## Context

`tools/item-art` selected one Kotlin function per `draw:` sprite. A new animated
VFX shape therefore required a code change, even though all runtime consumers
only need a horizontal PNG strip, bitmap font, and frame metadata. AsteraStudio
already has a PixelComposer-inspired effects DAG, but its browser UI, WebGL
runtime and stateful preview buffers are unnecessary dependencies for the build.
The geometry scripts also contain useful segment, polygon and symmetry math.

## Decision

Replace `draw:` with a strict, tool-only `graph.nodes` mapping in
`content/textures/sources/sprites.yaml`. Nodes use Studio's taxonomy and parameter
names; image edges name `node.image`, and typed parameter edges use
`{from: node.value}`. Evaluate images as straight float RGBA on the CPU using the
existing Kotlin/SnakeYAML/JVM stack. Scalar, vector and effector outputs carry
typed values. Snapshot imported images at load time. Reject unknown fields,
node types, parameters, ports, type mismatches, duplicate YAML keys, and cycles.
A graph has exactly one output. Keep the resource-pack/runtime output contract
and `from:` sources unchanged; remove the old painter dispatch and decoder.

### Geometry and migration

`shape.field` is a sandboxed arithmetic/SDF node, with ordered variables and
expressions parsed once per node evaluation. It has no sprite-id dispatch,
reflection, script engine, file access or network access. Its coordinate space
is 32 units across, matching the original field math, independently of output
resolution. General-purpose shapes and transforms use image pixel coordinates.
The field's optional four-tone ramp and 2x2 coverage sampling reproduce the
existing antialiasing, while ordinary nodes compose float images.

All 30 `draw:` entries (28 unique painters) are now YAML field graphs. Rendering
all old and new frames yielded zero changed pixels. Before/after contact sheets
and numerical comparison reports live under `build/sprite-graph/`. Committed
sprite PNGs have been regenerated for graph sources; some older committed PNGs
were already stale against the old renderer, so parity is measured against
fresh old-renderer output, not assumed from those PNGs.

Segment distance, regular/step-star polygon paths, even-odd fill, rotation fold,
and dihedral fold from `scripts/geometry/core.py` are available through SDF and
radial/kaleidoscope nodes. `shape.field` can express polar roses, concentric
patterns and other catalog equations without Kotlin changes. The Python pattern
catalog and tooltip script remain consumers in their own right; do not delete
these scripts or redirect tooltip production as part of this task.

### Time and purity

Time is normalized as `frame/(frameCount-1)` for one-shot curves; rotation and
wiggle use seconds at 20 frames/s. `anim.time` now emits a usable scalar, rather
than Studio's blank-canvas placeholder. Trail/feedback recursively evaluate
frame `f-1`, with a transparent frame before zero; stagger re-evaluates delayed
upstream frames. Per-render memoization is only an optimization, never an input.
Animated feedback parameters are evaluated separately at each historical frame.
Random-access previews therefore agree with sequential rendering.

### Preview and licensing

`./gradlew :tools:item-art:run --args='sprite-preview content <id> build/sprite-graph/<id> --nodes'`
produces PNG frames, a contact sheet, a GIF, and per-node images or typed-value
traces. `--compare <baselineDir>` adds comparison sheets and pixel counts.
`write-sprites content` regenerates only sprite outputs, avoiding unrelated
item/model writes. PixelComposer-derived filter math retains the MIT notice in
`tools/item-art/LICENSE-PIXEL-COMPOSER.txt`. `ai.image` is excluded by ADR-0010.

## Consequences

### Positive

- New sprites, animation curves and magic-circle recipes need YAML only.
- Runtime VFX references, glyphs and resource-pack assembly keep their format.
- A single typed registry drives strict loading, the node reference and source schema.
- Shipped-content integration tests cover rendering and backward scrubbing.

### Negative / Trade-offs

- Complex legacy silhouettes have long arithmetic expressions in YAML.
- CPU rendering favors small sprites; float frame caches consume memory for
  large graphs with long feedback histories. No GPU acceleration is introduced.
- Noise and sampling are deterministic CPU implementations; bitwise WebGL parity
  is not a target. GIF previews quantize color/alpha; PNG is authoritative.
- `filter.palette` reuses the existing perceptual palette extractor instead of
  adding a separate Studio median-cut implementation; `colors` remains a count.

### Mitigations

Use small shape/filter graphs for new designs and named field variables for
complex silhouettes. Render previews before committing. Keep generated artifacts
reviewable, and use the shipped loader/renderer integration checks at integration.
The authoring schema is staged in the owned `tools/item-art/` path for the schema
owner to publish under `content/schemas/`; the existing sprite schema describes
runtime metadata and must not be expanded to accept source graphs.

## Alternatives Considered

- **Keep Kotlin painters:** fails YAML-only authoring and keeps per-sprite code.
- **Invoke Studio/Bun/WebGL during Gradle builds:** adds an external runtime and
  inherits stateful frame behavior; this task needs a headless CPU tool.
- **Bake old sprites as imported strips:** preserves pixels but loses editable
  procedural animation and does not migrate their underlying construction.
- **Add a browser node editor:** explicitly outside the requested scope.

## References

- [[adr/0239-custom-particle-sprites-as-textdisplay-glyphs]] (existing output contract)
- [[adr/0010-postpone-generative-ai-to-phase-6]]
- [Sprite graph authoring](../contributing/vfx-team/sprite-graph.md)
- AsteraStudio `EFFECTS.md`, `shared/effects/types.ts`, `app/utils/effects/nodes/`
- `tools/item-art/scripts/geometry/`, `tools/item-art/scripts/geometric-catalog.py`
