# verdict-hammer-s1: measurement and PDCA

Source Hc6CJXJrzME, 10.9–14.6s, native 30000/1001 fps. Reviewed 4 fps strips and 24/30 fps flip sheets. Measurements are approximate silhouette bounds on 384px-wide tiles, +/- 3 px; antlers/hair excluded from body height. Camera changes prohibit using a body height from a different shot. Video subtitles are not skill labels: slot mapping remains inferred from the supplied wiki coin counts. No ally interlude at 38.7–42s is included.

Range: **dash-melee**. Caster x keys (blocks): [(0, 0), (7, 0), (19, 5.5)]. No damage on the positioning dash. Q's movement is approximated by a stationary caster; see report.

| Frame seconds | Element | Body-height ratio | Conversion |
|---|---|---|---|
| 12.500 | Crescent diameter | 300/85 = 3.529 H | 300/85 × 1.8 = 6.353 blocks |
| 12.500 | Bright band thickness | 15/85 = 0.176 H | 15/85 × 1.8 = 0.318 blocks |
| 12.200 | Hammer length | 80/75 = 1.067 H | 80/75 × 1.8 = 1.920 blocks |

Palette sampled visually: pale core #FFF4BD, gold #FFD66B, dark hammer #302313; violet #6353AA only on movement. No teal impact sparks (arena lights are not attack effects). Nail pair count = 2; angle about +/-16 degrees from vertical; ring count 16 approximates a continuous circumference. Hammer = haft + dark rectangular head + 4 gold strips. All solid parts brightness 15; bloom alpha .22. Effect center = target feet + (0,1,0); ring y=.12 blocks = .12/1.8 = .067 H.

| Beat | Observed source seconds | Tick formula | Implemented source-clock time |
|---|---|---|---|
| 1 | 12.433 | round((12.433 − 10.9) × 20) = 31 | 10.9+31/20 = 12.450s (t31) |
| 2 | 13.300 | round((13.300 − 10.9) × 20) = 48 | 10.9+48/20 = 13.300s (t48) |

Nail: summon at impact−16; hover 12 ticks; descend 3 ticks; beam 6 ticks; ring 14 ticks. Hammer: wind-up impact−9 through impact−4, sweep 4 ticks, recovery 14 ticks. Source timing preserved even where edited cinematic duration exceeds art guide 60/100t preference. Hit times quantized to 50 ms (maximum quantization error 25 ms); source still samples 33.367 ms. Musical onsets corroborate impacts but cannot identify damage independently.

## PDCA
- Plan: remove immediate baseline damage and unrelated AoE; use exact wiki hit counts 2/1/3/2; Q has no wiki coin count.
- Do: original display names retained; block hammer rig and fan, solid paired nails, short bloom, dark fragments, real particles, explicit multihit i-frame bypass.
- Check: pending real-client capture.

Native-frame cross-check: S1 arc begins 12.433s, second smear 13.300s, white cut 13.367s; DEF beams 16.967s and contact white 18.467s; S2 beams 36.600s; S3 beams 28.050s, thrust cut 29.383s, airborne hammer preparation 30.717s (no extra coin), terminal smash 31.342s; Q field 56.690s, paired impact 57.858s, sweep 59.393s, hammer flare 60.692s, inverse cut 62.763s, terminal burst 63.864s. Exact server damage moments are quantized approximations to these visual windows, not inferred additional coins.

Audio onset/cut cross-check from muxed cache (absolute seconds):
- Strong onsets: 11.608, 11.898, 12.351, 12.618, 13.094
- Shot cuts: 12.446, 12.513, 13.313, 13.38, 13.413, 13.514, 13.714

Native-frame refinement (supersedes initial timing table): {49: 48, 40: 39} ticks. Updated case hit markers and YAML together.

### Iteration 1 — real client
Inspected build/pairs/verdict-hammer-s1-round1.png. Approach/wind-up camera too wide; hit framing closer. Fan breaks into coarse rectangles with distracting white wire edges. Target hurt reaction appears but contact star is occluded/weak. Caster remains before target during recovery, unlike source slide past. ACT: add approach camera cuts, sample fan at 18 degrees with translucent gold interior + opaque pale outer band (remove wire edge), move see-through impact half a block camera-side, add non-damaging 4-block recovery dash and shorten echo rig body. Bound: 17 samples × 2 bands + 12 overlapping rig parts + 6 impact = 52.

### Iteration 2 — continuity and travel improved
Inspected build/pairs/verdict-hammer-s1-round2.png. Finer fan now continuous; recovery passes target. Impact is visible but glyph centre is above head despite y=1.1: runtime font centring differs from derived planner calibration. ACT: glyph Y compensated by 0.9 × glyph height, star holds full alpha 4t then fades, an eight-point filled star glyph adds diagonal rays; one dark fragment removed (5 displays). Raise rig pivot to 1.6, tilt 40 degrees, fan tip radius 3 for source vertical coverage. This is a content workaround, not a Kotlin correction.

### Iteration 3 review / final correction
Real-client round3 pairs inspected. Impact centering and visible nail crossguards improved. Remaining fan interior shows overlapping rectangular facets; reduced inner-band alpha from 0x38/255=0.220 to 0x18/255=0.094. Nail recovery incorrectly returned upward: explicitly hold the t29 translation at t38 while shrinking. Round4 checks these content fixes. Player body pose, volumetric glow and source scenery remain unmatched.

### Iteration 4 — final
Viewed build/pairs/verdict-hammer-s1.png (six moments). Fan panel overlap is less conspicuous after alpha reduction; sweep is still vertically circular rather than the source ellipse. At tick32 the client impact is not yet visible, then visible by tick36: recorded visual onset can lag scheduled damage. Both damage beats remain scheduled at31/48; no exact visual synchronization claim. Pass-through recovery is present. Four-iteration limit reached, partial match.
