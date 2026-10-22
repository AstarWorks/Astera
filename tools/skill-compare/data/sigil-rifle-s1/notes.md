# Sigil Rifle s1

Video 12.7–14.9s. Range type: **dash-melee**. Importance: 4.

Measurements use 854×480 source pixels; estimates ±5 px on blurred silhouettes. H is standing body height in the SAME shot (never transfer pixel scale across a camera cut). Block conversion = element_px / H_px × 1.8. Tick = round((absolute_seconds − start_seconds) × 20). Source is 24 fps, so a source frame is 20/24 = 0.8333 ticks. The requested 30 fps sheets duplicate frames; native frames in the case cache are the timing authority. Onsets from the supplied separate audio support, but do not define, contact.

Palette: dominant vermilion #F52A13; charcoal #230808; secondary gold #FFC24A; dark crimson #8C1515. Approximate visual samples, not color-calibrated.
| Element / frame | Measurement and conversion | Timing / angle / count |
|---|---|---|
| Upright caster, 13.250 | H ≈ 170 px = 1.8 blocks | neutral reference |
| Rifle, 13.250 | 190 / 170 × 1.8 = 2.01 blocks | muzzle above head, ≈90° |
| Wind-up arc, 13.250 | diameter 310 / 170 × 1.8 = 3.28; radius = 3.28 / 2 = 1.64 | one red semicircle, 180°; ticks 5–15 |
| Arc center, 13.250 | 118 / 170 × 1.8 = 1.25 above feet | hand pivot |
| Contact, 13.875 | enemy H ≈250; slash height 300 / 250 × 1.8 = 2.16 | diagonal sweep; one damaging hit |
| Advance, wide recovery 14.0 | spacing change ≈145 / 100 × 1.8 = 2.61 | 2.6 blocks forward, ticks 17–20 |
| Hit time, 13.750 | round((13.750−12.7)×20) = 21 | 1 coin / 1 damage; gold sparks, 2t stop |

## PDCA
Initial content replaces the incorrect slot/range assignments. Four real-client iterations completed; reviews follow.

## Constraints
No custom player skeleton/crouch animation in content; the rifle rig can move, the humanoid cannot match the 2D pose. The camera harness creates only one enemy and cannot reproduce the multi-target cutaway. Source timings are retained even where the cinematic exceeds the art-direction duration guideline.

### Iteration 1 — real-client pairs inspected
`build/pairs/sigil-rifle-s1.png`: rifle and 2.6-block advance render, but the text-backed fan and glyph impacts are invisible throughout the sheet. Opening/recovery camera too wide. ACT: add red concrete fan surfaces plus thin charcoal edge, retain soft bloom; add three solid impact spokes; extend tail to 6t; move wide camera from 17 to 7 blocks and recovery from 10 to 7. Source body silhouette crouches; client remains upright (engine gap).

## Reproduction
`SKILL=sigil-rifle-s1 VIEW=compare CASE=sigil-rifle-s1 bash tools/vfx-shot/vfx-shot.sh`

`SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=sigil-rifle-s1 SKILL=sigil-rifle-s1 AT="12.95:windup 13.25:arc 13.55:advance 13.75:hit 13.9:followthrough 14.45:recovery"`

Capture calibration: iteration 1 camera cut scheduled at tick 5 was first settled around tick 8; use CAST_LAG_MS=210 instead of 60 (difference 150ms = 3 ticks). This compensates observed client delivery/render delay; authored gameplay timings are not shifted.

Rig length correction: initial stock-to-muzzle span = 1.6 − (−.65) = 2.25 blocks; apply 2.05/2.25 = .91111 to part longitudinal offsets and sizes, giving 2.05 blocks (measured source ≈2.01–2.06).

### Iteration 2 — pairs inspected
Solid fan now renders, but the full radial band is a broad slab and peaks too early. ACT: outer 30% band (1.1 × .30 = .33 blocks), 25° segments, thin .03 charcoal edge, wind-up trail ticks 7–12 and five-tick tail. Fixed weapon length and closer framing retained. Captured server predates upstream input-recursion fix 795f836c, which landed while running. Rebuild before iteration 3.

### Iteration 3 — pairs inspected (updated engine)
The dummy now visibly takes the intended strike; no extra skill is dispatched. Narrow fan matches the crescent envelope better, but its older segments shrink into disconnected dashes. ACT: trail fade_power .3 and life 8t retain a connected band through the peak; add two crimson dust particles per tick at the muzzle/tip; move hit particles from feet to torso and hold caster with target for 2t. Remaining body crouch and coat silhouette cannot be authored in content.

### Iteration 4 — final pairs inspected
Connected crescent and sparse red tip particles now visible; one direct hit verified by health 200→192.9152. Final `build/pairs/sigil-rifle-s1.png`. Remaining: upright body vs low slide, coat absent, rifle finishing angle differs, darker flatter red. Stop at the requested four-iteration cap; partial parity.
