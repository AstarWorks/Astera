# verdict-hammer-ego: measurement and PDCA

Source Hc6CJXJrzME, 54.3–65.2s, native 30000/1001 fps. Reviewed 4 fps strips and 24/30 fps flip sheets. Measurements are approximate silhouette bounds on 384px-wide tiles, +/- 3 px; antlers/hair excluded from body height. Camera changes prohibit using a body height from a different shot. Video subtitles are not skill labels: slot mapping remains inferred from the supplied wiki coin counts. No ally interlude at 38.7–42s is included.

Range: **AoE at target (field)**. Caster x keys (blocks): [(0,0),(8,0),(16,3),(93,3),(101,6)]. No damage on the positioning dashes; fixed target field stays at cast-time x=8.

| Frame seconds | Element | Body-height ratio | Conversion |
|---|---|---|---|
| 58.400 | Stake shaft height | 75/50 = 1.500 H | 75/50 × 1.8 = 2.700 blocks |
| 58.400 | Field diameter | 350/50 = 7.000 H | 350/50 × 1.8 = 12.600 blocks |
| 58.400 | Crossguard width | 20/50 = 0.400 H | 20/50 × 1.8 = 0.720 blocks |

Palette sampled visually: pale core #FFF4BD, gold #FFD66B, dark hammer #302313; violet #6353AA only on movement. No teal impact sparks (arena lights are not attack effects). Nail pair count = 2; angle about +/-16 degrees from vertical; ring count 16 approximates a continuous circumference. Hammer = haft + dark rectangular head + 4 gold strips. All solid parts brightness 15; bloom alpha .22. Effect center = target feet + (0,1,0); ring y=.12 blocks = .12/1.8 = .067 H.

| Beat | Observed source seconds | Tick formula | Implemented source-clock time |
|---|---|---|---|
| 1 | 57.858 | round((57.858 − 54.3) × 20) = 71 | 54.3+71/20 = 57.850s (t71) |
| 2 | 59.393 | round((59.393 − 54.3) × 20) = 102 | 54.3+102/20 = 59.400s (t102) |
| 3 | 60.692 | round((60.692 − 54.3) × 20) = 128 | 54.3+128/20 = 60.700s (t128) |
| 4 | 63.864 | round((63.864 − 54.3) × 20) = 191 | 54.3+191/20 = 63.850s (t191) |

Nail: summon at impact−16; hover 12 ticks; descend 3 ticks; beam 6 ticks; ring 14 ticks. Hammer: wind-up impact−9 through impact−4, sweep 4 ticks, recovery 14 ticks. Source timing preserved even where edited cinematic duration exceeds art guide 60/100t preference. Hit times quantized to 50 ms (maximum quantization error 25 ms); source still samples 33.367 ms. Musical onsets corroborate impacts but cannot identify damage independently.

## PDCA
- Plan: remove immediate baseline damage and unrelated AoE; use exact wiki hit counts 2/1/3/2; Q has no wiki coin count.
- Do: original display names retained; block hammer rig and fan, solid paired nails, short bloom, dark fragments, real particles, explicit multihit i-frame bypass.
- Check: pending real-client capture.

Native-frame cross-check: S1 arc begins 12.433s, second smear 13.300s, white cut 13.367s; DEF beams 16.967s and contact white 18.467s; S2 beams 36.600s; S3 beams 28.050s, thrust cut 29.383s, airborne hammer preparation 30.717s (no extra coin), terminal smash 31.342s; Q field 56.690s, paired impact 57.858s, sweep 59.393s, hammer flare 60.692s, inverse cut 62.763s, terminal burst 63.864s. Exact server damage moments are quantized approximations to these visual windows, not inferred additional coins.

Audio onset/cut cross-check from muxed cache (absolute seconds):
- Strong onsets: 58.874, 59.594, 60.395, 62.195, 62.961, 63.82
- Shot cuts: 56.69, 57.024, 57.858, 58.058, 58.091, 59.326, 59.393, 59.559, 60.294, 61.061, 61.495, 62.329, 62.629, 62.696, 62.763, 63.83, 63.864

### Iteration 1 — real client, failed comparison
Pairs: build/pairs/verdict-hammer-ego-iteration1.png. Field missing: runtime VfxDefinition rejects duration_ticks 210 (limit 200), although JSON content lint passed. Hammer visible and damage sparks appear. Camera makes actor about half the required size; final face camera points at the old caster position. ACT: delay field play 18t, subtract 18 from its element windows (same world-clock appearance), duration 192; dolly camera to 55% distance and retarget face shot to caster x6. Use midnight/deepslate capture floor to reduce environmental brightness discrepancy. No source material copied into content.

### Iteration 2 — field now loads
Inspected build/pairs/verdict-hammer-ego-round1.png. Field present and damage reactions visible. Crossguards float above shafts: block transform uses centre, not bottom. ACT: shaft centre +1.35 (=2.7/2), crossguard +.4, white shaft with gold guard; improve early close camera; add 16-point dust ring to soften eight-segment polygon without more displays. Shared impact WORLD orientation fixes invisible billboard flashes. Remaining source face/inversion/cloth animation cannot be copied in permitted YAML.

### Iteration 3
Inspected build/pairs/verdict-hammer-ego-round2.png. Damage/ground-ring beats present; field joins corrected (Q). Crossguards are end-on to the side camera, so swap their local x/z extents. Shared flash remains off-frame due glyph-centre calibration; next capture applies negative Y compensation and four-tick full-alpha hold. No exact match claimed: original volumetric radiance, pose distortion, and cinematic overlays differ.

### Iteration 4 (batch round3) — final inspected comparison
Viewed build/pairs/verdict-hammer-ego.png at all seven selected moments. Field now has connected shafts and visible T crossguards, and disappears into the floor in recovery. Remaining: field is too regular/sparse, impacts are pixelated and climb above frame at large scales, source rays/tower/face/inversion are absent. Four captures exhausted; partial match, not accepted as exact. No shared Q effects changed after this capture.
