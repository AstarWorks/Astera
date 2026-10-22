# Ember Gauntlet s3: PLAN / MEASURE

Reference: vBob-jTmlnc, 54.5–58.8 s. Native 30 fps sheets inspected under scratchpad/pm/flip/vBob-jTmlnc/ember-*; full-size stills under /tmp/ember-measure. Estimates are manually bounded pixels, not segmentation ground truth. Measurements exclude bloom where a solid edge exists; obscured bodies are explicitly marked. Character standing height is 1.8 blocks.

Range: **dash-melee sweep/punch then planted short beam**. Wiki coins: **3**. Importance: 9/10.
Hit evidence: 55.533 s, 55.8 s, 57.3 s. Rounded ticks use `round((frame_time − case.start_s) × 20)`; tolerance ±1 tick (30 fps → 20 tps). Onsets corroborate impact windows, not coin counts: [{'t': 54.999, 's': 53.39}, {'t': 55.324, 's': 58.47}, {'t': 55.533, 's': 85.87}, {'t': 55.649, 's': 52.73}, {'t': 55.905, 's': 128.57}, {'t': 56.009, 's': 66.38}, {'t': 56.3, 's': 58.63}, {'t': 56.996, 's': 73.03}, {'t': 58.192, 's': 72.65}].

| Frame time (s) | Element | Measured pixels / observation | Conversion (formula beside result) | Local ticks | Shape / palette |
|---|---|---|---|---|---|
| 55.550 | body reference | head≈190, feet≈430: H≈240 px, partially covered | 1 H = 1.8 blocks; uncertainty ±25 px | 0–86 | close camera |
| 55.550 | sweep | x≈0–600 width≈600px; band thickness≈55px | 600/240=2.50 H; ×1.8=4.50 blocks. 55/240=0.23 H; ×1.8=0.41 blocks | 17–29 | horizontal crescent; orange flames rise above yellow band |
| 55.550 | impact star | x≈520–790:270px; y≈145–420:275px | 270/240=1.125 H; ×1.8=2.025 blocks. 275/240=1.146 H; ×1.8=2.063 blocks | 20–28 | roughly 9 unequal spikes |
| 56.933–57.033 | cut-ins | 2 native black frames then yellow frames | 2/30×20=1.33 ticks → 1 black tick; yellow begins (57.0−54.5)×20=50 ticks | 49–55 | black → near-solid yellow → pressure streaks |
| 57.300 | ring blast | two complete hoops visible, partial leading flare; body obscured by extreme zoom | No reliable H in this shot; ring height inferred from post-cut wide shot, about 0.8 H ×1.8=1.44 blocks | 56–74 | rings normal to direction of travel; straight flame tail |

## Shot and timing decisions

Camera keys reproduce wide setup, tighter attack framing, impact punch-in and recovery. Camera positions are calibrated estimates from visible body fraction, not an exact solve of the source camera. No unmeasurable coordinates are presented as exact.

Weapon pose: dark cuff and brass nozzle, forearm at waist/chest height, drawn back before striking, forward on straight punches; horizontal rotation for the sweeping fist. Minecraft player body cannot reproduce the source crouch/coat or articulated arm using this content schema; the separate display rig approximates the weapon motion.

The source's hand-drawn fill is mostly opaque yellow with orange edges, sparse dark smoke and a short pale impact centre. Rings are visual pressure effects, not independent coins. VFX are caster-relative to keep display count independent of enemies hit. Explicit damage occurs once per coin with ignore_invulnerability; burn is a separate short DoT.

## CHECK / ACT

Iteration 1: capture requested; comparison pending. Do not interpret content validation as visual acceptance.

Iteration 1 CHECK: pairs inspected (`build/pairs/iteration-1/ember-gauntlet-s3.png`). Sweep is readable but angular and too broad in band thickness; glyph jet and star are missing. Final hoops exist but have no filled blast between them. Caster and target overlap by the third strike; source maintains separation. Camera/impact visible onset is late. ACT: common solid-geometry replacement, narrower sweep band, and capture latency calibration; investigate movement carry-through before the next cast.

Pack reference at 37.200: visible shell about 60 px wide × 140 px high against ~275 px body: width 60/275×1.8=0.393 blocks; height 140/275×1.8=0.916 blocks. Two-part caster-following pack uses 0.5×0.85×0.3 blocks (coarse voxel approximation); no articulated hose. +2 live displays per cast.

Range refinement: straight flame/punch cones narrowed from 50° to 18°; the axial-cone estimate is 2×3.6×tan(9°)=1.140 blocks. The actual engine bounds radial distance, so maximum chord width is 2×3.6×sin(9°)=1.126 blocks (old 50° cone: 2×3.6×sin(25°)=3.043 blocks). Sweeping fist uses a 100° front arc with range 3.0 blocks. These are direct short-range hits, not travelling damage projectiles; visible hoops remain non-damaging.

Cone apex corrected to the nozzle: standing eye height 1.62−0.52=1.10 blocks. The engine tests body centres, so a narrow cone from eye height would miss a nearby torso; this content offset keeps the damage region on the authored flame.

Wind-up/contact separation: sweep visual starts S1 t10, S3 t17, DEF t18; straight punches start S2 t4/t20 and S3 t24; final blast starts S3 t54. Explicit damage ticks unchanged. Impact-spoke elements are delayed inside each VFX (sweep +2t, counter/punch/blast +1t, jet +6t), so visible motion leads contact instead of beginning after it. Native onset references: 36.067 sweep, 42.933 straight smear, 55.333 crescent.

Iteration 2 CHECK: `build/pairs/round-2/ember-gauntlet-s3.png` inspected. Hits now visibly land, but pre-dash snapshot anchors leave the gauntlet/impact behind the caster; jet fill is too thin and impact spokes look mechanical. ACT: caster-following rig/effects, smoother 36° fan samples without a redundant edge slab, fan outer radius 2.65 to match projected width, stronger jet vertical fill, and a native TextDisplay flare over solid backup geometry. S1/S3 impact cameras tighten and hold long enough for the client to expose the shot. Sampled display counts: S1 38, S2 32, S3 55, DEF 50.

## Final CHECK / disposition

A warm-server reload attempt used the obsolete `astera reload` command and was rejected. Its round-3 recordings are stale and excluded as evidence of the revised content. Correct `astera admin reload vfx` and `astera admin reload skills` both returned success before the final captures. No engine files were edited.

Final inspected sheet: `build/pairs/ember-gauntlet-s3.png`. Sweep, straight punch and two-hoop final blast all appear; the three contacts are readable. At 55.55 the initial fan and punch geometry overlap too much. At 57.3 the hoop scale is plausible but the blast is thin, dim and mechanical; the hand-drawn white-hot star is absent from this camera. The close-up and return-wide camera cuts are only approximate. Source diagonal cut-in framing is missing from the spectator view.

Status: partial fidelity, not a match. Capture/review budget exhausted; remaining visual differences are explicitly retained for review rather than marked accepted. S1 has four valid comparisons plus one discarded stale recording. S2 has four capture attempts (one zero-frame failure, one stale recording, two valid comparisons). S3 and DEF each have four attempts (one stale, three valid comparisons).
