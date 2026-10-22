# verdict-hammer-def: measurement and PDCA

Source Hc6CJXJrzME, 15.8–19.8s, native 30000/1001 fps. Reviewed 4 fps strips and 24/30 fps flip sheets. Measurements are approximate silhouette bounds on 384px-wide tiles, +/- 3 px; antlers/hair excluded from body height. Camera changes prohibit using a body height from a different shot. Video subtitles are not skill labels: slot mapping remains inferred from the supplied wiki coin counts. No ally interlude at 38.7–42s is included.

Range: **AoE at target then dash-melee**. Caster x keys (blocks): [(0, 0), (37, 0), (47, 3.5)]. No damage on the positioning dash. Q's movement is approximated by a stationary caster; see report.

| Frame seconds | Element | Body-height ratio | Conversion |
|---|---|---|---|
| 17.675 | Nail V height | 100/75 = 1.333 H | 100/75 × 1.8 = 2.400 blocks |
| 17.675 | Ring diameter | 290/75 = 3.867 H | 290/75 × 1.8 = 6.960 blocks |
| 18.050 | Hammer head width | 40/72 = 0.556 H | 40/72 × 1.8 = 1.000 blocks |

Palette sampled visually: pale core #FFF4BD, gold #FFD66B, dark hammer #302313; violet #6353AA only on movement. No teal impact sparks (arena lights are not attack effects). Nail pair count = 2; angle about +/-16 degrees from vertical; ring count 16 approximates a continuous circumference. Hammer = haft + dark rectangular head + 4 gold strips. All solid parts brightness 15; bloom alpha .22. Effect center = target feet + (0,1,0); ring y=.12 blocks = .12/1.8 = .067 H.

| Beat | Observed source seconds | Tick formula | Implemented source-clock time |
|---|---|---|---|
| 1 | 17.008 | round((17.008 − 15.8) × 20) = 24 | 15.8+24/20 = 17.000s (t24) |
| 2 | 18.467 | round((18.467 − 15.8) × 20) = 53 | 15.8+53/20 = 18.450s (t53) |

Nail: summon at impact−16; hover 12 ticks; descend 3 ticks; beam 6 ticks; ring 14 ticks. Hammer: wind-up impact−9 through impact−4, sweep 4 ticks, recovery 14 ticks. Source timing preserved even where edited cinematic duration exceeds art guide 60/100t preference. Hit times quantized to 50 ms (maximum quantization error 25 ms); source still samples 33.367 ms. Musical onsets corroborate impacts but cannot identify damage independently.

## PDCA
- Plan: remove immediate baseline damage and unrelated AoE; use exact wiki hit counts 2/1/3/2; Q has no wiki coin count.
- Do: original display names retained; block hammer rig and fan, solid paired nails, short bloom, dark fragments, real particles, explicit multihit i-frame bypass.
- Check: pending real-client capture.

Native-frame cross-check: S1 arc begins 12.433s, second smear 13.300s, white cut 13.367s; DEF beams 16.967s and contact white 18.467s; S2 beams 36.600s; S3 beams 28.050s, thrust cut 29.383s, airborne hammer preparation 30.717s (no extra coin), terminal smash 31.342s; Q field 56.690s, paired impact 57.858s, sweep 59.393s, hammer flare 60.692s, inverse cut 62.763s, terminal burst 63.864s. Exact server damage moments are quantized approximations to these visual windows, not inferred additional coins.

Audio onset/cut cross-check from muxed cache (absolute seconds):
- Strong onsets: 16.23, 16.601, 17.112
- Shot cuts: 16.984, 18.485

Native-frame refinement (supersedes initial timing table): {25: 24, 54: 53, 9: 8, 45: 44} ticks. Updated case hit markers and YAML together.

### Iteration 1 — real client
Inspected build/pairs/verdict-hammer-def-round1.png. Correct visible paired descent / hurt beats, but actor too small in wide shots, columns nearly invisible, contact flash absent, and melee fan coarse. Planner inspection shows billboard quads retain anchor rotation, turning a yaw-oriented quad edge-on from the side. ACT: world-oriented billboard impact; solid white column plus glass bloom (same four entities); dolly wide cameras to 9–10 blocks; denser two-band fans. DEF/S3 terminal ground ring added; S3 preparatory hammer shortened to avoid two held hammers at terminal contact. All changes YAML only.

### Iteration 2
Inspected build/pairs/verdict-hammer-def-round2.png. Damage/ground-ring beats present; field joins corrected (Q). Crossguards are end-on to the side camera, so swap their local x/z extents. Shared flash remains off-frame due glyph-centre calibration; next capture applies negative Y compensation and four-tick full-alpha hold. No exact match claimed: original volumetric radiance, pose distortion, and cinematic overlays differ.

### Iteration 3 review / final correction
Real-client round3 pairs inspected. Impact centering and visible nail crossguards improved. Remaining fan interior shows overlapping rectangular facets; reduced inner-band alpha from 0x38/255=0.220 to 0x18/255=0.094. Nail recovery incorrectly returned upward: explicitly hold the t29 translation at t38 while shrinking. Round4 checks these content fixes. Player body pose, volumetric glow and source scenery remain unmatched.

### Iteration 4 — final
Viewed build/pairs/verdict-hammer-def.png (six moments). Nail-hit then hammer-hit sequence and dash are present. Hover shot clips the upper nails; late nails remain elevated; final enlarged star is cropped above the victim rather than filling the source screen with radiance. Recovery lacks the source prone body and lingering light ring. Four-iteration limit reached, partial match.
