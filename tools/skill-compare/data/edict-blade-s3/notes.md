# Edict s3: reference measurements

Source: x6sc9JkW77c, 30.4–35.2s, 854×480 at 29.97 fps. Checked native-rate flip sheets under scratchpad/pm/flip/x6sc9JkW77c/edict-s3. Source audio was muxed without re-encoding into /tmp/edict-reference for the compare tool. Pixel measurements below are approximate readings of 384px-wide flip-sheet tiles (±5px); sprite height excludes the cape. Camera cuts change the pixel scale: use the body in the same frame, never a previous shot.

Range: dash-melee, no ranged damage. Caster movement: approach, close-range swing; upward vault and descent.

| Element / frame time | Measurement and conversion (1 body = 1.8 blocks) | Angle/count/color | Start–end ticks |
|---|---|---|---|
| Sweep, 31.500s | arc width ~205px / body ~80px = 2.56 bodies; radius = 2.56 × 1.8 / 2 = 2.30 blocks | white/blue, ~160° | 15–31 |
| Recovery fan, 32.100s | blue cape fan ~48px / body ~48px = 1 body; 1 × 1.8 = 1.8 blocks | not an independent hit | 33–40 |
| Vault, 33.800s | ground-to-caster ~130px / body ~50px = 2.6 bodies; 2.6 × 1.8 = 4.68 blocks | diagonal upward then downward | 42–71 |
| Impact, 34.200s | burst ~300px / inferred body ~75px = 4 bodies; radius = 4 × 1.8 / 2 = 3.6 blocks | white spikes + flat ring | 71–83 |

Timing formula: tick = round((absolute video seconds − segment start) × 20).
- Hit at 31.4s: round((31.4 − 30.4) × 20) = 20. ±1 tick quantization.
- Hit at 33.95s: round((33.95 − 30.4) × 20) = 71. ±1 tick quantization.

Audio/cut analysis (secondary evidence; speech also causes onsets): {"start_s": 30.4, "end_s": 35.2, "onsets": [{"t": 30.539, "s": 60.0}, {"t": 30.644, "s": 48.72}, {"t": 30.876, "s": 77.96}, {"t": 31.039, "s": 59.57}, {"t": 31.944, "s": 79.23}, {"t": 32.908, "s": 66.12}, {"t": 33.384, "s": 49.51}, {"t": 33.871, "s": 65.7}, {"t": 34.649, "s": 72.66}], "cuts": [{"t": 33.967, "s": 12.2}, {"t": 34.001, "s": 12.4}]}

Design checklist: importance 8. Opaque pale core, dark edge, translucent blue tail; particles at contact. No glowing outlines. Damage only at the coin ticks. Fixed caster-forward contact flash and caster-local rig; exact mob anchoring remains a runtime gap. 59 displays conservative single-target peak (27 sword/fan + 30 impact + 2 mark); no target-following fan. Source timing retained even where it exceeds the general 60-tick art guideline. Client comparison completed below; strict fidelity remains unmet.

## CHECK / ACT — iteration 1
Viewed six pairs at 31.1/31.4/32.1/33.2/34.0/34.6s (`build/pairs/edict-blade-s3-iteration1.png`). Fail: forward coasting passes the victim; sword trails are detached during the approach; descending rig remains too high; missing matching final burst; upright player silhouette. Added a stop after approach and placed the approach rig at the anticipated landing position (caster forward 5.4) rather than leaving it at the dash start. Shared contact-location and narrow-fan corrections apply. The general rig cannot translate with an airborne articulated body while leaving already-emitted trail geometry stationary; no Kotlin change was made.

Correction after runtime-code review: DashStrikeRunner.finish already zeros velocity. Removed the redundant stop mechanics; the apparent crossing is not proven to be caster coasting. The first capture's dummy drift is measured, so later capture batches explicitly set its movement/follow/attack attributes to zero via RCON, without NoAI (knockback remains enabled), and save attribute/health diagnostics.

## Final CHECK / ACT ledger

Four captures completed. Iteration 2 showed damage and displaced quads. The initial iteration-3 VFX reload failed because block keyframes retained quad-only alpha; the runtime retained the old VFX for that S3 capture. Removed those alpha keys and successfully reloaded before S2 iteration 3. Iteration 4 captured the final centered block burst and finer glass fan. The final seven pairs still FAIL strict fidelity: first contact and the finishing camera cut lag; the final burst is visible at 34.2s but does not reproduce the full-screen white frame at 34.0s; body inversion and cape recovery are absent. Final dummy health 192.14/200.

Final pairs: `pairs-final.png` (also `build/pairs/edict-blade-s3.png`). Four-iteration limit reached; no visual pass is claimed. Source measurements above describe the reference, not proof that the authored geometry matches.
