# Dagger palette restoration — ADR-0251

Restores the original muted blue-gray steel and brown leather from `36743b70`.
The reference atlas is unchanged since that commit. Its sampled colours include
steel `#60727c` / `#91a5ac`, edge `#a0b4bc`, leather `#453026` / `#72503a`
and stitching `#211919`. Tints compensate for multiplication by the light base;
they are not literal replacements for the sampled atlas pixels.

| Parts | Vanilla pattern | Tint |
|---|---|---|
| Tapered blade sections | Iron block | `#a0b5bd` |
| Ground cutting bevels | Iron block | `#b9d0da` |
| Central ridge | Iron block | `#708894` |
| Guard, collars, pommel facets, grip pins | Iron block | `#6e848f` |
| Grinding marks | Iron block | `#8098a3` |
| Leather grip | White wool | `#674737` |
| Leather lap bands | White wool | `#503a2e` |
| Leather stitches | White wool | `#281e1e` |

The first vanilla pass's white blade, black anvil fittings and gold pins become
cool steel again. The darker ridge and brighter bevels keep the compact dagger
readable without borrowing a sibling's saturated accent palette. Tinted white
wool supplies a quiet wrap pattern. Catalog wish: a less fleecy light leather
pattern; no custom texture or Mojang sprite is added.

`remake.py` imports the original palette reference for `before.png`, then imports
the current model for edits. All 100 cuboids, all 600 faces and the current display
block match HEAD exactly; only materials, UVs and tints change. No splits are
needed. The blade's north/south faces share a continuous longitudinal iron crop,
retaining each part's tint. Eight deduplicated colours tint all 600 faces.

Reviewed `before.png`, `after.png`, `contexts.png`, `inventory.png`,
`player-contexts.jpg` and `angles.jpg`. The eight canonical views, 26-direction
sweep, both hands, ground/spin, frame and GUI retain steel/brown separation.
The 16px icon retains blade/edge/grip contrast; stitches become subpixel.
Geometry and display assertions, identical export/import, zero exported PNGs and
one-texel/unit density are recorded in `verification.json`. Forge reports zero
errors and the unchanged 225 overlap / one coplanar-contact / one disconnected
component warnings. Existing bevel construction is retained; this is not a
complete geometry or animation audit.

Reproduction (fresh project directory, repository root; no Gradle/tests):

```sh
git show 36743b70:content/models/items/dagger.model.json > build/dagger-original.json
python3 tools/model-forge/batches/vanilla-material/dagger/remake.py \
  --project build/dagger-tint-replay --before build/dagger-original.json
python3 tools/model-forge/context_preview.py --viewer --out build/dagger-viewer dagger
bun tools/model-forge/review.mjs build/dagger-viewer/viewer.html build/dagger-sweep dagger
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/dagger/capture-client.py \
  --world-source '<isolated superflat save directory>'
```

The local viewer's embedded vanilla pixels stay ignored under `build/`.
`capture-pack.py` follows the current builder's output: it removes `astera_tints`
from the cuboid JSON and emits eight `minecraft:constant` sources in the item
definition, preserving face indices. This is a minimal builder-equivalent pack,
not a production ZIP build. The native client resolves iron/wool from its block
atlas; no vanilla texture PNG is copied into the pack.

Native capture uses portablemc 26.3, a dynamic Xvfb display, llvmpipe, 1280×720,
FOV 70, view bob off, GUI scale 2, peaceful/noon/clear, position 0/-60/3, yaw/pitch
0/0. The entire launch/capture/shutdown holds the shared lock. Only the copied
superflat in `build/dagger-client` is changed; commands set creative mode and equip
`minecraft:iron_sword[minecraft:item_model="astera:dagger"]`.

Opened `client.png`: cool steel blade, darker ridge, bright cutting edges, steel
fittings and brown wrap render correctly, including the hotbar icon. The capture
pack model and tint sources match the authored palette. `client-log.txt` contains
no model-loading, missing-texture or mixed-atlas errors. Offline authentication,
unavailable audio and `minecraft:end_of_frame` post-effect messages are unrelated
to this model. The held grip continues below the screen under the unchanged
HEAD display transform.

Remaining limits: static multiplication cannot brighten or emit light. White wool
approximates leather at this scale. Native evidence covers first person/hotbar;
other contexts use illustrative offline lighting. Swing/use, glint, shaders and
other FOVs remain unverified. Production pack/deployment remain manager work.
No Gradle, unit/integration tests, E2E, Docker, external server or push was run.
