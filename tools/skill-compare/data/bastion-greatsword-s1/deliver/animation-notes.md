# animation-notes (limbus-animator, round 1) - S1 月輪の薙ぎ

Status: round 1, offline preview only (vfx-preview). Real client NOT run. Parts are PLACEHOLDERS (8-part list of `bastion-sweep-high`, `face` renamed `blade_face`); re-paste `rig-parts.yaml` in round 2. Nothing here is a pass.

## Structural change vs the brief (founder feedback: no duplicate sword)
ONE rig, ONE set of sword displays for the whole skill: `content/vfx/bastion-s1-rig.yaml` (id `bastion-s1-rig`, rig `sword`). It replaces `bastion-s1-rig-high / -low / -hold` (not shipped; fewer files than the brief named). Max sword displays alive at any tick = 8 placeholder parts = exactly one sword (the modeled sword will be <= 20 parts, still one). No spawn/despawn hand-over exists, so the hand-over ticks 3/21/27/42 cannot pop.
Director wiring request (content-only, no runtime change needed):
- `play_vfx bastion-s1-rig` at skill tick 0, `freeze: [{ at: 7, ticks: 2 }, { at: 31, ticks: 2 }]` (effect ticks = skill ticks since played at tick 0), `duration_ticks: 52`.
- The effect is `follow: true` so the sword rides the caster through both dashes (ticks 0-3, 24-26). Trails/emitters of a follow effect move with the anchor; the caster is stationary during both swings, so the wake stays put. If the caster can be knocked back during a swing the wake would be dragged; not expected.
- Do NOT play any rig-high/low/hold ids; fx-high/fx-low (limbus-vfx) stay at skill ticks 3 / 27 (their own clock; contact = their effect tick 4).
- The skill's `hit_stop` must pause the same ticks as the freeze list (the freeze only pauses the rig clock; if the server timeline does not stop, the rig clock and the dash/fx ticks diverge by 2 after contact 1; hit 2 rig keys already assume the 2-tick shift).
- Display reuse (Round 27) was not needed: it concerns attached passengers of replacement groups, a single rig avoids the problem entirely.

## Timing facts verified in preview (do not rediscover)
- A rig key `t=l` is DISPLAYED at effect tick `l + 1` (`VfxRigMotion.base = startTick + ANIMATION_DELAY_TICKS(1)`), plus frozen ticks before it. A freeze `{at: c}` stops the rig clock at key `c-1`, so frames c and c+1 both show key `c-1`: the apex key must be `c-1` for contact tick c. Checked in `strip-hit1-top.png` / `strip-hit2-top.png`: blade pointing straight forward on effect ticks 7+8 and 31+32 (identical frames = the 2-tick hold), then moves on.
- Hit 1: e_k (e = tick since the old rig-high spawn, contact e4) = key k+2 (displayed at skill tick 3+k). Hit 2: e_k = key k+24 (displayed at skill tick 27+k+... the first freeze shifts hit 2 by 2 rig ticks): contact displayed at effect tick 31.
- Euler yaw is unwrapped continuously (-172.9 at rest -> -532.9 after swing 1 -> -892.9 after swing 2); identical quaternion at every rest key, so the static holds are literally equal keys (no interpolation).

## Axes and roll (founder: 90 deg roll error = fail)
Convention verified with a test rig (`t1`, scratchpad) against the planner math (`Quaternion.kt`: yaw = rotation about +Y by -yaw, then pitch about X, roll about Z; `tilt * swing * rest`): +X local edge side shows to the caster's LEFT at yaw/pitch/roll 0; yaw negative turns toward the caster's left. The blade's broad face normal is local Z (placeholder `blade_face` is 0.46 wide (X) x 1.9 long (Y) x 0.07 thick (Z); edges = `rim_l/rim_r` at x +-0.25). Round 2: the real parts must keep this: width along X, thickness along Z, +Y grip -> tip.
- REST (e0 and every hold): blade tip phi 180 / pitch -20 (backward, 20 deg down), face normal (-1.00, 0.00, 0.00) in anchor (x = left): the broad face looks to the caster's right/left side (sideways), edges up/down (edge direction (0, 0.94, -0.34)). Seen in `side` as the broad rectangle with the yellow inset (same as reference a00/f01: broad face visible from the side) and in `top` as a thin white line (edge-on). I opened both.
- e2..e7: face normal is vertical (|y| >= 0.98: (0.12,-0.98,0.16) at e2, (-0.01,-0.99,0.12) at e4), edge direction horizontal along the travel. `top` view shows the broad face as a full rectangle during the sweep (edge leads), `eye` view shows the blade face-on from below at contact because the face is horizontal.
- Roll schedule psi (0 = REST, 90 = edge fully leading): e0 0, e1 62 (hit 2: 62), e2..e7 90, e8 70, e9 40, e10 20, e11 8, e12 2, e14 0.

## Keyframe table - hit 1 (key = e+2; displayed at skill tick 3+e)
tip = blade tip direction (phi, pitch) in anchor frame (0 fwd, +90 left, 270 right). turn = quaternion angle of that chunk. edge@tick = angle between the edge axis +-X and the chord velocity (incoming); edge@mid = same at the slerped middle of the chunk. Target: <= 25 degrees.

| e | key | skill tick | tip (phi, pitch) | target | tipY | turn deg | edge@tick | edge@mid | euler (yaw,pitch,roll) |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 2 | 3 | 180, -20 | 180,-20 | 0.05 | 0 | - | - | -172.9, 18.7, 68.8 |
| 1 | 3 | 4 | 160, -6 | 160,-32 | 0.65 | 62 | 66 | - | -159.2, -1.0, 9.2 |
| 2 | 4 | 5 | 248, -10 | 250,-10 | 0.48 | 93 | 44 | 19 | -245.2, 28.4, -2.6 |
| 3 | 5 | 6 | 322, +3 | 320,0..8 | 1.05 | 75 | 37 | 1 | -322.9, 9.3, 25.1 |
| 4 | 6 | 7 contact | 360, +7 | 360,+4..10 | 1.23 | 39 | 20 | 2 | -357.6, -6.6, 19.4 |
| 5 | 7 | 9 (after freeze) | 410, 0 | 430 | 0.92 | 51 | 25 | 2 | -408.2, -15.2, 3.7 |
| 6 | 8 | 10 | 455, -7 | 460 | 0.61 | 46 | 23 | 1 | -455.1, -12.9, -10.6 |
| 7 | 9 | 11 | 492, -12 | 490,-12 | 0.39 | 38 | 19 | 2 | -491.0, -3.1, -22.8 |
| 8 | 10 | 12 | 525, -18 | 540+10,-28 | 0.13 | 38 | - | - | -519.9, 11.9, 0.3 |
| 9 | 11 | 13 | 550, -19 | 550,-28 | 0.09 | 32 | - | - | -542.6, 21.2, 28.8 |
| 10 | 12 | 14 | 548, -19.5 | | 0.07 | 21 | - | - | -540.6, 21.0, 48.7 |
| 11 | 13 | 15 | 543, -20 | 540,-20 | 0.05 | 15 | - | - | -535.7, 19.8, 60.7 |
| 12 | 14 | 16 | 541, -20 | | 0.05 | 7 | - | - | -533.8, 19.1, 66.8 |
| 14 | 16 | 18 | 540.5, -20 | | 0.05 | 2 | - | - | -533.4, 18.9, 68.8 |
| 17 | 19 | 21 | 540, -20 | <= e17 | 0.05 | 0.5 | - | - | -532.9, 18.7, 68.8 |
Apex at contact: key 6 is displayed on skill ticks 7 and 8 (freeze). Main motion e1 -> e4 = 3 ticks (key 3 -> 6). Overshoot: phi +10 past REST at e9 (550), settle monotone to 540 by e14; static after key 19.

## Keyframe table - hit 2 (key = e+24; contact displayed at skill tick 31)
| e | key | tip (phi, pitch) | tipY | turn deg | edge@mid | euler |
|---|---|---|---|---|---|---|
| 0 | 24 | 180, -20 | 0.05 | 0 | - | -532.9, 18.7, 68.8 (same quaternion as rest) |
| 1 | 25 | 148, -10 | 0.48 | 62 | - | -506.6, -0.9, 11.1 |
| 2 | 26 | 235, -19 | 0.09 | 97 | 22 | -588.7, 34.8, -12.9 |
| 3 | 27 | 315, -10 | 0.48 | 78 | 2 | -679.5, 23.7, 25.6 |
| 4 | 28 | 360, -4 | 0.74 | 48 | 2 | -721.4, 3.8, 20.4 (contact, held by freeze at 31) |
| 5 | 29 | 410, -10 | 0.48 | 52 | 2 | -770.5, -5.4, 4.7 |
| 6 | 30 | 455, -16 | 0.22 | 44 | 1 | -814.8, -3.9, -3.2 (lowest: near-left, tip y 0.22) |
| 7 | 31 | 495, -13 | 0.35 | 40 | 2 | -853.6, -1.4, -15.8 |
| 8 | 32 | 530, -18 | 0.13 | 36 | - | -884.4, 13.5, -0.3 |
| 9 | 33 | 555, -19 | 0.09 | 32 | - | -907.4, 22.9, 29.0 (overshoot 15 deg) |
| 10-12 | 34-36 | 553 -> 543, -19.5 -> -20 | 0.07-0.05 | 21-8 | - | see yaml |
| 14 | 38 | 540, -20 | 0.05 | 4 | - | -892.9, 18.7, 68.8 (settle, = skill tick 41 if freezes counted; static after) |
Hit 2 is a "bigger anticipation + lower plane" variant of hit 1 (anticipation phi -32 vs -20, pitch -10, lowest pitch -16). It is only subtly different from hit 1 in the sword path; the weight difference is carried by the larger overshoot (15 vs 10) and the lower sweep.

## Pivot / tilt / rest
pivot `[-0.15, 0.92, 0.25]` for the whole skill (one rig = one pivot; hit 1 asked 1.0, hit 2 0.85: 0.92 is within 0.08 of both and removes any pop). `rest [0,90,0]`, `tilt [0,0,-20]` (negative roll = caster's right end of the plane higher; the targets in the tables are WORLD tip directions, the solver divides the tilt out, so tilt only shapes the euler interpolation). Tip length used for the numbers: 2.55 from the pivot.

## Deviations from the brief and why
1. One rig instead of rig-high/-low/-hold (founder feedback), see above.
2. e1 anticipation pitch: -6 / -10 instead of -32 / -40. The brief's pitch would drive the tip below the floor (pivot y 0.92 + 2.55 * sin(-32) = -0.43) and contradicts "pulled back/up". Amplitude is 26 deg (hit 1) / 40 deg (hit 2), >= the 15-25 asked for by direction (phi) plus roll (62 deg).
3. Overshoot pitch -19 instead of -28 (brief) for the same floor reason (tip y >= 0.05 everywhere; REST tip y is 0.05, the video says 0.1-0.2: raise the pivot or shorten the blade in round 2 if the model's tip distance differs).
4. e2 edge-vs-velocity at the sample tick is 44/37 degrees (target 25): impossible to meet at the sample instants with 75-95 deg chunks (the chord direction changes ~40 deg between consecutive ticks); at the middle of each chunk (what the client shows between updates) it is 19-22 / 1-2 degrees. Not a pass, a measured limit. Smaller chunks would need >= 5 ticks for the sweep.
5. Chunks: e1->e2 93 (hit 1) and 97 (hit 2) degrees, inside the 100 limit; no 1-tick ease over 170.
6. e5 tip phi 410 vs table 430 (e5), 455 vs 460: the table's overshoot spacing was resampled to keep 50/46/38 deg per tick deceleration; within the +-15 target at e4/e6/e7, 20 off at e5 (see table; not retuned).
7. Trails: 2 bands + edge, `dark_rim` dropped, `step_deg` 40 (not <= 30): 7 sample intervals x 3 = 21 trail displays per swing (<= 24), the two swings never overlap (hit 1 wake gone by key 17). Placeholder neutral gold bands; the director pastes limbus-vfx's. Total displays alive: 8 sword + 21 trail = 29 (<= 44).
6b. Emitters reference `bastion-s1-tip-ember` / `bastion-s1-tip-glint` (limbus-vfx files not present yet; the preview skipped nothing visibly: it draws dots). Real runtime may fail on missing particle ids until the vfx files land.

## Not verified
Real client; the interaction of `follow: true` + rig trails in the real client (preview with --follow-path not run: caster is static during swings); wake dragging if the caster moves; hit-stop/freeze alignment with the actual skill timeline; sword orientation against the real model (placeholder boxes).

## Evidence (all opened)
`evidence/animator/strip-hit1-{side,top,eye}.png`, `strip-hit2-{side,top,eye}.png` (effect ticks 3-14 / 27-38, labelled, with `--freeze 7:2 --freeze 31:2`). I opened hit1 side/top/eye and hit2 top/side; hit2 eye was rendered but not opened.

## Full per-key solver output
(scratchpad script, columns: faceN = blade broad-face normal in anchor frame (x left, y up, z forward) printed on the line ABOVE the key it belongs to; edgeX = edge axis)
```
HIGH
      faceN=(-1.00,0.00,-0.00) edgeX=(0.00,0.94,-0.34)
 e0  tip(phi,pitch)=(  180,-20.0) tipY=0.05 turn/chunk=  0.0 edge_vs_vel= euler=(-172.9,18.7,68.8) err=0e+00
      mid-chord edge_vs_chord=
      faceN=(-0.47,-0.88,-0.07) edgeX=(-0.81,0.47,-0.35)
 e1  tip(phi,pitch)=(  160, -6.0) tipY=0.65 turn/chunk= 61.8 edge_vs_vel=66 euler=(-159.2,-1.0,9.2) err=0e+00
      mid-chord edge_vs_chord=86
      faceN=(0.12,-0.98,0.16) edgeX=(-0.39,0.10,0.92)
 e2  tip(phi,pitch)=(  248,-10.0) tipY=0.48 turn/chunk= 93.3 edge_vs_vel=44 euler=(-245.2,28.4,-2.6) err=0e+00
      mid-chord edge_vs_chord=19
      faceN=(0.09,-0.99,0.14) edgeX=(0.78,0.16,0.60)
 e3  tip(phi,pitch)=(  322,  3.0) tipY=1.05 turn/chunk= 74.8 edge_vs_vel=37 euler=(-322.9,9.3,25.1) err=0e+00
      mid-chord edge_vs_chord=1
      faceN=(-0.01,-0.99,0.12) edgeX=(1.00,-0.01,0.00)
 e4  tip(phi,pitch)=(  360,  7.0) tipY=1.23 turn/chunk= 38.6 edge_vs_vel=20 euler=(-357.6,-6.6,19.4) err=0e+00
      mid-chord edge_vs_chord=2
      faceN=(-0.11,-0.99,0.13) edgeX=(0.63,-0.16,-0.76)
 e5  tip(phi,pitch)=(   50,  0.0) tipY=0.92 turn/chunk= 50.6 edge_vs_vel=25 euler=(-408.2,-15.2,3.7) err=0e+00
      mid-chord edge_vs_chord=2
      faceN=(-0.11,-0.98,0.16) edgeX=(-0.10,-0.15,-0.98)
 e6  tip(phi,pitch)=(   95, -7.0) tipY=0.61 turn/chunk= 45.5 edge_vs_vel=23 euler=(-455.1,-12.9,-10.6) err=0e+00
      mid-chord edge_vs_chord=1
      faceN=(-0.04,-0.96,0.26) edgeX=(-0.69,-0.16,-0.71)
 e7  tip(phi,pitch)=(  132,-12.0) tipY=0.39 turn/chunk= 37.5 edge_vs_vel=19 euler=(-491.0,-3.1,-22.8) err=0e+00
      mid-chord edge_vs_chord=2
      faceN=(-0.41,-0.89,0.19) edgeX=(-0.88,0.33,-0.35)
 e8  tip(phi,pitch)=(  165,-18.0) tipY=0.13 turn/chunk= 38.3 edge_vs_vel=30 euler=(-519.9,11.9,0.3) err=0e+00
      mid-chord edge_vs_chord=16
      faceN=(-0.72,-0.61,0.34) edgeX=(-0.68,0.72,-0.13)
 e9  tip(phi,pitch)=(  190,-19.0) tipY=0.09 turn/chunk= 32.2 edge_vs_vel=49 euler=(-542.6,21.2,28.8) err=0e+00
      mid-chord edge_vs_chord=37
      faceN=(-0.91,-0.32,0.24) edgeX=(-0.38,0.89,-0.26)
 e10 tip(phi,pitch)=(  188,-19.5) tipY=0.07 turn/chunk= 20.8 edge_vs_vel=56 euler=(-540.6,21.0,48.7) err=0e+00
      mid-chord edge_vs_chord=45
      faceN=(-0.99,-0.13,0.10) edgeX=(-0.16,0.93,-0.33)
 e11 tip(phi,pitch)=(  183,-20.0) tipY=0.05 turn/chunk= 14.5 edge_vs_vel=77 euler=(-535.7,19.8,60.7) err=0e+00
      mid-chord edge_vs_chord=70
      faceN=(-1.00,-0.03,0.03) edgeX=(-0.04,0.94,-0.34)
 e12 tip(phi,pitch)=(  181,-20.0) tipY=0.05 turn/chunk=  6.9 edge_vs_vel=88 euler=(-533.8,19.1,66.8) err=0e+00
      mid-chord edge_vs_chord=85
      faceN=(-1.00,-0.00,0.01) edgeX=(-0.00,0.94,-0.34)
 e14 tip(phi,pitch)=(  180,-20.0) tipY=0.05 turn/chunk=  2.2 edge_vs_vel=90 euler=(-533.4,18.9,68.8) err=0e+00
      mid-chord edge_vs_chord=89
      faceN=(-1.00,0.00,0.00) edgeX=(-0.00,0.94,-0.34)
 e17 tip(phi,pitch)=(  180,-20.0) tipY=0.05 turn/chunk=  0.5 edge_vs_vel=90 euler=(-532.9,18.7,68.8) err=0e+00
      mid-chord edge_vs_chord=90
LOW
      faceN=(-1.00,0.00,-0.00) edgeX=(0.00,0.94,-0.34)
 e0  tip(phi,pitch)=(  180,-20.0) tipY=0.05 turn/chunk=  0.0 edge_vs_vel= euler=(-172.9,18.7,68.8) err=0e+00
      mid-chord edge_vs_chord=
      faceN=(-0.48,-0.87,-0.12) edgeX=(-0.71,0.46,-0.54)
 e1  tip(phi,pitch)=(  148,-10.0) tipY=0.48 turn/chunk= 61.9 edge_vs_vel=52 euler=(-146.6,-0.9,11.1) err=0e+00
      mid-chord edge_vs_chord=76
      faceN=(0.26,-0.95,0.20) edgeX=(-0.58,0.01,0.82)
 e2  tip(phi,pitch)=(  235,-19.0) tipY=0.09 turn/chunk= 97.0 edge_vs_vel=43 euler=(-228.7,34.8,-12.9) err=0e+00
      mid-chord edge_vs_chord=19
      faceN=(0.25,-0.97,0.01) edgeX=(0.67,0.18,0.72)
 e3  tip(phi,pitch)=(  315,-10.0) tipY=0.48 turn/chunk= 78.5 edge_vs_vel=39 euler=(-319.5,23.7,25.6) err=0e+00
      mid-chord edge_vs_chord=0
      faceN=(0.01,-1.00,-0.07) edgeX=(1.00,0.01,0.00)
 e4  tip(phi,pitch)=(  360, -4.0) tipY=0.74 turn/chunk= 47.6 edge_vs_vel=24 euler=(-361.4,3.8,20.4) err=0e+00
      mid-chord edge_vs_chord=2
      faceN=(-0.22,-0.98,-0.00) edgeX=(0.62,-0.14,-0.77)
 e5  tip(phi,pitch)=(   50,-10.0) tipY=0.48 turn/chunk= 52.0 edge_vs_vel=25 euler=(-410.5,-5.4,4.7) err=0e+00
      mid-chord edge_vs_chord=3
      faceN=(-0.27,-0.96,0.05) edgeX=(-0.09,-0.03,-1.00)
 e6  tip(phi,pitch)=(   95,-16.0) tipY=0.22 turn/chunk= 44.4 edge_vs_vel=22 euler=(-454.8,-3.9,-3.2) err=0e+00
      mid-chord edge_vs_chord=3
      faceN=(-0.14,-0.97,0.18) edgeX=(-0.71,-0.03,-0.70)
 e7  tip(phi,pitch)=(  135,-13.0) tipY=0.35 turn/chunk= 40.2 edge_vs_vel=22 euler=(-493.6,-1.4,-15.8) err=0e+00
      mid-chord edge_vs_chord=6
      faceN=(-0.39,-0.89,0.23) edgeX=(-0.91,0.33,-0.27)
 e8  tip(phi,pitch)=(  170,-18.0) tipY=0.13 turn/chunk= 36.1 edge_vs_vel=29 euler=(-524.4,13.5,-0.3) err=0e+00
      mid-chord edge_vs_chord=17
      faceN=(-0.69,-0.61,0.40) edgeX=(-0.69,0.72,-0.07)
 e9  tip(phi,pitch)=(  195,-19.0) tipY=0.09 turn/chunk= 32.2 edge_vs_vel=49 euler=(-547.4,22.9,29.0) err=0e+00
      mid-chord edge_vs_chord=37
      faceN=(-0.89,-0.32,0.32) edgeX=(-0.40,0.89,-0.23)
 e10 tip(phi,pitch)=(  193,-19.5) tipY=0.07 turn/chunk= 20.8 edge_vs_vel=56 euler=(-545.3,22.7,48.8) err=0e+00
      mid-chord edge_vs_chord=45
      faceN=(-0.98,-0.13,0.17) edgeX=(-0.18,0.93,-0.32)
 e11 tip(phi,pitch)=(  187,-20.0) tipY=0.05 turn/chunk= 15.1 edge_vs_vel=78 euler=(-539.4,21.1,60.7) err=0e+00
      mid-chord edge_vs_chord=71
      faceN=(-1.00,-0.03,0.06) edgeX=(-0.05,0.94,-0.34)
 e12 tip(phi,pitch)=(  183,-20.0) tipY=0.05 turn/chunk=  8.3 edge_vs_vel=89 euler=(-535.7,19.8,66.7) err=0e+00
      mid-chord edge_vs_chord=85
      faceN=(-1.00,0.00,0.00) edgeX=(-0.00,0.94,-0.34)
 e14 tip(phi,pitch)=(  180,-20.0) tipY=0.05 turn/chunk=  4.1 edge_vs_vel=89 euler=(-532.9,18.7,68.8) err=0e+00
      mid-chord edge_vs_chord=89
```

## Round 2 (real parts pasted, axis conflict resolved)
- `bastion-s1-rig` now holds the 20 modeler parts verbatim (blade +Y, edges +-X, face normal +-Z: identical to the axes round 1 solved for, so NO euler change). Pivot y 0.92 -> 1.0 (REST tip y 0.05 -> 0.13, video 0.1-0.2). Trail `to` / emitter point y 2.55 -> 2.5 (real tip 2.545).
- Axis truth: the composed transform is `tilt * swing * rest`. `rest [0,90,0]` alone puts the face UP/DOWN (modeler is right about that, with swing identity) but the swing keys carry a roll (-172.9, 18.7, 68.8 at rest) that turns it back to sideways. Computed composed face normal (x left, y up, z fwd): REST (-1.00, 0.00, 0.00) = broad face to the caster's side, edge dir (0,0.94,-0.34); sweep keys 4..9: |y| 0.96-0.99 (face horizontal, edge leading); settle keys 10..14: |y| 0.89 -> 0.03; back to (-1,0,0) at key 19. Hit 2 same (keys 25-33 |y| 0.87-1.00). Full table: `evidence/animator/r2/face-normal-per-key.txt` (script computes tilt*swing*rest*Z from the yaml). No tilt [0,0,90] or constant roll needed; the 20 deg plane tilt is kept.
- Preview (vfx-preview, --freeze 7:2 --freeze 31:2, no planner exception): side REST shows the broad face with fuller + lime inlay, tip back and 20 deg down, tip above floor (frame_000 opened); top sweep shows the broad face as a full strip (face horizontal) with the contact held on effect ticks 7+8; eye shows the blade face-on from below at contact. Strips opened: `evidence/animator/r2/strip-hit1-{side,top,eye}.png` (hit2 strips rendered, not opened).
- Displays: 20 sword parts + 21 trail = 41 alive max (budget 44). One rig, exactly one sword at every tick.
- Hit-2 lowest tip now y ~0.30 (pivot +0.08); brief asked 0.2. Not retuned.
- Real client NOT run.

## Round 3 (real-client fail: stab/poke, no pull-back; gaps in the one-weapon rule)
- Re-read of the reference (b02 f360 tick 7, b04 f365 tick 11, d01 f392 tick 29, contact-sheet-0/1): the blade is NEVER in front. At tick 7 the grey guard + blade lie behind the caster at shoulder/hip height, tip pointing back; at ticks 11 and 29 only a thin dark line goes steeply down-back from the hip. The gold ring/streak do the cutting. The reviewer's reading is right; the brief's "tip forward at contact" is dropped.
- New motion (tip phi/pitch, 0 = fwd, 270 = caster's right): REST (180,-20) -> pull-back, tip up and behind (189,+6) key 2 -> (203,+52) key 3 (tick 4, blade raised behind, full blade visible in side) -> chop down the right-back (224,+12) key 4 -> (239,-12) key 5 -> contact (245,-18) key 6 held ticks 7-8 by the freeze -> recoil overshoots REST to (162,-10) key 11 (tick 14, 18 deg past) -> settle (180,-20) key 17 (tick 20). Hit 2 same shape, deeper: pull-back (205,+58) key 26 = tick 29, contact key 28 = tick 31 (held 31-32), overshoot (158,-9) key 33, settle key 36 = tick 41.
- Roll: face stays sideways-ish (|face.x| >= 0.49 everywhere; edge up/down leads a mostly vertical chop). No horizontal-face keys any more (that made the blade an edge-on thin line in the side view = the windup-2 collapse). Per-key tip, tipY, chunk degrees, face normal, euler: `evidence/animator/r3/per-key-tip-face.txt`. Chunks <= 54 deg (hit 1), 62 (hit 2 pull-back lift); strike 44/28; no ease over 170. Edge-leading angle vs chord not re-measured (roll fixed at 0); brief's <= 25 target is not claimed.
- Key -> tick: key l<=6 reached at tick l+1; after freeze 1 (at 7) l+3; after freeze 2 (at 31) l+5. Hit 1: pull-back keys 2,3 (ticks 3,4), strike 4,5 (ticks 5,6), contact 6 (ticks 7-8), recoil 7-11 (ticks 10-14), settle 13-17 (ticks 16-20). Hit 2: pull-back 22-26 (ticks 25-29), strike 27-28 (ticks 30-31), recoil 29-33, settle 36 = tick 41.
- Diff 1: duration_ticks 52 -> 58, the final REST hold lasts through tick 57 (hide_held_item 56 ticks + the 1 tick delay the director adds); despawn at 58. Rig first drawn tick 0 in the plan (initial = rotationAt(0), REST); the real client draws a new display 1 tick late (coordinator measured), so hide_held_item must be delayed 1 tick. Rig first visible tick 0 (plan) / 1 (client), last visible tick 57.
- Trails start at the strike keys (rig ticks 4-7 and 27-29), so the pull-back no longer paints a ribbon over the head. Displays: 20 parts + a few trail intervals x 3 per swing; preview plans without error. Real client not run.
- Strips opened: evidence/animator/r3/strip-hit1-side.png, strip-hit2-side.png, strip-hit1-top.png (hit2-top rendered, eye view not run).

## Round 6 (founder: the weapon almost does not move)
- Redesign: hit 1 = overhead chop (vertical plane, tilt 22 deg, top leans to the caster right; ring/arch plane = that plane), hit 2 = low horizontal ring (plane tilt 14 deg about centre pitch -8, lowest at phi 60 left-front, highest at phi 240 right-back). Numbers: evidence/animator/r6/per-key-tip-face.txt, strips r6/strip-hit{1,2}-{side,top,eye}.png, r6/compare-projection.png.
- Hit 1 keys: lift 200->137 deg (keys 0-3, ticks 1-4), chop theta 137->87->36->contact in 3 ticks = 160 deg (chunks 50/51/59), contact tick 7-8 tip (phi 18, pitch -17, y 0.26), drag round the left to REST with +17 deg overshoot, REST key 18.
- Hit 2 keys: pull-back keys 21-23 (tip back-up to phi 135 pitch +5), ring 220 deg in 5 ticks (chunks 30/47/55/56/59), contact key 28 = tick 31 (phi 18, pitch -18), follow-through to REST with +15 overshoot by key 39. All chunks <= 60 deg; planner accepts.
- Contact poses are left of the target line (phi 18), tip behind the target body as seen from the compare camera (caster right), so the target is never covered; the tip overlaps only its silhouette at floor level.
- Not verified: real client (no vfx-shot by instruction), fx ring alignment (vfx role), first-person feel.

## Round 7 (fist pivot, 22 parts, first-person lift)
- Parts: verbatim `deliver/rig-parts.yaml`, 22 parts (+ gold `guard_plate`, `hilt_flare`). Sword displays alive at any tick: 22 (was 20, +2); the rig has no trails (smears live in fx-high/low), so the rig's share of the cast budget is 22.
- Pivot `[-0.15, 1.0, 0.25]` -> `[-0.348, 0.773, 0.293]` (held-item fist from `tools/model-forge/work/bastion-greatsword/handover_s1.py`; shift (-0.198, -0.227, +0.043)). Swing rotations are pivot-relative, so the arc shapes/tilts are unchanged except where the lower pivot put the tip under the floor (any pitch below -16.5 deg): floor pitch is now -15 (tip y 0.11): REST and the floor drags (was -20, tip y 0.13 at the old pivot).
- Hit 1: keys 0-5 unchanged (chop plane tau 22). Contact key 6 moved ONTO the chop plane (theta -16.2: tip phi 6, pitch -15, y 0.11; r6 was phi 18 / pitch -17, off-plane, would be y 0.03). Drag phi 43/73/106/138 (was 48/78/110/140) and roll psi 14/58 on keys 7/8 to keep chunks <= 60 (57.9 / 58.9).
- Hit 2 (vfx-artist request): keys 23-27 (ticks 26-30) sit exactly on the ring plane pitch = -8 - 14 cos(phi - 60) (r6 cocked them 5-17 deg above it); contact key 28 and follow keys 29-30 clamped to pitch -15 where the plane itself dips under the floor (phi 18: plane -18.4 -> -15, 0.15 bl above; phi 60: -22 -> -15, 0.31 bl above; phi 102: 0.15 bl). Keys 21-22 (pull-back) and 31-39 (blend back to REST) leave the plane as before. Table: `evidence/animator/r7/per-key-tip-face.txt`.
- Lift (keys 42-52 = ticks 47-57) re-routed outside the caster's first-person frustum: REST -> slerp up the caster's RIGHT side to tip right-up (phi 270, pitch 52) by key 48 (tick 53; chunks 6/15/25/31/31/25/22) -> horizontal wiper about world Y into the held pose (keys 49-52, chunks 32/28/20/10). Key 52 = held item: handover_s1.py `angle vs rig 0.0 deg, grip offset [0, 0, 0]` (rig k52 tip [-0.346, 2.779, 1.860] vs held [-0.347, 2.778, 1.859]).
- First-person coverage (perspective ray-cast of the 22 boxes, eye (0,1.62,0) +Z, vfov 70, 16:9; scratch fpcov.py): ticks 47-53 0.0%, tick 54 0.1% (5.0% at the half-tick), 55 4.9%, 56 5.4%, 57 5.9%. Same tool on the r6 rig: tick 53 17.5% (24.9% half-tick), 54 20.0%, 55 10.2%, 56 7.0%, 57 5.9% -> real client reported ~30% at 54, so the client reads ~1 tick later and up to ~1.3x; r7 worst case ~8%.
- Emitters unchanged (sweep timing unchanged: hit 1 keys 3-14, hit 2 keys 24-36).
- Preview (vfx-preview, --freeze 7:2 --freeze 31:2, planner accepted): strips `evidence/animator/r7/strip-{hit1,hit2,lift}-{side,top,eye}.png`. Opened: lift-side, lift-eye (blade absent ticks 46-54, enters at the right edge 55-57), hit1-side, hit2-top. Real client NOT run.

## Round 8 (real-client r5: start snap, hand-over 57->58)
- Start: keys 0-3 (ticks 1-4) = held pose exactly (euler (-336.4, -47.8, -59.4) = key 52 minus (720, 0, 360); tip phi 0 pitch 52, face sideways). Key 4 (tick 5) pull-back to chop-plane theta 100 (tip phi 245 pitch 66, 52.5 deg), key 5 (tick 6) theta 42 (58.0 deg), key 6 contact unchanged (58.2 deg). Contact tick 7 + freeze unchanged.
- Trade-off: the wind-up is now 1 tick / 52 deg from the held pose instead of a lift from the floor behind, and the chop is 116 deg in 2 ticks (was 153 deg in 3). A bigger chop would exceed 60 deg/tick; more wind-up needs contact later than tick 7.
- Hand-over: r5 side f57 measured blade 58.8 deg above horizontal, length 0.85 x held (f58: 50.4 deg). Projection of the r7 keys: key 50 = 58.9 deg / 0.884, key 51 = 52.7 / 0.966, key 52 = 50.7 / 1.000, held (bob mean) = 50.7 / 1.000. So the client showed key 50 at tick 57 (2-tick display lag), the hand-over pose itself was right. Fix: lift keys 42-52 moved to 40-50 (same path, same FP coverage), keys 50-52 hold the held pose (ticks 55-57). REST hold after hit 2 is now 0 ticks (key 39 REST, lift starts at key 40 with a 6 deg chunk).
- Residual: vanilla arm bob. Held item drifts 50.4 (f58) -> 47.2 deg (f64, length 1.02) = bobArms xRot +0.05 rad (predicted 47.9 / 0.998); +-3 deg, not reachable from the rig. Measurement image: evidence/animator/r8/r5-side-handover-grid.png (frames 57, 58, 64; 10 px grid). Per-key: evidence/animator/r8/per-key-r8.txt.
- Emitters unchanged: tip_ember_1 starts at key 3 (still the held pose) - one static tick of embers before the pull-back; vfx role may move it to 4.
- Preview (vfx-preview --freeze 7:2 --freeze 31:2, planner accepted): evidence/animator/r8/strip-start-side.png, strip-end-side.png, strip-start-eye.png, strip-top.png, all opened. Real client NOT run (by instruction).

## Round 9 (real-client r6: hit 1 reads smaller than r5; contact moved to tick 8)
- Director r6 `side-h1-close`: f4-f5 still the held pose, f6 ~vertical, f7 contact. The single pull-back key (key 4, theta 100) was never on screen; the client interpolation skipped it. The apex needs two keys.
- Timing (manager decision): hit-1 contact = key 7, displayed at tick 8. `play_vfx` freeze = `[{at: 8, ticks: 2}, {at: 31, ticks: 2}]`. Key -> tick: l+1 (l<=7), l+3 (8..28), l+5 (29+). Hit 2 keys/ticks unchanged (contact key 28 = tick 31).
- Deviation: only keys 0-1 hold the held pose (the brief said 0-3). Keys 0-3 held leaves 3 keys for lift + 2-frame apex + chop: lift from held to >= 43 deg behind vertical is >= 82 deg (2 chunks at <= 60), a 2-key apex, and a 150+ deg chop to the floor contact needs 3 chunks. That is 6 chunks, so motion starts at key 2. The r6 side-start strip shows the rig first drawn at f2 (= key 0); keys 0-1 held still cover the vanilla -> rig switch, and every chunk after that is <= 59 deg, so there is no snap.
- Hit 1 keys (in-plane theta in the 22 deg-tilted chop plane; side = angle behind vertical as seen from the side camera, + = behind):

| key | tick | theta | tip (phi, pitch) | side | tip y | chunk |
|---|---|---|---|---|---|---|
| 0-1 | 1-2 | held | 0, 52 | -38 | 2.74 | 0 |
| 2 | 3 | 95 | 257, 67 | +5 | 3.08 | 48.1 |
| 3 | 4 | 132 | 203, 44 | +44 | 2.50 | 37.0 |
| 4 | 5 | 145 (apex) | 195, 32 | +57 | 2.10 | 13.0 |
| 5 | 6 | 100 | 245, 66 | +11 | 3.06 | 45.0 |
| 6 | 7 | 43 | 341, 39 | -49 | 2.35 | 57.0 |
| 7 | 8-10 | -16.2 contact | 6, -15 | -105 | 0.12 | 59.2 |
| 8-18 | 11-21 | r8 keys 7-17 (drag left/back, overshoot) | | | | <= 58.9 |
| 19-20 | 22-23 | REST | 180, -15 | | 0.13 | |

- Expected on-screen arc from the side camera: apex 44-57 deg behind vertical (r5 ~43, r6 ~23), apex -> contact ~160 deg (r6 ~95-110, r5 ~140). Lift eases out (48/37/13), chop eases in (45/57/59). With the ~1-2 tick client lag the apex should be on screen at ~f5-f7 and the contact at f8-f10.
- Emitters: tip_ember_1 3-15, tip_glint_1 5-11 (were 4-14 / 4-10).
- Preview: `vfx-preview bastion-s1-rig --view side|top --freeze 8:2 --freeze 31:2`, the planner accepted it. Strips (both opened): `evidence/animator/r9/strip-hit1-side.png` (f0-2 held, f3 vertical, f4-5 blade behind the shoulder, f6-7 chop, f8-10 contact frozen, f11+ drag), `strip-hit1-top.png`. Per-key table: `evidence/animator/r9/per-key-r9.txt`. Real client not run (by instruction).

## Round 10 (reviewer r9: size passes, hit-1 plane wrong -> tilted horizontal sweep)
- Plane (manager decision): hit 1 is a horizontal sweep whose tip stays on the plane y = 1.15 + tan(20 deg) * z (anchor x left, y up, z forward): forward end 20 deg higher, which is the reference ring's "right end higher" in the compare camera (screen-right = caster forward). Tip y: front 2.04 (head), sides 1.1-1.25 (chest), the plane would pass the back at ~0.4 (the follow-through leaves it there and drops to REST). Plane pitch of the tip: 30.5 (phi 0), 11.2 (phi 90/270), -9.5 (phi 180). Travel right -> front -> left -> back (phi increasing). `tilt` unchanged (hit 2 shares it); the solver works in the anchor frame.
- Keys (tick = l+1 for l <= 7, l+3 for 8..28):

| key | tick | tip (phi, pitch) | tip (x, y, z) | chunk | note |
|---|---|---|---|---|---|
| 0-1 | 1-2 | 0, 52 | -0.35, 2.74, 1.83 | 0 | held pose |
| 2 | 3 | 290, 40 | -2.15, 2.38, 0.95 | 57.1 | up the right side, outside the FP frustum |
| 3 | 4 | 230, 24 | -2.10, 1.79, -1.18 | 56.6 | wind-up extreme 1 (back-right, raised) |
| 4 | 5 | 219, 22 | -1.81, 1.71, -1.51 | 24.8 | extreme 2 (11 deg creep + roll toward the cut) |
| 5 | 6 | 262, 8 | -2.80, 1.13, -0.05 | 51.7 | on plane, edge turning to lead (40 deg) |
| 6 | 7 | 310, 24 | -2.10, 1.79, 1.76 | 49.0 | on plane, edge leads 24 |
| 7 | 8-10 | 356, 30 | -0.50, 2.04, 2.44 | 41.9 | CONTACT (freeze 8:2), edge leads 23 |
| 8 | 11 | 50, 24 | 1.40, 1.79, 1.76 | 48.9 | follow-through on plane |
| 9 | 12 | 100, 8 | 2.09, 1.10, -0.14 | 51.5 | on plane, edge leads 8 |
| 10 | 13 | 143, -9 | 1.14, 0.40, -1.68 | 48.8 | leaves plane down to the floor |
| 11-12 | 14-15 | 175 / 197, -15 | | 38.2 / 31.3 | |
| 13 | 16 | 206, -12 | -1.42, 0.25, -1.90 | 31.5 | overshoot 26 deg past REST |
| 14-18 | 17-21 | slerp to REST (0.35/0.62/0.82/0.94/1) | | 9/7/5/3/2 | REST at key 18 = tick 21 |
| 19-20 | 22-23 | REST 180, -15 | | 0 | unchanged, hit 2 unchanged from key 21 |

- Travel: phi 219 (extreme) -> 356 (contact) = 137 deg in 3 ticks (ease-in 52/49/42 deg rotation), contact -> phi 100 in 2 ticks still on the plane, overshoot at phi 566: 347 deg of phi from the extreme, 393 deg of rotation. The on-plane part (keys 5-9, phi 262 -> 460) is ~200 deg of ring around the body at chest/head height.
- Edge lead (edge axis vs the chord to the next key): wind-up keys 2-4 60-67 (face leads backward: anticipation, not a cut), sweep 40/24/23 (keys 5-7), follow 23/8/15 (8-10), then the face rolls back to the sideways REST face (keys 11-13). Brief target <= 25 met on keys 6-10.
- First person (ray-cast, eye (0,1.62,0) +Z, vfov 70, 16:9; also the half-tick slerp): t0-2 5.9% (held pose), half-tick held->k2 7.0% (r9: 18.5%, this is what the client showed at ~t4), t3-6 0.0%, t7 4.6%, t8-10 4.0% (contact), t11 5.9%, t12 0.0%. No part within 0.3 of the eye. Preview `eye` t3-7 empty; t8/t11 blade in the lower-left (preview eye reads ~10% there).
- Compare camera (intent-redo cuts, my projection `evidence/animator/r10/proj-compare.png`, 640x360): wide cut t4-5: blade horizontal pointing BEHIND the caster (screen-left), 101-115 px (~0.3 screen height); close cut t6 (camera 1 block from the caster's right-side tip path): key 5 tip comes toward the camera, 246 px bar leaving the bottom edge; t7 a 313 px bar across to the right edge; t8-10 contact 282 px rising 28 deg to the upper right (toward the target) = the "right end higher" slope; wide cut t11+: follow-through swings to screen-left behind the caster, REST lower-left. Side camera (vfx-shot, caster's left, `proj-side.png`): wind-up t4-5 tip behind (screen-right) at 28-35 deg up, sweep passes the far side (short), contact t8-10 tip forward-up 31 deg, follow-through drops to the floor behind.
- Client lag: the client shows key l ~2 ticks late, so expect the extreme on screen ~f6-7 and contact ~f10 (freeze still at effect tick 8).
- Emitters unchanged (tip_ember_1 3-15, tip_glint_1 5-11 cover the extreme, sweep and follow-through).
- Preview: `vfx-preview bastion-s1-rig --view side|top|eye --freeze 8:2 --freeze 31:2`, planner accepted. Strips (all three opened): `evidence/animator/r10/strip-hit1-{side,top,eye}.png` (ticks 2,3,4,5,6,7,8,11,12,13,16,21). Top: tip back-right t4-5 -> right t6 -> front-right t7 -> front t8 -> left t11-12 -> back-left t13 -> back-right overshoot t16 -> REST t21 (a full circle of emitter dots). Per-key: `evidence/animator/r10/per-key-r10.txt`. Real client not run (by instruction).
- For the vfx ring: blade root (local y 0.7) / tip (local y 2.5) per displayed tick 4-10 in anchor coords: t4 (-0.84,1.06,-0.12)/(-2.10,1.79,-1.18); t5 (-0.76,1.04,-0.21)/(-1.81,1.71,-1.51); t6 (-1.03,0.87,0.20)/(-2.80,1.13,-0.05); t7 (-0.84,1.06,0.70)/(-2.10,1.79,1.76); t8-10 (-0.39,1.13,0.89)/(-0.50,2.04,2.44); t11 (0.14,1.06,0.70)/(1.40,1.79,1.76); t12 (0.34,0.86,0.17)/(2.09,1.10,-0.14).

## Round 11 (director r8 opponent capture: hand-over snaps ~30-40 deg at f54->55 in the front view)
- 3-axis check with `tools/model-forge/work/bastion-greatsword/handover_s1.py` (26.3 third-person chain) vs the shipped `thirdperson_righthand` display: keys 0-1 and 50-52 (r10) already equal the held item to 0.0 deg in yaw / pitch / roll (world-frame YXZ delta; face differs only by the symmetric Ry180 flip). Arm bob residual (not the rig's): zRot 0..0.1 rad = front lean -2.8..+2.9 deg, xRot +-0.05 rad = pitch 49.1..54.9 deg; the rig sits on the mean (lean 0.0, pitch 52.0).
- Cause: timing, not orientation. Opponent frames f51-54 show front lean ~-38 -> -33 deg = keys 46-47 (tip up the caster's right, lean -38.0 / -33.5); the held item appears at f55, before the client ever showed wiper keys 48-50 (lean -21.3 / -7.7 / 0). The side and compare cameras see that wiper mostly as depth, hence smooth there.
- Fix: same lift path re-timed (quaternion slerp of the r10 path at u = 39 + (j-39)*11/7): keys 40-46 = REST -> right side -> wiper; held pose reached at key 46 (tick 51) and held through key 52 (tick 57), 6 ticks. Chunks 15.0 / 35.6 / 48.4 / 39.6 / 28.2 / 41.1 / 21.5 (max 48 <= 60). Front lean per key 40-46: 129 / 22 / -6 / -28 / -35 / -16 / 0. First-person coverage stays <= 5.9% (0% through key 44, half-ticks included).
- Keys 0-1 unchanged (0.0 / 0.0 / 0.0 vs held).
- Preview (opened): `evidence/animator/r11/strip-end-front.png`, `strip-end-side.png` (`vfx-preview --view front|side --freeze 8:2 --freeze 31:2`, ticks 42-57 then 0-3): front upright from t51 to t57 and identical at t0-2; side steady forward-up from t50. Real client not run (by instruction). Numbers: `evidence/animator/r11/per-key-r11.txt`; opponent crop `opp-r8-f44-59.jpg`.
