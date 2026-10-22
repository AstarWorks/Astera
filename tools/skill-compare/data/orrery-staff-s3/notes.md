# S3 — measured plan

Primary comparison: second performance, 34.300–43.200 s; first performance
23.900–33.800 is a multi-target version of the same choreography. **Beam** range.
Caster approaches during intro, remains planted for three shots, raises staff,
then channels final shot. Close inspection of 38.3–39.2 shows planted feet:
the earlier 'rises one body height / ten-block pillar' hypothesis is rejected.
Importance 9/10. Contacts 38/55/66/136t, final beam held through 168t.

Manual tile measurements (384×216, body excludes antlers), ±5 px; angles ±10°.
H=1.8 blocks, tick=round((time−34.3)×20). Native sheets inspect third shot at
37.567; other shots inspected at 24 fps with <=1t temporal uncertainty.

| Frame | Element | Formula | Implementation | Window |
|---|---|---|---|---|
| 36.217 | Body | ≈80 px = 1 H | 1.8 blocks | reference |
| 36.217 | Muzzle clock diameter | ≈150/80×1.8 =3.375 blocks |3.6-block ring |38–51t |
| 36.217 | Beam core thickness | ≈4/80×1.8 =0.09 blocks |0.12 core, 0.30 bloom |38–50t |
| 36.217 | Visible beam | ≈200/80×1.8 =4.5 blocks; cropped by framing |12-block beam, 13-block targeting |38–50t |
| 37.092 | Second clock | ≈185/95×1.8 =3.51 blocks |3.6-block ring |55–68t |
| 37.567 | Third clock onset | round((37.567−34.3)×20)=65 |66t (+1t rounding/pose tolerance) |66–79t |
| 38.883 | Channel seal | ≈180/155×1.8 =2.09 blocks |2.5-block ring, no levitation |84–98t |
| 40.967 | Charged orb | ≈35/95×1.8 =0.663 blocks |0.6 core +1.0 shell |121–137t |
| 41.217 | Final core thickness | ≈5/95×1.8 =0.095 blocks |0.12 blocks |136–168t |
| 36.217 /37.050 /41.100 | Contact ticks |round(1.917×20)=38; round(2.750×20)=55; round(6.800×20)=136 |38/55/136 |4 damage beats total |

Long beam brightness is visual persistence, NOT extra damage pulses. First three
shots 12t each, final 32t. Onsets at 36.169,37.110,37.644,41.231 support the
observed shot groups. The final beam's small repeated target rings are afterglow,
not extra coins. Dominant gold #FFD447; dark #33202A; red/green minor motes.
Beam muzzle 1.4 blocks high (≈0.78H); staff tilts from upright to ≈horizontal.
Camera punching close at 36.1,37.05,37.567,39.0,40.0,41.1 is encoded as keys.

Peak display bound near t66: two clock rings 36 +two beams 6 +two impacts 16
+staff 3 =61 if both impact rings overlap. The t55 impact ends at t65 (end_tick10),
so t66 peak is53; orb expired at t68 adds2 ->55. Keep <=60.
Guard uses25 displays; inferred ultimate <=49. No per-enemy display fan-out:
nearest target receives shards; all beam targets receive only particles.

The implementation column and budget above record the initial plan. Final
PDCA entries below supersede them: clock diameter3.3, core0.08, bloom0.22,
beam length11, short life10t, clock42 displays, conservative peak59.

## PDCA

Iteration 1: replaced ~7 damage pulses with four timed hits. Rejected unsupported
levitation/pillar. Added actual raised staff pose, segmented muzzle rings,
constant-thickness beam with dark accent and no glow outline. Check pending.

Known targeting limitation: narrow cone approximates beam; width grows with
range (13×tan(6°/2)≈0.68-block half-width if angle is full cone). A constant-radius
hitscan cylinder is unavailable in the exposed targeter schema. Final report
must retain this mismatch unless real engine behavior establishes otherwise.

Native 30 fps confirmation: shots at 36.167–36.200,37.033–37.067,
37.567,41.133–41.167; visible impact follows muzzle by 0–2 frames.
A violet impact core adds one display: revised maximum <=56.
Full cone apex interpretation verified in TargeterResolver.resolveCone KDoc.

Approach: initial short dash1.2 blocks/4t (0.67H/0.2s), zero damage.
Channel rig anchors after the dash at t5, with all local pose/trail keys shifted
−5t to preserve absolute contact times. Target begins10.2 blocks away so
post-approach separation=10.2−1.2=9 blocks.

Iteration 1 CHECK: `build/pairs/iteration-1/orrery-staff-s3.png` reviewed. Beam
exists but source actors are much closer relative to their heights, causing
Minecraft close-ups to crop both actors. Corrected actor distance: at36.217,
actor-to-actor≈280px /body80px×1.8=6.3 blocks; previously9 was overestimated.
Target begins7.5 so7.5−1.2 dash=6.3. Ring remains3.6 diameter, but gold segments
increase12→24, dark6→8; life13→10 prevents generations overlapping (55+10<66).
Peak now32 clock+6 overlapping beam slots+9 impact+3 rig+2 orb=52.
Beam core0.12 yellow→0.08 white, bloom0.30→0.22, dark0.06→0.025.
Channel camera centres on caster. Short sparks replace persistent flame trails.

Iteration 2 CHECK: `build/pairs/iteration-2/orrery-staff-s3.png`: all four beam
moments visible; spacing fixed, small hit shards/short sparks visible, channel
close-up readable. Still sparse/outline-like clock and brown oversized orb;
ring bottom clips floor, camera remains too wide in beam shots.
ACT: radius1.65, centre-height1.65 (bottom=0); gold band0.26; inner12-segment
clock; dark count8→6. Clock total24+6+12=42. Short beam life12→10 avoids overlap,
peak42+3 beam+9 hit+3 staff+2 orb=59. Orb white core max0.585, glass max0.75;
beam begins at staff tip1.95, length11, centre=1.95+11/2=7.45, ends12.95 (range13).
Camera distance cap7.5 for beam shots. Fan fade_power0.3 keeps early tiles broad.
First channel flourish lags1 tick: first fan panels spawn77, after shot3's
clock/beam/impact despawn76. This prevents even a same-tick spawn-before-despawn
transient from exceeding60 displays.

Iteration 3 CHECK: `build/pairs/iteration-3/orrery-staff-s3.png`: concentric
clock is fuller and clear of the floor; orb smaller, camera scale closer. First
beam row repeated the preceding pose (another capture timing/stall anomaly);
shots2/3/4 visible. Source remains brighter and more irregular. Final ACT adds
12 jittered gold-dust emitters on the clock for7t (no displays); preserves the
59-display bound. Final pass is also a repeatability check of the first shot.

Iteration 4 validation failure: four beams appeared, but the entire muzzle
clock was missing. Runtime loader log `/tmp/orrery-final-load.log` identified
`elements[3].kind.count` as unknown. Content lint had accepted it (schema/runtime
gap). Removed that redundant field; element-level count12 remains. This is a
loader correction/retry of the fourth design, not another art redesign. The
next server reports `VFX loaded: 109 ok, 0 failed` in
`/tmp/orrery-fixed-load.log`. Final compare is regenerated after this correction.

Iteration 4 FINAL CHECK after loader correction: final pairs inspected. All
four muzzle clocks and beams now appear, including the first shot; upright
channel and recovery also present. Gold dust breaks up the regular clock rim.
Final runtime log `/tmp/orrery-s3-final-runtime.log`:109 VFX loaded,0 failed.
Remaining: still a regular polygon rather than jagged luminous clock, dimmer
core/bloom, cubic orb, fixed beam overshoots the enemy, channel ring too clean,
static vanilla body/face/arms. Nominal hit samples show shards but no hurt tint;
these pairs verify the four effect events, not precise damage-feedback timing.
Targeting remains a6° cone instead of a piercing cylinder. Four art rounds;
one extra capture retried the fourth round after the loader-only correction.
