# Ember Gauntlet s1: PLAN / MEASURE

Reference: vBob-jTmlnc, 35.5–38.5 s. Native 30 fps sheets inspected under scratchpad/pm/flip/vBob-jTmlnc/ember-*; full-size stills under /tmp/ember-measure. Estimates are manually bounded pixels, not segmentation ground truth. Measurements exclude bloom where a solid edge exists; obscured bodies are explicitly marked. Character standing height is 1.8 blocks.

Range: **dash-melee sweep then short ranged flame jet; entry before first hit; planted for jet**. Wiki coins: **2**. Importance: 3/10.
Hit evidence: 36.133 s, 37.3 s. Rounded ticks use `round((frame_time − case.start_s) × 20)`; tolerance ±1 tick (30 fps → 20 tps). Onsets corroborate impact windows, not coin counts: [{'t': 35.523, 's': 19.64}, {'t': 36.208, 's': 128.9}, {'t': 36.87, 's': 60.47}, {'t': 36.974, 's': 21.55}, {'t': 37.241, 's': 27.31}].

| Frame time (s) | Element | Measured pixels / observation | Conversion (formula beside result) | Local ticks | Shape / palette |
|---|---|---|---|---|---|
| 36.500 | body reference | head≈185, sole≈335: H≈150 px | 1 H = 1.8 blocks | 0–60 | black coat, brass fist |
| 36.500 | sweep width / height | x≈10–400:390 px; y≈170–285:115 px | 390/150=2.60 H; ×1.8=4.68 blocks. 115/150=0.77 H; ×1.8=1.38 blocks | 11–21 | yellow #FFD426, orange #FF730A; curved 220–250° |
| 36.500 | sweep vertical placement | middle≈230; sole≈335:105 px | 105/150=0.70 H; ×1.8=1.26 blocks above ground | 11–21 | caster-centred, extends forward |
| 37.200 | jet body reference | cropped caster head≈175, sole≈450: H≈275 px | 1 H = 1.8 blocks; ±15 px from crop | 29–40 | arm points approximately 0° forward |
| 37.200 | jet silhouette | x≈180–620:440 px; y≈130–300:170 px | 440/275=1.60 H; ×1.8=2.88 blocks. 170/275=0.62 H; ×1.8=1.11 blocks | 29–40 | ragged solid yellow fill with orange bloom |
| 36.833 | gauntlet | approx 50 px length against H≈210 px | 50/210=0.24 H; ×1.8=0.43 blocks | 24–40 | forward brass nozzle; total arm+gauntlet longer |

## Shot and timing decisions

Camera keys reproduce wide setup, tighter attack framing, impact punch-in and recovery. Camera positions are calibrated estimates from visible body fraction, not an exact solve of the source camera. No unmeasurable coordinates are presented as exact.

Weapon pose: dark cuff and brass nozzle, forearm at waist/chest height, drawn back before striking, forward on straight punches; horizontal rotation for the sweeping fist. Minecraft player body cannot reproduce the source crouch/coat or articulated arm using this content schema; the separate display rig approximates the weapon motion.

The source's hand-drawn fill is mostly opaque yellow with orange edges, sparse dark smoke and a short pale impact centre. Rings are visual pressure effects, not independent coins. VFX are caster-relative to keep display count independent of enemies hit. Explicit damage occurs once per coin with ignore_invulnerability; burn is a separate short DoT.

## CHECK / ACT

Iteration 1: capture requested; comparison pending. Do not interpret content validation as visual acceptance.

Iteration 1 CHECK: `build/pairs/iteration-1/ember-gauntlet-s1.png` inspected. Sweep fill almost absent; flame particles conceal the contact point; camera subjects too small; jet starts late. ACT: crisp yellow edge plus soft fan, 22° tilt, sparse particles, contact star, camera distances ×0.72. Jet visual begins t29; damage remains t36. Recapture at 854×480 and render distance 2 to reduce dropped client frames (first capture had 34 unique images / 67 output frames).

Revision clarification: `style: both` only creates opaque trail bands when each band has a `block`; supplied orange/yellow concrete blocks explicitly. Read-only planner inspection confirmed this requirement. No Kotlin changes.

Iteration 2 CHECK: `build/pairs/iteration-2/ember-gauntlet-s1.png` inspected. Solid sweep now visible and roughly correctly scaled, but polygonal; jet fill and contact glyph remain invisible despite server TextDisplays. Source hit1 is already active where capture still shows wind-up (about 3–4 ticks late), and the close cut is missed. ACT: replace glyph quads with solid emissive flame tongues and eight-spoke impact geometry; narrow sweep to one filled band to preserve budget. Next capture uses CAST_LAG_MS=210 (previous 60; +150 ms = 3 frames at 20 fps) to calibrate observed client delay, not to change damage timing. Sampled sweep: 21 block displays + 19 text displays = 40, no item displays. Different entity types were queried sequentially, so this is an observed approximate peak, not an atomic total.

Pack reference at 37.200: visible shell about 60 px wide × 140 px high against ~275 px body: width 60/275×1.8=0.393 blocks; height 140/275×1.8=0.916 blocks. Two-part caster-following pack uses 0.5×0.85×0.3 blocks (coarse voxel approximation); no articulated hose. +2 live displays per cast.

Range refinement: straight flame/punch cones narrowed from 50° to 18°; the axial-cone estimate is 2×3.6×tan(9°)=1.140 blocks. The actual engine bounds radial distance, so maximum chord width is 2×3.6×sin(9°)=1.126 blocks (old 50° cone: 2×3.6×sin(25°)=3.043 blocks). Sweeping fist uses a 100° front arc with range 3.0 blocks. These are direct short-range hits, not travelling damage projectiles; visible hoops remain non-damaging.

Cone apex corrected to the nozzle: standing eye height 1.62−0.52=1.10 blocks. The engine tests body centres, so a narrow cone from eye height would miss a nearby torso; this content offset keeps the damage region on the authored flame.

Wind-up/contact separation: sweep visual starts S1 t10, S3 t17, DEF t18; straight punches start S2 t4/t20 and S3 t24; final blast starts S3 t54. Explicit damage ticks unchanged. Impact-spoke elements are delayed inside each VFX (sweep +2t, counter/punch/blast +1t, jet +6t), so visible motion leads contact instead of beginning after it. Native onset references: 36.067 sweep, 42.933 straight smear, 55.333 crescent.

Iteration 3 CHECK: `build/pairs/round-2/ember-gauntlet-s1.png` inspected. Hits now visibly land, but pre-dash snapshot anchors leave the gauntlet/impact behind the caster; jet fill is too thin and impact spokes look mechanical. ACT: caster-following rig/effects, smoother 36° fan samples without a redundant edge slab, fan outer radius 2.65 to match projected width, stronger jet vertical fill, and a native TextDisplay flare over solid backup geometry. S1/S3 impact cameras tighten and hold long enough for the client to expose the shot. Sampled display counts: S1 38, S2 32, S3 55, DEF 50.

Before final S1 recapture: added five native TextDisplay triangular flame faces over the solid backup jet (no outline glow), plus one native text impact flare. This tests the existing text path separately from the invisible quad-glyph path. Display budget remains below the sweep's peak. Timing/hit regions are unchanged.

## Final CHECK / disposition

A warm-server reload attempt used the obsolete `astera reload` command and was rejected. Its round-3 recordings are stale and excluded as evidence of the revised content. Correct `astera admin reload vfx` and `astera admin reload skills` both returned success before the final captures. No engine files were edited.

Final inspected sheet: `build/pairs/ember-gauntlet-s1.png`. The wider crescent and jet now render and follow the dash. At 36.15 the capture is still in wind-up; the sweep is visible at 36.35. Jet/contact at 37.3 is much dimmer and narrower in screen area than the reference, and the punch-in does not match. The arc remains polygonal, the impact spokes mechanical, and the fire particles linger through recovery. Native text flares do not provide a reliable source-facing silhouette.

Status: partial fidelity, not a match. Capture/review budget exhausted; remaining visual differences are explicitly retained for review rather than marked accepted. S1 has four valid comparisons plus one discarded stale recording. S2 has four capture attempts (one zero-frame failure, one stale recording, two valid comparisons). S3 and DEF each have four attempts (one stale, three valid comparisons).
