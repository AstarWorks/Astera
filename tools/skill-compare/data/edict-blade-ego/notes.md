# Edict ego: reference measurements

Source: x6sc9JkW77c, 35.8–39.2s, 854×480 at 29.97 fps. Checked native-rate flip sheets under scratchpad/pm/flip/x6sc9JkW77c/edict-ego. Source audio was muxed without re-encoding into /tmp/edict-reference for the compare tool. Pixel measurements below are approximate readings of 384px-wide flip-sheet tiles (±5px); sprite height excludes the cape. Camera cuts change the pixel scale: use the body in the same frame, never a previous shot.

Range: self buff / no damaging AoE. Caster movement: stationary thorn transformation.

| Element / frame time | Measurement and conversion (1 body = 1.8 blocks) | Angle/count/color | Start–end ticks |
|---|---|---|---|
| Thorns, 36.700s | spread ~300px / standing body inferred ~90px = 3.33 bodies; 3.33 × 1.8 = 6 blocks | ~8 crooked limbs, near-black | 16–66 |
| Cipher, 36.733s | line ~105px / body ~90px = 1.17 bodies; 1.17 × 1.8 = 2.1 blocks; height above feet ~160/90 × 1.8 = 3.2 blocks | ~8 blue glyphs; original replacement SEALED | 3–52 |
| Bloom, 36.600s | swirl width ~280px / body ~90px × 1.8 = 5.6 blocks | violet/ice-blue | 14–26 |

Timing formula: tick = round((absolute video seconds − segment start) × 20).

Audio/cut analysis (secondary evidence; speech also causes onsets): {"start_s": 35.8, "end_s": 39.2, "onsets": [{"t": 37.472, "s": 49.55}, {"t": 37.588, "s": 45.17}, {"t": 37.704, "s": 79.47}, {"t": 38.192, "s": 52.26}, {"t": 38.668, "s": 53.17}, {"t": 38.807, "s": 35.01}, {"t": 39.028, "s": 49.05}], "cuts": [{"t": 36.47, "s": 14.9}]}

Design checklist: importance 9. Near-black bent limbs, brief translucent violet petals, and a blue original cipher. No glowing outlines and no damage beats for this form-entry action. Final version: 52 displays; breakdown in the final check below. Source timing retained even where it exceeds the general 60-tick art guideline. Client comparison completed below; strict fidelity remains unmet.

## CHECK / ACT — iteration 1
Viewed six pairs, archived `build/pairs/edict-blade-ego-iteration1.png`. Fail: cipher invisible, radial black bars make an oversized vertical mast rather than a low spider web, no violet accents. Replaced radial bars with eight explicitly bent three-segment legs (5.6-block total width, 1.8-block peak height), eight brief violet petal quads, and a blue original cipher. Maximum 33 displays.

Billboard issue also explains missing hit flashes: `orient: yaw/to_target` adds an anchor yaw to camera-facing quads/text, turning them edge-on for the +X caster. Changed billboard impact effects and the transformation to `orient: world`. Kept the physical weapon rig yaw-oriented. This is a content correction, not an engine edit. Re-capture required.

## Final CHECK / ACT ledger

Four captures completed. Iterations 2/3 removed the vertical mast but the depth-overlapping limbs read as two sparse wings, cipher was faint, and violet accents were too small. Iteration 4 varies limb silhouettes, adds eight thorns and three mantle pieces, enlarges the original cipher on a dark backing, and adds eight solid violet shards. Final conservative maximum is 52 displays: 24 limb segments + 8 thorns + 3 mantle pieces + 1 cipher + 8 shards + 8 translucent particles. The six final pairs still FAIL strict fidelity: limbs are angular and disconnected during growth, the cut-in/cipher timing lags, violet shards are not luminous ribbons, and the body remains upright. Dummy health stayed 200/200, as intended for non-damaging entry.

Final pairs: `pairs-final.png` (also `build/pairs/edict-blade-ego.png`). Four-iteration limit reached; no visual pass is claimed. Source measurements above describe the reference, not proof that the authored geometry matches.
