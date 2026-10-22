# Edict s2: reference measurements

Source: x6sc9JkW77c, 16.8–23.6s, 854×480 at 29.97 fps. Checked native-rate flip sheets under scratchpad/pm/flip/x6sc9JkW77c/edict-s2. Source audio was muxed without re-encoding into /tmp/edict-reference for the compare tool. Pixel measurements below are approximate readings of 384px-wide flip-sheet tiles (±5px); sprite height excludes the cape. Camera cuts change the pixel scale: use the body in the same frame, never a previous shot.

Range: dash-melee, no ranged damage. Caster movement: approach, close-range swing; upward vault and descent.

| Element / frame time | Measurement and conversion (1 body = 1.8 blocks) | Angle/count/color | Start–end ticks |
|---|---|---|---|
| Dive, 18.333s | burst diameter ~155px / body ~95px = 1.63 bodies; 1.63 × 1.8 = 2.94 blocks | ~12 spikes, white | 29–39 |
| Spin, 20.200s | arc diameter ~215px / body ~80px = 2.69 bodies; radius = 2.69 × 1.8 / 2 = 2.42 blocks | ~160° sweep | 61–77 |
| Finisher, 22.900s | burst diameter ~300px / body inferred ~75px = 4 bodies; radius = 4 × 1.8 / 2 = 3.6 blocks | ~16 uneven spikes; white, ice-blue | 118–130 |
| Ground, 23.000s | ring width ~380px / body inferred ~75px = 5.07 bodies; radius = 5.07 × 1.8 / 2 = 4.56 blocks | flat elliptical ring | 120–130 |

Timing formula: tick = round((absolute video seconds − segment start) × 20).
- Hit at 18.2333s: round((18.2333 − 16.8) × 20) = 29. ±1 tick quantization.
- Hit at 20.1s: round((20.1 − 16.8) × 20) = 66. ±1 tick quantization.
- Hit at 22.7s: round((22.7 − 16.8) × 20) = 118. ±1 tick quantization.

Audio/cut analysis (secondary evidence; speech also causes onsets): {"start_s": 16.8, "end_s": 23.6, "onsets": [{"t": 17.102, "s": 32.37}, {"t": 17.183, "s": 36.53}, {"t": 17.276, "s": 29.17}, {"t": 18.031, "s": 43.31}, {"t": 18.75, "s": 32.75}, {"t": 19.83, "s": 28.06}, {"t": 20.167, "s": 47.86}, {"t": 20.666, "s": 50.12}, {"t": 20.805, "s": 66.58}, {"t": 20.945, "s": 28.74}, {"t": 21.235, "s": 29.44}, {"t": 21.63, "s": 29.73}, {"t": 21.874, "s": 31.73}, {"t": 22.593, "s": 43.29}, {"t": 22.744, "s": 31.11}], "cuts": [{"t": 18.218, "s": 18.1}]}

Design checklist: importance 7. Opaque pale core, dark edge, translucent blue tail; particles at contact. No glowing outlines. Damage only at the coin ticks. Fixed caster-forward contact flash and caster-local rig; exact mob anchoring remains a runtime gap. 59 displays conservative single-target peak (27 sword/fan + 30 impact + 2 mark); no target-following fan. Source timing retained even where it exceeds the general 60-tick art guideline. Client comparison completed below; strict fidelity remains unmet.

## CHECK / ACT — iteration 1
Viewed six pairs at 17.2/18.3/20.2/21.5/22.9/23.4s, archived as `build/pairs/edict-blade-s2-iteration1.png`. Fail: caster coasts beyond the dummy after the aerial dash, so later forward attacks face away from it; no readable impact burst; coarse fan; close camera clips caster. Added explicit zero velocity after the dash (tick 28). Applied the shared narrower, more finely sampled trail and fixed contact-anchor workaround described in S1 notes. Close camera now centers between x=5.4 and x=8. Player remains upright rather than source's inverted airborne pose (player-body animation gap).

Correction after runtime-code review: DashStrikeRunner.finish already zeros velocity. Removed the redundant stop mechanics; the apparent crossing is not proven to be caster coasting. The first capture's dummy drift is measured, so later capture batches explicitly set its movement/follow/attack attributes to zero via RCON, without NoAI (knockback remains enabled), and save attribute/health diagnostics.

## Final CHECK / ACT ledger

Four captures completed. Iteration 2 confirmed damage but showed displaced radial quads. Iteration 3 used centered block bursts; their dark borders dominated. Iteration 4 thickened the white fill, narrowed dark accents, varied ray lengths and used finer glass trails. The final six pairs still FAIL strict fidelity: the first dive/contact flash is late, the airborne body stays upright, the pale burst is mechanical and lacks source bloom, and movement is not fully target-relative. Final dummy health 189.2/200; aggregate damage is not a per-coin event trace.

Final pairs: `pairs-final.png` (also `build/pairs/edict-blade-s2.png`). Four-iteration limit reached; no visual pass is claimed. Source measurements above describe the reference, not proof that the authored geometry matches.
