# S1 — measured plan

Source 12.800–16.400 s, native 29.97 fps. Range: **melee**, two opposing sweeps.
Caster stays approximately planted; enemy recoils. Intro approach is not a damage dash.
Importance 4/10. Windup 0–11t, contacts 12/43t, recovery through 72t.

Measurements are manual pixel estimates on the 384×216 flip-sheet tiles (±5 px;
body excludes antlers and staff). Compare ratios within the SAME shot: zoom changes
make absolute pixel sizes across shots meaningless. H = 1.8 blocks.

| Frame | Element | Pixel reading / formula | Authoring value | Window |
|---|---|---|---|---|
| 13.400 | Caster body | head-to-foot ≈90 px = 1 H | 1.8 blocks | reference |
| 13.400 | Crescent diameter | ≈210/90 = 2.33 H; 2.33×1.8 = 4.2 blocks | fan tip radius 2.7 (includes grip offset), sweep 180° | 8–17t |
| 13.400 | Crescent bright band | ≈23/90×1.8 = 0.46 blocks | outer 35% of 1.7-block fan = 0.595 blocks | 8–17t |
| 13.400 | Contact separation | ≈150/90×1.8 = 3.0 blocks (projected) | 3.6-block scene; melee range 4.5 | 12t |
| 13.567 | Enemy ring | ≈45/65×1.8 = 1.25 blocks diameter | 8 outward shards, ~1.2 diameter | 12–22t |
| 14.967 | Reverse crescent | ≈270/110×1.8 = 4.42 blocks wide | opposite 180° sweep | 39–48t |
| 14.967 | Staff shaft | ≈120/110×1.8 = 1.96 blocks | 2.0 blocks, dark shaft / gold collar | 36–54t |
| 13.400 / 14.967 | Contact ticks | round((13.400−12.800)×20)=12; round((14.967−12.800)×20)=43 | 12 / 43 | two coins = two damage events |

Colors sampled visually: dominant yellow-gold #FFD447, amber #DC820D, dark
purple-brown #261721; small red/green sparks. Gold fan is opaque at its rim,
translucent inward, with a dark edge. No glow outline. 2t contact hit-stop.
Audio onsets 13.439 and 14.983 support contact timing, not substituted for it.
Camera cuts/punches: 13.333, 14.833–14.900; gradual pullbacks approximated by keys.

Display bound: fan 4 intervals, <=2 subdivisions each ×3 bands/rim =24;
3 rig parts +8 contact shards =35. Motes (16) and poise rig (3) occur between
swings; conservatively <=54 live displays. Particles do not consume displays.

## PDCA

Iteration 1: replaced disconnected glass rods with staff-driven filled fan;
delayed both damage beats to measured contact, removed cast-time damage.
Real-client check pending (shared capture queue).

Iteration 1 CHECK: real-client pair `build/pairs/iteration-1/orrery-staff-s1.png`
reviewed. Soft fan faces were invisible against the day sky; flame trail dominated.
Wide-camera body size was about one quarter of reference; poise rig overlapped
second swing. ACT: solid yellow outer fan plus dark block edge, fewer flames,
7.5-block camera cap, remove overlapping poise rig. Iteration 2 queued.
The pair generator must run AFTER meta.json exists (pre-roll=6 frames); an early
pair without metadata was discarded and regenerated before this assessment.

Iteration 2 CHECK: `build/pairs/iteration-2/orrery-staff-s1.png`: opaque fan
visible, camera scale improved, double-staff removed. Remaining: first arc is
upper arch instead of lower U; radius too large; gold is darker than source;
contact shards/camera appear ~3 ticks behind nominal source. Next ACT:
first sweep through180° (down), second through0° (up), tip radius2.3 instead2.7,
gold-block band, thinner dark edge, capture CAST_LAG_MS210 (was60; +150ms=3t).
This adjusts client display latency, not authored damage tick count.
First lower arc additionally raises its grip to1.9 blocks and sets tip radius2.1:
bottom=1.9−2.1=−0.2 blocks, versus an unacceptable1.1−2.3=−1.2 below floor.
Gold band width=(2.1−1.0)×(1−0.6)=0.44 blocks, close to measured0.46.
This raised-hand pose is a rig approximation; vanilla player's arm cannot follow it.

Iteration 3 CHECK: `build/pairs/iteration-3/orrery-staff-s1.png`: second contact
and short gold sparks visible, but first ~38 ticks show no effects. Same lower
sweep asset is visibly rendered in S2 frame_029, so no confirmed planner defect.
This capture is anomalous; next pass repeats the asset with runtime logging to distinguish a
transient capture failure from a reproducible content failure. Do not claim first hit verified.

Iteration 4 FINAL CHECK: `build/pairs/orrery-staff-s1.png` inspected: first lower
U, second upper arch, both hurt/contact beats and short gold sparks are visible.
The previous missing-first-half result did not recur; saved runtime log
`/tmp/orrery-s1-final-runtime.log` contains no execution exception. No stronger
claim about the prior anomaly's cause is supported.
At the4-pass cap: silhouettes/directions and2 beats are reproduced, NOT a pixel
match. Remaining: polygon edges, dimmer daylight gold, no soft additive glow,
vanilla arm/body pose (raised grip floats above the arm), sparse impact ring,
wind-up weaker than source, target knockback/body proportions differ. Melee
cone4.5 extends beyond the2.4-block forward fan tip: remaining range tuning,
not an engine impossibility. No more source-faithfulness claim than these pairs.
