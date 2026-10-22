# Edict s1: reference measurements

Source: x6sc9JkW77c, 11–14.1s, 854×480 at 29.97 fps. Checked native-rate flip sheets under scratchpad/pm/flip/x6sc9JkW77c/edict-s1. Source audio was muxed without re-encoding into /tmp/edict-reference for the compare tool. Pixel measurements below are approximate readings of 384px-wide flip-sheet tiles (±5px); sprite height excludes the cape. Camera cuts change the pixel scale: use the body in the same frame, never a previous shot.

Range: dash-melee, no ranged damage. Caster movement: approach, close-range swing.

| Element / frame time | Measurement and conversion (1 body = 1.8 blocks) | Angle/count/color | Start–end ticks |
|---|---|---|---|
| Approach, 11.000s | separation ~180px / body ~35px = 5.14 bodies; 5.14 × 1.8 = 9.26 blocks (scene uses 8, approximate) | forward dash begins at 11.133s; round((11.133 − 11) × 20) = 3; two translucent echoes | 3–12 |
| Impact, 12.567s | star diameter ~105px / body ~66px = 1.59 bodies; 1.59 × 1.8 = 2.86 blocks | ~12 uneven spikes, white/ice-blue | 29–39 |
| Sweep, 12.600s | arc diameter ~210px / body 66px = 3.18 bodies; radius = 3.18 × 1.8 / 2 = 2.86 blocks | ~160° inclined sweep, blue-grey tail | 25–36 |
| Second cut, 13.300s | blade ~95px / body ~95px = 1 body; 1 × 1.8 = 1.8 blocks | reverse sweep, pale core / black spine | 39–56 |

Timing formula: tick = round((absolute video seconds − segment start) × 20).
- Hit at 12.4667s: round((12.4667 − 11) × 20) = 29. ±1 tick quantization.
- Hit at 13.2s: round((13.2 − 11) × 20) = 44. ±1 tick quantization.

Audio/cut analysis (secondary evidence; speech also causes onsets): {"start_s": 11.0, "end_s": 14.1, "onsets": [{"t": 11.348, "s": 54.79}, {"t": 11.743, "s": 49.87}, {"t": 12.138, "s": 62.42}, {"t": 12.312, "s": 54.44}, {"t": 12.451, "s": 52.57}, {"t": 12.567, "s": 36.04}, {"t": 12.834, "s": 29.73}, {"t": 13.949, "s": 43.35}], "cuts": []}

Design checklist: importance 4. Opaque pale core, dark edge, translucent blue tail; particles at contact. No glowing outlines. Damage only at the coin ticks. Fixed caster-forward contact flash and caster-local rig; exact mob anchoring remains a runtime gap. 59 displays conservative single-target peak (27 sword/fan + 30 impact + 2 mark); no target-following fan. Source timing retained even where it exceeds the general 60-tick art guideline. Client comparison completed below; strict fidelity remains unmet.

## CHECK / ACT — iteration 1
Viewed `build/pairs/edict-blade-s1-iteration1.png` (six pairs). Fail: fan was a broad polygonal sheet instead of a narrow curved slash; target clipped by the wind-up camera; no world impact at expected contact; second hit timing/readability unclear. Narrowed trail to outer 45% of blade sweep, sampled 24° instead of 60°, removed its redundant solid edge and halved dark-rim width. Recentered close camera on x=6.5, between the actors.

Confirmed engine gap: `PaperPlayerPoseLookup.poseOf` only resolves players. `VfxMechanics` anchors entity effects using that lookup and falls back to caster origin for the husk. Content workaround: fixed caster-forward contact/mark anchors (2.6 blocks; initial S3 mark at 8), with target following disabled. This approximates this measured melee spacing; it is not exact tracking of arbitrary moving mobs. Damage changed to a capped one-target cone for more forgiving melee contact; the previous narrow ray did not visibly damage the dummy. The fixed contact flash also appears on a whiff and must not be treated as proof of damage.

Server entity selectors reported zero displays despite visible sword/fan: these are packet displays, so the attempted server-entity counter cannot verify the live client count. Keep the static conservative bound, not a fabricated measured zero.

## CHECK / ACT — iteration 2
Viewed six pairs (`build/pairs/edict-blade-s1-iteration2.png`). Fan narrower but still faceted; impact quads still not visible; body/camera framing differs. Sampled dummy health remained 200. RCON found its feet at (307.893,100,299.903), caster (305.9,100,300.5), yaw −90°. The 65° cone measures toward feet from the caster eye: atan(1.62/1.993) ≈ 39.1°, outside the 32.5° half-angle. Replaced that selector with `random(count=1, nearby_entities(center=caster_forward(2.6,height=-0.72), radius=1.7, exclude_caster=true))`; this caps displays and tests a chest-height melee contact volume. It does not rely on the ignored max_count field. The dummy also drifted ~0.6 blocks laterally, enough to miss a 0.6-wide ray.

Correction after runtime-code review: DashStrikeRunner.finish already zeros velocity. Removed the redundant stop mechanics; the apparent crossing is not proven to be caster coasting. The first capture's dummy drift is measured, so later capture batches explicitly set its movement/follow/attack attributes to zero via RCON, without NoAI (knockback remains enabled), and save attribute/health diagnostics.

## Final CHECK / ACT ledger

Four captures completed. Iteration 3 made the impact visible but exposed displaced, line-like radial quads. Iteration 4 uses centered filled block rays, narrow dark accents, and an 8-degree glass fan. The final seven pairs still FAIL strict fidelity: the first impact and camera cut are late, the fan is below the source arc, the burst resembles a cog, and player/cape poses are absent. Final dummy health 194.12/200; this confirms damage, not that both scheduled hits landed. Earlier iteration 3 ended at 188.19199; contact consistency remains unresolved.

Final pairs: `pairs-final.png` (also `build/pairs/edict-blade-s1.png`). Four-iteration limit reached; no visual pass is claimed. Source measurements above describe the reference, not proof that the authored geometry matches.
