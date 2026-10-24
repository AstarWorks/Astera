---
status: Accepted
date: 2026-10-24
deciders: Claude (model-artist, bastion-greatsword S1 r24)
supersedes: null
superseded_by: null
---

# ADR-0253: Weapon-rig shading marker and the core/item.vsh patch

## Status

Accepted. Reuses the shader-patch mechanism and the light-UV marker idea of
[[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]]; touches the VFX rig of
[[adr/0220-display-choreography-vfx-and-combo-mechanics]].

## Context

A weapon rig (VFX `rigs:` parts, block displays) stands in for the held item during a skill. Reviewers saw the
bastion-greatsword S1 blade turn much darker than the held item when the opponent looks up at the swing
(vfx-reviewer r22 diff #5, animator r23 opponent t10-11: blade face ~ (95, 90, 85) on an iron_block texture of 220).

Read from the unobfuscated 26.3 client jar (`javap`):

- Block displays (`BlockModelRenderState.setupModel` -> `Sheets.cutoutBlockItemSheet` = `RenderTypes.itemCutout`)
  and held items (`BakedQuad.MaterialInfo.itemRenderType` -> `Sheets.cutoutItemSheet` = `itemCutout`) draw through
  the same `item_cutout` pipeline and `core/item.vsh`.
- `VertexConsumer.putBakedQuad` sends the quad's face direction, transformed by the pose's normal matrix, as the
  vertex normal; nothing else shades the face. `item.vsh` then applies `minecraft_mix_light` with the fixed world
  directions `(0.2, 1, -0.7)` / `(-0.2, 1, 0.7)`: brightness = `min(1, 0.6 * (max(0, n.L0) + max(0, n.L1)) + 0.4)`.
  Up faces 1.0, side faces 0.40-0.75 depending on azimuth (0.62 on average), **down faces 0.4**.
- `shade: false` on a model element only sets `MaterialInfo.shadeDirectionOverride`, which is read by
  `BlockModelLighter` (chunk meshing) and nothing in the entity / item path.
- The light level is not the cause: the rig rides the caster as a passenger and samples sky 15 in daylight.

So a held item and a rig in the same pose are lit identically. The rig is darker only because a swing shows the
viewer faces that a held item never points at it: a blade swept above an opponent's eye shows its downward face.

## Decision

One shading rule for a weapon, applied to the rig parts AND the held item it stands in for, in
`content/shader-patches/core/item.vsh.patch`: vanilla lighting, then
`max(vanilla, 0.62 * smoothstep(0.2, 0.7, -n.y))` (0.62 = the azimuth mean of a lit side face; the lift starts
12 deg below horizontal and is full from 44 deg). Two markers select it:

1. **Rig parts.** Every rig part without its own `brightness` is a held-weapon stand-in
   (`VfxElementPlan.weaponRigShading`, `DisplaySpec.weaponRigShading`). The Paper adapter sends the brightness override
   (block 15, sky 14) (`WeaponRigShading`) = light UV `(240, 224)`, outside the skin-rig payloads (`sky & 7 <= 5`).
   The shader lights such vertices with lightmap `(0, 240)` (sky 15: an outdoor held item).
2. **Held item.** The weapon model carries a constant tint `(254, 254, 254)` on every face (`astera_tints`, ADR-0251
   pipeline; `tools/model-forge/work/bastion-greatsword/weapon_shading.py`, called by `build_s1.py`). The shader resets
   that colour to white and keeps the world lightmap.

Because both sides use the same rule, the hand-over cannot pop whatever the angle threshold, which an angle-only rule
on the rig could not guarantee (the held pose already has faces 38-52 deg below horizontal; the S1 contact face is at 49).

Measured (bastion-greatsword S1, evidence `modeler/r24/measurements.txt`): opponent contact blade face 87 -> 134 (+55 %,
220 x 0.40 -> 0.61); compare hand-over held 166 vs rig 162.

## Consequences

### Positive
- A blade swept over the viewer reads like a side face instead of 0.4; rig and held item match in every pose.
- No extra displays, no per-skill content change; other weapon rigs get the rig half automatically.

### Negative / Trade-offs
- The held bastion greatsword itself changes slightly in normal play: faces pointing down are lifted the same way
  (e.g. the lower edge at rest 0.40 -> 0.57). Other weapons keep vanilla shading on the held item until their model is
  marked, so their rig undersides can read up to ~0.2 brighter than the held item's in the same pose.
- In GUI rendering the marked model's downward faces (in GUI space) get the lift too.
- The rig ignores the world light (always sky 15); inside a light-15 source an entity at (15, 14) gets the rule.
- Side faces still vary 0.40-0.75 by azimuth (vanilla, shared by the held item).
- The patch must be re-based when Mojang changes `core/item.vsh` (the pack build fails on a missing anchor).

## Alternatives Considered

- **Display brightness override (15/15)**: fixes darkness from light level only; the light is already 15 in the
  arena, the 0.4 comes from the normal. No effect.
- **ItemDisplay parts with custom item models and `"shade": false`**: same `item.vsh`, same normal lighting;
  `shade` is chunk-only in 26.3. No effect, and 22 extra generated models.
- **Brighter textures on rig parts**: a face that is 0.4 from below is 1.0 from above, so a brighter texture
  clips to white on the top faces and pops at the hand-over.
- **Flat (unlit) rig**: matches nothing; the held item next to it at the hand-over is lit by normal.
- **Lift on the rig only, by angle** (first real-client try, `0.7 * smoothstep(0.6, 1.0, -n.y)`): the contact face
  (n.y -0.75) stayed at 0.40 (measured 87); widening the angle range lifts faces of the held pose and pops the hand-over.

## References

- [[adr/0250-skin-rig-player-animations-display-rig-and-skin-uv-shader]] (shader patches, light-UV marker)
- `docs/contributing/vfx-team/modeler.md` Learnings 2026-10-24 r24
- Evidence: `tools/skill-compare/data/bastion-greatsword-s1/evidence/modeler/r24/`
