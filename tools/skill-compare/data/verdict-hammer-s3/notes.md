# verdict-hammer-s3: measurement and PDCA

Source Hc6CJXJrzME, 25.4–32.6s, native 30000/1001 fps. Reviewed 4 fps strips and 24/30 fps flip sheets. Measurements are approximate silhouette bounds on 384px-wide tiles, +/- 3 px; antlers/hair excluded from body height. Camera changes prohibit using a body height from a different shot. Video subtitles are not skill labels: slot mapping remains inferred from the supplied wiki coin counts. No ally interlude at 38.7–42s is included.

Range: **AoE at target then two melee beats**. Caster x keys (blocks): [(0, 0), (6, 0), (15, 2), (72, 2), (79, 5.5)]. No damage on the positioning dash. Q's movement is approximated by a stationary caster; see report.

| Frame seconds | Element | Body-height ratio | Conversion |
|---|---|---|---|
| 28.300 | Nail V height | 45/45 = 1.000 H | 45/45 × 1.8 = 1.800 blocks |
| 29.550 | Sweep diameter | 320/85 = 3.765 H | 320/85 × 1.8 = 6.776 blocks |
| 31.500 | Burst diameter | 280/120 = 2.333 H | 280/120 × 1.8 = 4.200 blocks |

Palette sampled visually: pale core #FFF4BD, gold #FFD66B, dark hammer #302313; violet #6353AA only on movement. No teal impact sparks (arena lights are not attack effects). Nail pair count = 2; angle about +/-16 degrees from vertical; ring count 16 approximates a continuous circumference. Hammer = haft + dark rectangular head + 4 gold strips. All solid parts brightness 15; bloom alpha .22. Effect center = target feet + (0,1,0); ring y=.12 blocks = .12/1.8 = .067 H.

| Beat | Observed source seconds | Tick formula | Implemented source-clock time |
|---|---|---|---|
| 1 | 28.050 | round((28.050 − 25.4) × 20) = 53 | 25.4+53/20 = 28.050s (t53) |
| 2 | 29.383 | round((29.383 − 25.4) × 20) = 80 | 25.4+80/20 = 29.400s (t80) |
| 3 | 31.342 | round((31.342 − 25.4) × 20) = 119 | 25.4+119/20 = 31.350s (t119) |

Nail: summon at impact−16; hover 12 ticks; descend 3 ticks; beam 6 ticks; ring 14 ticks. Hammer: wind-up impact−9 through impact−4, sweep 4 ticks, recovery 14 ticks. Source timing preserved even where edited cinematic duration exceeds art guide 60/100t preference. Hit times quantized to 50 ms (maximum quantization error 25 ms); source still samples 33.367 ms. Musical onsets corroborate impacts but cannot identify damage independently.

## PDCA
- Plan: remove immediate baseline damage and unrelated AoE; use exact wiki hit counts 2/1/3/2; Q has no wiki coin count.
- Do: original display names retained; block hammer rig and fan, solid paired nails, short bloom, dark fragments, real particles, explicit multihit i-frame bypass.
- Check: pending real-client capture.

Native-frame cross-check: S1 arc begins 12.433s, second smear 13.300s, white cut 13.367s; DEF beams 16.967s and contact white 18.467s; S2 beams 36.600s; S3 beams 28.050s, thrust cut 29.383s, airborne hammer preparation 30.717s (no extra coin), terminal smash 31.342s; Q field 56.690s, paired impact 57.858s, sweep 59.393s, hammer flare 60.692s, inverse cut 62.763s, terminal burst 63.864s. Exact server damage moments are quantized approximations to these visual windows, not inferred additional coins.

Audio onset/cut cross-check from muxed cache (absolute seconds):
- Strong onsets: 25.934, 27.432, 28.21, 29.649, 30.009, 32.029
- Shot cuts: 28.061, 29.363, 29.396, 31.331, 31.365, 31.798, 31.832, 31.865, 31.899

Native-frame refinement (supersedes initial timing table): {81: 80, 72: 71} ticks. Updated case hit markers and YAML together.

### Iteration 1 — real client
Inspected build/pairs/verdict-hammer-s3-round1.png. Correct visible paired descent / hurt beats, but actor too small in wide shots, columns nearly invisible, contact flash absent, and melee fan coarse. Planner inspection shows billboard quads retain anchor rotation, turning a yaw-oriented quad edge-on from the side. ACT: world-oriented billboard impact; solid white column plus glass bloom (same four entities); dolly wide cameras to 9–10 blocks; denser two-band fans. DEF/S3 terminal ground ring added; S3 preparatory hammer shortened to avoid two held hammers at terminal contact. All changes YAML only.

### Iteration 2
Inspected build/pairs/verdict-hammer-s3-round2.png. Nail columns now readable as a V, cameras much closer, ground ring placement coherent. Bloom glass over-darkens the white core; glyph centre shifts high (off-frame in close shots). ACT: clear-white glass bloom; shared glyph vertical calibration/hold fix; one vanilla flash per impact to support the solid core. Fan remains visibly faceted and block hammer cannot deform like source.

### Iteration 3 review / final correction
Real-client round3 pairs inspected. Impact centering and visible nail crossguards improved. Remaining fan interior shows overlapping rectangular facets; reduced inner-band alpha from 0x38/255=0.220 to 0x18/255=0.094. Nail recovery incorrectly returned upward: explicitly hold the t29 translation at t38 while shrinking. Round4 checks these content fixes. Player body pose, volumetric glow and source scenery remain unmatched.

### Iteration 4 — final
Viewed build/pairs/verdict-hammer-s3.png (seven moments). Three damage actions are authored; final capture has red victim feedback at first/last hit, but selected hit frames miss the shared star flash and are much dimmer than round3/source. This exposes client/capture timing variability rather than proving synchronized effects. Fan panel overlap improved, but thrust, preparatory cluster and terminal dome are still unmatched. Four-iteration limit reached, partial match.
