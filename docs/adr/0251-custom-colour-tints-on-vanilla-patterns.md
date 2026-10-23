---
status: Proposed
date: 2026-10-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0251: Custom colour tints on vanilla patterns

## Status

Proposed (founder decision recorded; manager review pending).

## Context

Track `vm-tint`. The founder decided on 2026-10-24: “Patterns stay vanilla-based;
colour may be fully custom, as much as we like.” This supersedes the 2026-10-23
restriction that only emblems/runes may use custom colour. The public repository
must never contain Mojang PNGs, including recoloured or derived Mojang PNGs.
Vanilla material substitution alone had turned the greatsword's blue-steel blade
gray and its crimson guard copper. The model needs independent pattern and colour.

## Decision

Use the native item-model-definition constant tint mechanism verified in the 26.3
client jar. Forge's `material.apply` takes optional `tint: "#rrggbb"`, interns equal
RGB values into the model's `astera_tints` array and assigns its zero-based index
to selected faces' `tintindex`. Omitting/nulling tint clears it on selected faces;
unselected faces retain it through splits. Keep the array in project history and
exported model JSON. The pack builder strips this authoring field from cuboid JSON
and writes `minecraft:constant` tint sources into the selected item's definition,
both for manifest entries and authored overrides of generated icons. The runtime
`ItemModelCatalog` and `PaperItemMaterializer` continue selecting `astera:<id>`;
they do not carry colour or texture data. Offline raster and player-context
previews multiply texture channels by the same RGB values. No derived texture
asset is exported, shipped or committed. Patterns continue to reference vanilla
resources; custom-painted emblems/runes remain available under the existing rule.

## Consequences

### Positive

- Arbitrary static RGB colours on vanilla patterns, with per-face selection.
- One palette travels with the model; no sidecar discovery or runtime plugin API.
- Real-client evidence uses item definitions emitted by the actual pack builder.
- No compatibility layer or deprecated schema during pre-release.

### Negative / Trade-offs

- Multiplication only darkens: black stays black and saturated bases restrict hue.
- Constant RGB is opaque; this does not provide emission, biome tint or animation.
- Preview lighting remains illustrative; native client capture is required.
- Dark-base warnings use known resource names, not jar-dependent pixel analysis.

### Mitigations

Use iron_block, smooth_stone, white_concrete, white_wool, quartz or calcite as light
bases; preserve pattern contrast. Reject invalid RGB values and out-of-table face
indices in authoring and pack building. Keep extracted vanilla pixels and tinted
preview buffers in ignored local caches/build output; commit only review boards
and raw screenshots, never texture sprites or embedded-pixel HTML viewers.

## Alternatives Considered

- **Recolour PNGs at pack build time from the client jar:** unnecessary because
  native cuboid tints work; adds a texture-generation pipeline and jar dependency.
  This fallback would require a separate manager decision.
- **Restrict palette to existing vanilla blocks:** loses the founder's intended
  colour identity and contradicts the 2026-10-24 decision.
- **Palette sidecar:** separate file can drift from face indices; inline metadata
  keeps history, import, export and installation atomic.

## References

- `net.minecraft.client.renderer.item.ItemModels.bootstrap`: registers `model` with
  the cuboid wrapper codec.
- `net.minecraft.client.renderer.item.CuboidItemModelWrapper$Unbaked.MAP_CODEC`:
  `ItemTintSources.CODEC.listOf().optionalFieldOf("tints", List.of())`; no fixed
  tint-count cap in this codec.
- `net.minecraft.client.color.item.ItemTintSources.bootstrap`: registers `constant`.
- `net.minecraft.client.color.item.Constant.MAP_CODEC`: `ExtraCodecs.RGB_COLOR_CODEC`
  field `value`; constructor forces opaque alpha.
- `net.minecraft.client.resources.model.cuboid.CuboidFace$Deserializer.getTintIndex`:
  integer `tintindex`, default -1. `BakedQuad.MaterialInfo` stores it as an int.
- `net.minecraft.client.renderer.item.CuboidItemModelWrapper.update`: evaluates
  every source into tintLayers; `ItemFeatureRenderer.getLayerColorSafe` selects
  the indexed colour, falling back to white for missing/out-of-range indices.
  Authoring requires -1 or 0..palette-size-1 rather than silently losing colour.
- `assets/minecraft/shaders/core/item.fsh`: multiplies sampled texture colour by
  vertexColor and ColorModulator.
- [Forge documentation](../../tools/model-forge/README.md#vanilla-materials)
- [Greatsword evidence](../../tools/model-forge/batches/vanilla-material/greatsword/review.md)
- [Resource-pack assembly](0182-resource-pack-pipeline-and-hp-status-hud.md)
