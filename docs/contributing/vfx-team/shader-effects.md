# Shader effects: add an effect in 3 steps

Effects are local to an authored pack mesh display. Existing skin/weapon rigs keep their own reserved markers. Start by copying `content/shader-patches/effects/emissive-pulse/`.

1. Create `content/shader-patches/effects/<id>/effect.yaml`. Set `id` to the directory name, `programs: [item]`, a unique `vfx-tint` RGB decimal, and numeric `constants`. Inspect other manifests first; all marker claims are exclusive, including weapon tint `16711422`. The VFX light pair block 13 / sky 11 is reserved in `ShaderEffect`.
2. Write `item.vsh.patch` and `item.fsh.patch` with `@@ hook: <name>`. `hooks.yaml` maps names to unique vanilla anchors; authors normally need only `declarations`, `shade`, `light` and `surface`. Copy the marker decoder and choose free matching varying locations in both stages. `${MARKER_R}`, `${MARKER_G}`, `${MARKER_B}`, `${BLOCK_UV}`, `${SKY_UV}` and `${CONSTANT_NAME}` expand at pack build. Prefix GLSL names per effect. Execute alpha/discard logic in OIT alpha-only passes too; lighting outputs exist only outside `OIT_ALPHA_ONLY`.
3. Add `shader_effect: <id>` to a `mesh` op, then build and film:

   ```yaml
   # Inside presentation.timeline[].do:
   - {id: aura, op: mesh, frame: surface, item: iron-warden-chestplate, scale: 2, shader_effect: emissive-pulse}
   ```

   ```sh
   ./gradlew :tools:resource-pack:buildResourcePack
   make vfx-shot SKILL=demo-shader-emissive-pulse VIEW=side TICKS=100
   ```

Playable VFX demos in `content/vfx/` use the same Skill YAML schema as `content/skills/`. They are loaded by the same runtime and can be cast/filmed by id. Duplicate skill/demo ids and unknown shader ids fail content loading. Reloading the registry changes id validation; changing markers/constants/snippets also requires rebuilding and reloading the pack.

## First effects

| id | Look | Constants |
|---|---|---|
| `dissolve` | looping noise erosion with orange edge | `PERIOD_SECONDS`, `CELL_PIXELS` |
| `uv-flow` | cyan bands flowing over texture UVs | `SPEED`, `BAND_PIXELS` |
| `emissive-pulse` | unlit colour boost pulsing smoothly | `PERIOD_SECONDS`, `BOOST` |

Time is `GameTime * 1200.0` seconds, synchronized to the world. Casts do not restart the phase. `uv-flow` changes a procedural mask, not texture lookup UVs: ordinary item textures live in an atlas and scrolling across its boundaries samples other items. Emissive is a colour boost, with no additive blending, light emission onto neighbors or bloom.

## Marker and model boundaries

The builder writes `assets/astera/shader-effects/markers.json` into the pack. Duplicate claims fail loudly; light wildcard claims overlap their whole sky range. The reserved VFX channel is **exact face tint AND brightness (13,11)**. Tint alone is restored to white only after both match. SKL-03 / ADR-0298: natural light can forge any 0–15 light value, so never remove the tint confirmation. A custom model carrying the exact reserved tint under the same light can still collide; do not use these colours as regular model tints.

Effects support generated/handheld item parents and Astera cuboid models. The builder clones model geometry and forces face tint index 0 in the effect variant, then writes a constant-tint item definition. This intentionally replaces prior material tints only for that effect surface. Original models, held items and rig encodings are untouched. Special skull/block parents, composite item definitions, `minecraft:` meshes, `main_hand`, plates and lines are outside this first version.

The builder composes real skill/VFX YAML and generates only referenced combinations under `assets/astera/items/shader-effects/<effect>/<item>.json`. An unsupported model reference fails the build. No files under `content/models/` need editing to add an effect. Do not allocate a location already used by another snippet (current VFX locations 12–14; weapon 7; skin rig 8–11).

`ShippedShaderEffectsPackTest` builds the real pack, checks the ledger and shader composition, preserves the skin-rig baseline and verifies demos plus unknown-id/duplicate-marker rejection. Workers compile it; the manager runs it in the integration window.

## Capture integration note

The current `vfx-shot` warm-stack pack fingerprint predates mesh-reference-driven variants. Until the QA owner adds `skills`, `vfx` and `frames` to `tools/vfx-shot/shotstate.py`'s pack inputs, run `make vfx-shot-down` before filming a changed model/effect reference. A cold shot always rebuilds the pack. The Gradle pack task already tracks all three directories.

## Sprite shader combinations (Minecraft samples)

The three `content/skills/sample-*/` shader demos use double-sided, four-block
item meshes from `content/pack-assets/assets/astera/models/vfx-samples/`.
They reference the same graph-generated horizontal sprite strips; the adjacent
`.mcmeta.json` supplies 64×64 frames 0–7 at one tick per frame. The item atlas
includes only the animated sprite entries (single sources), rather than uploading
all plate-only strips. Unlike plate flipbooks, atlas animation phase is
client-global and does not restart on cast. Meshes face the side capture camera
(fixed 90° yaw); they are not viewer billboards.

| sample | shader_effect | Meaning |
|---|---|---|
| `sample-dissolve-sigil` | `dissolve` | rotating sigil plus noise erosion |
| `sample-energy-orb` | `emissive-pulse` | animated orb plus pulsing unlit colour boost |
| `sample-heat-haze` | `uv-flow` | animated heat ribbons plus flowing cyan bands |

The orb's painted ring is not a view-dependent rim shader. Heat haze does not
refract or distort the background. These use the three existing ADR-0305 effects;
no new marker or screen-reading shader is needed. The gallery displays effect ids
and the real-client side sheets from `build/vfx-shot/<sample>/side/sheet.png`.
