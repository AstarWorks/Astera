# S2 — measured plan

Source 17.200–22.400 s. **AoE at target / ranged magical strike**, not a damaging
360° spin: the preparatory rings do not contact the enemy. Two coins, two impacts.
Short approach before spin; caster then remains planted with staff overhead.
Importance 6/10. Windup 0–35t; hits 36/61t; recovery 72–104t.

Manual measurements on 384×216 tiles, body excludes ornaments; ±5 px and ±1t.
H=1.8 blocks. Native timing confirmation around S1/S3 establishes 29.97 fps;
S2 sheet is 24 fps (41.7ms, less than one server tick).

| Frame | Element | Formula | Authoring value | Ticks |
|---|---|---|---|---|
| 18.867 | Standing body | ≈85 px = 1 H | 1.8 blocks | reference |
| 18.867 | Overhead orb | ≈33/85×1.8 = 0.70 blocks | staff tip 0.35 core + glow particles | 25–49 |
| 19.033 | Hit ring | ≈65/85×1.8 = 1.38 blocks | 8 shards, ~1.2 blocks diameter | 36–46 |
| 19.033 | Separation | ≈155/85×1.8 = 3.28 blocks | 4.2 scene blocks; range 6 | 36 |
| 19.867 | Spin seal diameter | ≈100/85×1.8 = 2.12 blocks | 2.5 blocks; 12 gold +6 dark segments | 51–65 |
| 20.325 | Final arc width | ≈320/110×1.8 = 5.24 blocks | 5.4-block tip diameter, 180° | 57–66 |
| 19.033 / 20.242 | Contact ticks | round((19.033−17.2)×20)=37; round((20.242−17.2)×20)=61 | first onset 18.992 rounds 36; second 61 | 36 /61 |

Audio supports hits: onsets 19.046 /20.242. First ring appears at 18.992;
19.033 is the measurement frame after onset. Gold cut-in 20.117–20.242,
implemented t59–62. The earlier analysis's 'giant lance' is a broad curved energy sweep.
Final native-frame review below distinguishes it from the upright physical staff. No third or fourth damage event.
Palette: gold #FFD447, amber #DC820D, dark #261721, minor red/green.
Peak display bound: 27 fan +18 seal +8 impact =53. No live large glow outlines.

The authoring-value column records the initial plan; subsequent PDCA entries
explicitly supersede sizes, counts, pose interpretation and lifetimes.

## PDCA

Iteration 1: removed immediate baseline +three delayed hits; two deliberate
hits only. Removed hop and spear interpretation. Prepared staff-driven fan,
segmented seal, caster-only flash. Real-client check pending.

Native 30 fps confirmation: 18.983 first ring, 20.267 second ring (preceded
by close-up 20.167–20.233). Both are within 1 tick of authored 36/61.
Added overhead 0.55-block solid orb and 0.9-block translucent bloom (25–50t).

Approach: initial short dash 1.2 blocks/4t =0.67H in0.2s, no on_hit
mechanics. Scene target moved from4.2 to5.4 so separation AFTER approach remains4.2.
This is an estimate of the brief entry motion, not an additional coin.

Iteration 1 CHECK: `build/pairs/iteration-1/orrery-staff-s2.png` inspected.
Overhead orb/shaft are readable, but lingering flame arcs dominate; contact ring
is too large and spoke-like, and seal tilts toward the floor. Camera cuts arrive
late (~3t, same as S1), so hit rows remain on the close shot. Next ACT: shorter
owned spark particles, small radial contact shards, side-facing seal with no
origin-pivot roll, client-lag compensation210ms, modest camera tightening.

Iteration 2 CHECK: sparse gold dust and white contact sparks replace persistent
flame; seal reads as a circle and both impacts show hurt tint. Overhead orb's
brief close-up crops the tip; final fan still too regular and breaks into
separate slabs during fade. The lower sweep itself is confirmed visible in
frame_029 (same asset as S1 first sweep), excluding a universally invalid rig.
Next ACT: soften camera punch and hold fan width during its short persistence.
Final fan scale1.15: diameter2×2.3×1.15=5.29 blocks, matching measured5.24.
The S1 fan stays unscaled; the stronger second skill keeps a larger silhouette.

Iteration 3 CHECK: `build/pairs/iteration-3/orrery-staff-s2.png`: continuous
filled finisher arc now persists, orb stays within frame, both hurt beats visible.
Source's final sweep is more diagonal and its close shot larger. Final ACT:
S2-only finisher pose tilted−35° in the swing plane, same display count; camera
at59/61t moves6.5→5.5 blocks. Keep common S1 assets unchanged after its4th pass.
Final native-frame recheck:19.033 and20.325 both show the physical staff held
UPRIGHT; the giant curved energy projection moves independently. Corrected
final pass: overhead pose+orb held25–80t, finisher rig has no physical parts and
projects a lower diagonal arc from the orb (pivot2.39×1.15=2.7485 blocks).
This supersedes the earlier 'staff swings through finisher' interpretation.
Peak unchanged fan panels24+seal18+hit9+upright staff3+orb2=56 displays.

Iteration 4 FINAL CHECK: final pairs and full real-client sheet reviewed.
Physical staff remains upright through both contacts; the diagonal lower energy
arc is independent. Seven pairs include19.40 `late_hit1` to expose timing rather
than silently realigning the capture. Full sheet shows hurt/contact at about44t
and64t, versus authored36/61t: (44−36)×50=400ms and(64−61)×50=150ms late.
At nominal hit rows the impact is absent; this remains a failed timing match.
No evidence separates server load, client rendering latency and scheduling here.
Other residuals: cubic overhead orb with a thin pale trail, polygonal/dimmer fan,
source's broad luminous impact ring reduced to sparse shards, weak initial spin,
vanilla static arm/body and spectator-invisible cut-in. Damage uses6-block35°
cone rather than a selected-target AoE. Four art iterations, plus interrupted
and stale-server startup retries; no claim of complete source match.
