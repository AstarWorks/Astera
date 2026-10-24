---
status: Accepted
date: 2026-10-24
deciders: Claude (model-artist, bastion-greatsword S1 r24-r25; director r25)
supersedes: null
superseded_by: null
---

# ADR-0253: Weapon-rig shading marker and the core/item shader patches

## Status

Accepted. Reuses the shader-patch mechanism and the light-UV marker idea of
[[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]]; touches the VFX rig of
[[adr/0220-display-choreography-vfx-and-combo-mechanics]].

## Context

A weapon rig (VFX `rigs:` parts, block displays) stands in for the held item during a skill. Reviewers saw the
bastion-greatsword S1 rig blade change brightness with pose and camera far more than the held item ever does:
near-black when the opponent looks up at the swing (vfx-reviewer r22 diff #5, blade face ~ (95, 90, 85) on an
iron_block texture of 220), near-white when the swing turns the blade face to the sky in the side view (director r24
side t4: 230 vs held 108), near-black again on the compare coil where only the edge shows (33-48).

Read from the unobfuscated 26.3 client jar (`javap`):

- Block displays (`BlockModelRenderState.setupModel` -> `Sheets.cutoutBlockItemSheet` = `RenderTypes.itemCutout`)
  and held items (`BakedQuad.MaterialInfo.itemRenderType` -> `Sheets.cutoutItemSheet` = `itemCutout`) draw through
  the same `item_cutout` pipeline and `core/item.vsh` / `core/item.fsh`.
- `VertexConsumer.putBakedQuad` sends the quad's face direction, transformed by the pose's normal matrix
  (inverse-transpose + normalize, so scaled display transforms are correct), as the vertex normal. The camera rotation
  lives in `ModelViewMat`, so the normal is world-space. `item.vsh` applies `minecraft_mix_light` with the fixed world
  directions `(0.2, 1, -0.7)` / `(-0.2, 1, 0.7)`: brightness = `min(1, 0.6 * (max(0, n.L0) + max(0, n.L1)) + 0.4)`.
  Up faces 1.0, side faces 0.40-0.75 depending on azimuth (0.62 on average), down faces 0.4.
- `shade: false` on a model element only sets `MaterialInfo.shadeDirectionOverride`, which is read by
  `BlockModelLighter` (chunk meshing) and nothing in the entity / item path.
- The light level is not the cause: the rig rides the caster as a passenger and samples sky 15 in daylight.

So a held item and a rig in the same pose are lit identically. The rig looks different only because a swing points
faces at the viewer that a static hold never does (the face down over an opponent, the face up toward a side camera),
and because an edge-on blade shows only its dark rims (polished_blackstone, texture luma 50): that last one is
material, not light. The rig eulers alone reproduce the measured luma (`evidence/modeler/r25/keys.py`).

## Decision

One shading rule for a weapon, applied to the rig parts AND the held item it stands in for, in
`content/shader-patches/core/item.vsh.patch` and `item.fsh.patch`:

1. **Squeeze (vsh).** Marked faces keep vanilla's light direction but the range is compressed toward 0.65:
   `shade = 0.65 + 0.35 * (vanilla - 0.65)`, so 0.57 (face down) .. 0.77 (face up); a side face 0.40-0.75 becomes
   0.56-0.68.
2. **Dark-texel lift (fsh).** Marked faces lift dark texels on luma, hue kept: `y' = y + 0.3 * (1 - y)^2`, applied as a
   scale of rgb (polished_blackstone 50 -> 100, deepslate 73 -> 112, iron / gold / quartz +1-3). A per-channel lift
   was rejected: it greyed the gold guard and the gem.
3. **GUI keeps vanilla.** The rule is skipped under an orthographic projection (`ProjMat[3][3] >= 0.5`), so inventory
   icons keep vanilla shading; the held marker colour is still reset to white there.

Two markers select the rule:

1. **Rig parts.** Every rig part without its own `brightness` is a held-weapon stand-in
   (`VfxElementPlan.weaponRigShading`, `DisplaySpec.weaponRigShading`). The Paper adapter sends the brightness override
   (block 15, sky 14) (`WeaponRigShading`) = light UV `(240, 224)`, outside the skin-rig payloads (`sky & 7 <= 5`).
   The shader lights such vertices with lightmap `(0, 240)` (sky 15: an outdoor held item).
2. **Held item.** The weapon model carries a constant tint `(254, 254, 254)` on every face (`astera_tints`, ADR-0251
   pipeline; `tools/model-forge/work/bastion-greatsword/weapon_shading.py`, called by `build_s1.py`). The shader resets
   that colour to white and keeps the world lightmap.

Because both sides use the same rule, the hand-over cannot pop at any angle.

Measured (bastion-greatsword S1, real client, `lum.py` median blade luma, evidence `modeler/r25/measurements.txt`):
side t0-4 134 / 134 / 139 / 154 / 118 (vanilla 108 .. 230), compare hand-over held 158 vs rig 155 / 152, compare coil
67-76 (vanilla 33-48), no frame t0-12 above 160 or below 66 in either view.

## Consequences

### Positive
- The rig blade keeps one readable brightness band through the swing in every view; rig and held item match in
  every pose.
- No extra displays, no per-skill content change; other weapon rigs get the rig half automatically.

### Negative / Trade-offs
- The held bastion greatsword changes in normal play too (both markers apply in the world): flatter shading (top faces
  1.0 -> 0.77, down faces 0.40 -> 0.57) and lifted dark texels. It reads slightly flatter than vanilla items next to it.
- Other weapons keep vanilla shading on the held item until their model is marked, so their rig can read up to ~0.2
  different from the held item in the same pose.
- The edge-on coil is still dark grey (rims 100 after the lift x shade); bringing it to held level needs a lighter rim
  material or a coil roll that shows the face (model / animation call, not shading).
- The rig ignores the world light (always sky 15); inside a light-15 source an entity at (15, 14) gets the rule.
- The patches must be re-based when Mojang changes `core/item.vsh` / `item.fsh` (the pack build fails on a missing
  anchor).

## Alternatives Considered

- **Display brightness override (15/15)**: fixes darkness from light level only; the light is already 15 in the
  arena, the 0.4 comes from the normal. No effect.
- **ItemDisplay parts with custom item models and `"shade": false`**: same `item.vsh`, same normal lighting;
  `shade` is chunk-only in 26.3. No effect, and 22 extra generated models.
- **Brighter textures on rig parts**: a face that is 0.4 from below is 1.0 from above, so a brighter texture
  clips to white on the top faces and pops at the hand-over.
- **Flat (unlit) rig**: matches nothing; the held item next to it at the hand-over is lit by normal.
- **Lift on the rig only, by angle** (`0.7 * smoothstep(0.6, 1.0, -n.y)`): the contact face (n.y -0.75) stayed at 0.40;
  widening the angle range lifts faces of the held pose and pops the hand-over.
- **Underside-only lift on rig and held** (`max(vanilla, 0.62 * smoothstep(0.2, 0.7, -n.y))`, first shipped rule):
  fixed the opponent's view from below (87 -> 134) but left vanilla's up faces at 1.0, so a blade turned to the sky in
  the side view went near-white (230); edge-on rims stayed near-black. Replaced by the squeeze + dark-texel lift.

## References

- [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]] (shader patches, light-UV marker)
- `docs/contributing/vfx-team/modeler.md` Learnings 2026-10-24 r24-r25
- Evidence: `tools/skill-compare/data/bastion-greatsword-s1/evidence/modeler/r24/`, `.../modeler/r25/`
