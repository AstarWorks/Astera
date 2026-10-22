# Sigil Rifle s2

Video 15.4–18.9s. Range type: **ranged pulse / hitscan**. Importance: 6.

Measurements use 854×480 source pixels; estimates ±5 px on blurred silhouettes. H is standing body height in the SAME shot (never transfer pixel scale across a camera cut). Block conversion = element_px / H_px × 1.8. Tick = round((absolute_seconds − start_seconds) × 20). Source is 24 fps, so a source frame is 20/24 = 0.8333 ticks. The requested 30 fps sheets duplicate frames; native frames in the case cache are the timing authority. Onsets from the supplied separate audio support, but do not define, contact.

Palette: dominant vermilion #F52A13; charcoal #230808; secondary gold #FFC24A; dark crimson #8C1515. Approximate visual samples, not color-calibrated.
| Element / frame | Measurement and conversion | Timing / angle / count |
|---|---|---|
| Caster, 15.875 | H ≈175 px = 1.8 blocks | standing, no dash |
| Rifle, 15.875 | 200 / 175 × 1.8 = 2.06 blocks | horizontal, 0° |
| Hand, 15.875 | 122 / 175 × 1.8 = 1.25 blocks above feet | stationary pivot |
| Enemy separation, 15.875 | 410 / 175 × 1.8 = 4.22 blocks | capture target 4.2 |
| Red shot thickness, 16.375 | ≈12 / 175 × 1.8 = .123 blocks | narrow pulse; halo ≈.35 |
| Target burst, 16.375 | ≈180 / 175 × 1.8 = 1.85 blocks wide | red burst + thin gold spokes |
| Contact 1, 16.375 | round((16.375−15.4)×20) = 20 | authored tick 20 |
| Contact 2, 18.125 | round((18.125−15.4)×20) = 54 | authored tick 54 |

## PDCA
Initial content replaces the incorrect slot/range assignments. Four real-client iterations completed; reviews follow.

## Constraints
No custom player skeleton/crouch animation in content; the rifle rig can move, the humanoid cannot match the 2D pose. The camera harness creates only one enemy and cannot reproduce the multi-target cutaway. Source timings are retained even where the cinematic exceeds the art-direction duration guideline.

## Reproduction
`SKILL=sigil-rifle-s2 VIEW=compare CASE=sigil-rifle-s2 bash tools/vfx-shot/vfx-shot.sh`

`SKILL_COMPARE_VIDEO_DIR="$PWD/tools/skill-compare/cache/videos" make skill-pairs CASE=sigil-rifle-s2 SKILL=sigil-rifle-s2 AT="15.85:aim 16.4:hit1 16.6:tail1 17.5:hold 18.15:hit2 18.6:recovery"`

### Iteration 1 — pairs inspected
Both pulses render, but caster unexpectedly lunges and a second rifle rig appears after damage. Root cause is the old server's damage event re-entering left_click; upstream fix 795f836c arrived via external fast-forward during capture. This is not a YAML movement beat. ACT: rebuild before next iteration, retain stationary authored keys, shorten visual pulse from 10.3 to 4.8 blocks (source target spacing 4.2), tighten wide camera z 8→6.8. Dark red is dull compared with source vermilion; glyph/bloom still missing.

### Iteration 2 — pairs inspected (updated engine)
Both direct hits now land while caster remains stationary. Rifle orientation and beam direction are correct. Main mismatches: flat dark-red streaks lack the source's muzzle plume, yellow flash is too orange, red beam disappears before the sampled tail. ACT: 16 bright crimson dust + 4 smoke at the muzzle every 2t for 8t, yellow-concrete flash, core lifetime 7t and bloom 9t. Hit particle height corrected to torso. Pair hit1 sampled at 16.400s (same native frame as 16.375) to avoid binary floating-point half-tick rounding to 19 in pairs.py.

### Iteration 3 — pairs inspected
Capture 3 had a delayed start: initial camera and rig did not appear until about the middle of the clip. Visible later plume is brighter and correctly muzzle-local, but timing cannot be accepted from that run. ACT: final capture with JOIN_SETTLE=10, SETTLE=10; keep measured authored hit times. Shared target flash reduced red-spoke thickness and doubled gold-spoke thickness for clearer gold dominance.

### Iteration 4 — final pairs inspected
Longer settle removes the blank/delayed opening. Two visible hurt flashes, correct stationary caster, bright muzzle dust and red beam tail. Sampled peak 13 block displays, zero text displays. Final `build/pairs/sigil-rifle-s2.png`. Remaining: source gold streaks and red smoke are broader/brighter; palette/material and sprite silhouette are approximate. Four-iteration cap reached; partial parity.
