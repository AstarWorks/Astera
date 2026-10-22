# Sigil Rifle s3

Video 19.6–25.9s. Range type: **ranged shot followed by redirected target impact**. Importance: 9.

Measurements use 854×480 source pixels; estimates ±5 px on blurred silhouettes. H is standing body height in the SAME shot (never transfer pixel scale across a camera cut). Block conversion = element_px / H_px × 1.8. Tick = round((absolute_seconds − start_seconds) × 20). Source is 24 fps, so a source frame is 20/24 = 0.8333 ticks. The requested 30 fps sheets duplicate frames; native frames in the case cache are the timing authority. Onsets from the supplied separate audio support, but do not define, contact.

Palette: dominant vermilion #F52A13; charcoal #230808; secondary gold #FFC24A; dark crimson #8C1515. Approximate visual samples, not color-calibrated.
| Element / frame | Measurement and conversion | Timing / angle / count |
|---|---|---|
| Kneeling pose, 21.500 | visible height ≈130 px; standing same framing ≈175 | body ≈130 / 175 × 1.8 = 1.34; rig hand 1.0 |
| Rifle, 21.500 | ≈200 / 175 × 1.8 = 2.06 blocks | raised then horizontal |
| Target body, 24.333 | standing H ≈175 px = 1.8 | use standing side target, not crouching central target |
| Seal, 24.333 | diameter ≈130 / 175 × 1.8 = 1.337; radius .669 | 3 circles, radius .68; two vertical + one tilted above |
| Upper seal height, 24.333 | 190 / 175 × 1.8 = 1.954 blocks | center ≈2.0 |
| Side-seal displacement, 24.333 | 75 / 175 × 1.8 = .771 blocks | ±.75 from target |
| Muzzle, 22.083 | round((22.083−19.6)×20) = 50 | firing, not extra damage |
| Contact 1, 23.167 | round((23.167−19.6)×20) = 71 | red horizontal pulse, 1st coin |
| Redirect contact, 24.708 | round((24.708−19.6)×20) = 102 | 2nd coin, gold lines between seals |

## PDCA
Initial content replaces the incorrect slot/range assignments. Four real-client iterations completed; reviews follow.

## Constraints
No custom player skeleton/crouch animation in content; the rifle rig can move, the humanoid cannot match the 2D pose. The camera harness creates only one enemy and cannot reproduce the multi-target cutaway. Source timings are retained even where the cinematic exceeds the art-direction duration guideline.

## Reproduction
`SKILL=sigil-rifle-s3 VIEW=compare CASE=sigil-rifle-s3 bash tools/vfx-shot/vfx-shot.sh`

`SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=sigil-rifle-s3 SKILL=sigil-rifle-s3 AT="20.5:raise 21.5:aim 22.083:muzzle 23.167:hit1 24.333:seals 24.708:hit2 25.3:recovery"`

Seal planes face the side camera (formation yaw 90°, pitch 90°); upper seal pitch 35°. A lens normal along the firing axis would appear edge-on in the source camera and is not used for these target seals.

### Iteration 1 — pairs inspected
Same upstream damage-to-input recursion as S2, producing an unintended S1 lunge and extra rig. Seals render but overlap because their offsets were along camera depth, not screen horizontal. ACT: side seals move to local Z ±.75, X=0; keep their camera-facing planes. Replace invisible interior glyphs with three thin solid radial spokes per seal. Narrow shoulder fan to outer 30%. Beam translation is a CENTER (confirmed VfxTimeline): set center = muzzle 1.7 + length 4.8 / 2 = 4.1, avoiding a beam extending behind the caster. Add 14 crimson dust particles per hit for source-like bright scattered embers. Rebuild incorporates upstream 795f836c; no Kotlin changed here.

### Iteration 2 — pairs inspected (updated engine)
Caster remains planted, both hits flash on dummy, and all three seals separate correctly. Main remaining mismatch: seals look like sparse dark wheels, disappear before source recovery, and the camera misses the very short target close-up. ACT: saturated redstone-block rings .06 thick (was .035), three-sided interior rather than asterisk, hold interiors through the hit, extend seal fade from relative tick 32 to 44 (absolute 124, source 25.8s). Muzzle plume and gold flash changes shared with S2. Short camera-cut latency remains a capture-tool limitation.

### Iteration 3 — pairs inspected
Three separate red seal rings with triangular interiors now persist through recovery; target close-up lands correctly in this run. Source seals are much more intricate and luminous, and the missing kneel remains obvious. ACT: reduce target red spokes (.22→.12 and .12→.08), gold spoke .055→.10 to restore the gold impact accent; longer scene settle for final capture. No new displays.

### Iteration 4 — final pairs inspected; iteration cap reached
Raised rifle, planted stance, two target hurt flashes, three separate seals and recovery are visible. At the selected tick-50 muzzle pair the discharge is not yet visible; the target close-up also misses this run. These are unresolved timing/camera mismatches, not successful source matches. Red rings remain dark and sparse; the source kneel and three-target arrangement are absent. Gold impact reads more clearly after the last material/width change. Final status: partial visual parity, four iterations, no further correction claimed.
