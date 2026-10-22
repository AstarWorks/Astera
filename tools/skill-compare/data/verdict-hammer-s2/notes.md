# verdict-hammer-s2: measurement and PDCA

Source Hc6CJXJrzME, 35.3–38.2s, native 30000/1001 fps. Reviewed 4 fps strips and 24/30 fps flip sheets. Measurements are approximate silhouette bounds on 384px-wide tiles, +/- 3 px; antlers/hair excluded from body height. Camera changes prohibit using a body height from a different shot. Video subtitles are not skill labels: slot mapping remains inferred from the supplied wiki coin counts. No ally interlude at 38.7–42s is included.

Range: **AoE at target / paired falling nails**. Caster x keys (blocks): [(0, 0)]. No damage on the positioning dash. Q's movement is approximated by a stationary caster; see report.

| Frame seconds | Element | Body-height ratio | Conversion |
|---|---|---|---|
| 36.500 | Nail blade | 70/45 = 1.556 H | 70/45 × 1.8 = 2.800 blocks |
| 36.600 | Column visible height | 205/45 = 4.556 H | 205/45 × 1.8 = 8.200 blocks |
| 37.100 | Ground ring diameter | 170/45 = 3.778 H | 170/45 × 1.8 = 6.800 blocks |

Palette sampled visually: pale core #FFF4BD, gold #FFD66B, dark hammer #302313; violet #6353AA only on movement. No teal impact sparks (arena lights are not attack effects). Nail pair count = 2; angle about +/-16 degrees from vertical; ring count 16 approximates a continuous circumference. Hammer = haft + dark rectangular head + 4 gold strips. All solid parts brightness 15; bloom alpha .22. Effect center = target feet + (0,1,0); ring y=.12 blocks = .12/1.8 = .067 H.

| Beat | Observed source seconds | Tick formula | Implemented source-clock time |
|---|---|---|---|
| 1 | 36.600 | round((36.600 − 35.3) × 20) = 26 | 35.3+26/20 = 36.600s (t26) |

Nail: summon at impact−16; hover 12 ticks; descend 3 ticks; beam 6 ticks; ring 14 ticks. Hammer: wind-up impact−9 through impact−4, sweep 4 ticks, recovery 14 ticks. Source timing preserved even where edited cinematic duration exceeds art guide 60/100t preference. Hit times quantized to 50 ms (maximum quantization error 25 ms); source still samples 33.367 ms. Musical onsets corroborate impacts but cannot identify damage independently.

## PDCA
- Plan: remove immediate baseline damage and unrelated AoE; use exact wiki hit counts 2/1/3/2; Q has no wiki coin count.
- Do: original display names retained; block hammer rig and fan, solid paired nails, short bloom, dark fragments, real particles, explicit multihit i-frame bypass.
- Check: pending real-client capture.

Native-frame cross-check: S1 arc begins 12.433s, second smear 13.300s, white cut 13.367s; DEF beams 16.967s and contact white 18.467s; S2 beams 36.600s; S3 beams 28.050s, thrust cut 29.383s, airborne hammer preparation 30.717s (no extra coin), terminal smash 31.342s; Q field 56.690s, paired impact 57.858s, sweep 59.393s, hammer flare 60.692s, inverse cut 62.763s, terminal burst 63.864s. Exact server damage moments are quantized approximations to these visual windows, not inferred additional coins.

Audio onset/cut cross-check from muxed cache (absolute seconds):
- Strong onsets: 35.509, 35.602, 36.031, 36.647, 37.61
- Shot cuts: 36.603

### Iteration 1 — real client
Inspected build/pairs/verdict-hammer-s2-round1.png. Correct visible paired descent / hurt beats, but actor too small in wide shots, columns nearly invisible, contact flash absent, and melee fan coarse. Planner inspection shows billboard quads retain anchor rotation, turning a yaw-oriented quad edge-on from the side. ACT: world-oriented billboard impact; solid white column plus glass bloom (same four entities); dolly wide cameras to 9–10 blocks; denser two-band fans. DEF/S3 terminal ground ring added; S3 preparatory hammer shortened to avoid two held hammers at terminal contact. All changes YAML only.

### Iteration 2
Inspected build/pairs/verdict-hammer-s2-round2.png. Nail columns now readable as a V, cameras much closer, ground ring placement coherent. Bloom glass over-darkens the white core; glyph centre shifts high (off-frame in close shots). ACT: clear-white glass bloom; shared glyph vertical calibration/hold fix; one vanilla flash per impact to support the solid core. Fan remains visibly faceted and block hammer cannot deform like source.

### Iteration 3 review / final correction
Real-client round3 pairs inspected. Impact centering and visible nail crossguards improved. Remaining fan interior shows overlapping rectangular facets; reduced inner-band alpha from 0x38/255=0.220 to 0x18/255=0.094. Nail recovery incorrectly returned upward: explicitly hold the t29 translation at t38 while shrinking. Round4 checks these content fixes. Player body pose, volumetric glow and source scenery remain unmatched.

### Iteration 4 — final
Viewed build/pairs/verdict-hammer-s2.png (six moments). V-shaped columns converge at the target and one damage beat is represented. The late ring frame still shows nails above the victim despite the terminal translation fix; recovery trajectory is not accepted as matched. Straight columns, coarse star and regular thin ring differ from source radiance. Four-iteration limit reached, partial match.
