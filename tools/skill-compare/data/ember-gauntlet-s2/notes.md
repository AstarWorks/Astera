# Ember Gauntlet s2: PLAN / MEASURE

Reference: vBob-jTmlnc, 42.7–44.7 s. Native 30 fps sheets inspected under scratchpad/pm/flip/vBob-jTmlnc/ember-*; full-size stills under /tmp/ember-measure. Estimates are manually bounded pixels, not segmentation ground truth. Measurements exclude bloom where a solid edge exists; obscured bodies are explicitly marked. Character standing height is 1.8 blocks.

Range: **dash-melee, two straight punches with short flame pressure trails**. Wiki coins: **2**. Importance: 5/10.
Hit evidence: 42.967 s, 43.767 s. Rounded ticks use `round((frame_time − case.start_s) × 20)`; tolerance ±1 tick (30 fps → 20 tps). Onsets corroborate impact windows, not coin counts: [{'t': 43.013, 's': 128.72}, {'t': 43.548, 's': 90.32}, {'t': 43.698, 's': 71.74}, {'t': 44.407, 's': 68.25}].

| Frame time (s) | Element | Measured pixels / observation | Conversion (formula beside result) | Local ticks | Shape / palette |
|---|---|---|---|---|---|
| 43.100 | crouched body reference | head≈220, feet≈340: visible120 px; standing equivalent≈170 px | 1 standing H = 1.8 blocks; crouch≈120/170=0.71 H | 0–40 | low planted stance after entry |
| 43.100 | straight flame smear | x≈170–520:350 px; y≈245–290:45 px | 350/170=2.06 H; ×1.8=3.71 blocks. 45/170=0.26 H; ×1.8=0.48 blocks | 5–13 | 0° horizontal orange/yellow taper |
| 43.100 | impact star | x≈430–600:170 px; y≈180–345:165 px | 170/170=1 H; ×1.8=1.8 blocks. 165/170=0.97 H; ×1.8=1.75 blocks | 6–13 | roughly 8–10 unequal spokes; white-yellow centre |
| 43.900 | second punch / camera | frame largely filled; body obscured | Do not derive world scale from zoomed obscured body; retain preceding shot calibration | 22–29 | larger bright star, white pressure crescent behind it |
| 43.500 | smoke | plume about 1 standing body high | 1×1.8=1.8 blocks; diffuse extent only | 14–20 | dark gray; rises behind shoulder |

## Shot and timing decisions

Camera keys reproduce wide setup, tighter attack framing, impact punch-in and recovery. Camera positions are calibrated estimates from visible body fraction, not an exact solve of the source camera. No unmeasurable coordinates are presented as exact.

Weapon pose: dark cuff and brass nozzle, forearm at waist/chest height, drawn back before striking, forward on straight punches; horizontal rotation for the sweeping fist. Minecraft player body cannot reproduce the source crouch/coat or articulated arm using this content schema; the separate display rig approximates the weapon motion.

The source's hand-drawn fill is mostly opaque yellow with orange edges, sparse dark smoke and a short pale impact centre. Rings are visual pressure effects, not independent coins. VFX are caster-relative to keep display count independent of enemies hit. Explicit damage occurs once per coin with ignore_invulnerability; burn is a separate short DoT.

## CHECK / ACT

Iteration 1: capture requested; comparison pending. Do not interpret content validation as visual acceptance.

Attempt 1: real client cast completed, but `vfx-shot` reported `no frames extracted`; no pair sheet was accepted or fabricated. The broad nearby-entity query reached 64, which was not a display-only count and cannot establish a display budget. Next attempt samples block/text/item displays separately. Shared revisions from the inspected S1/S3 pairs replace invisible quad glyphs with opaque geometry and recalibrate client lag. Four capture attempts remain the upper bound; failures are distinguished from inspected comparisons.

Pack reference at 37.200: visible shell about 60 px wide × 140 px high against ~275 px body: width 60/275×1.8=0.393 blocks; height 140/275×1.8=0.916 blocks. Two-part caster-following pack uses 0.5×0.85×0.3 blocks (coarse voxel approximation); no articulated hose. +2 live displays per cast.

Range refinement: straight flame/punch cones narrowed from 50° to 18°; the axial-cone estimate is 2×3.6×tan(9°)=1.140 blocks. The actual engine bounds radial distance, so maximum chord width is 2×3.6×sin(9°)=1.126 blocks (old 50° cone: 2×3.6×sin(25°)=3.043 blocks). Sweeping fist uses a 100° front arc with range 3.0 blocks. These are direct short-range hits, not travelling damage projectiles; visible hoops remain non-damaging.

Cone apex corrected to the nozzle: standing eye height 1.62−0.52=1.10 blocks. The engine tests body centres, so a narrow cone from eye height would miss a nearby torso; this content offset keeps the damage region on the authored flame.

Wind-up/contact separation: sweep visual starts S1 t10, S3 t17, DEF t18; straight punches start S2 t4/t20 and S3 t24; final blast starts S3 t54. Explicit damage ticks unchanged. Impact-spoke elements are delayed inside each VFX (sweep +2t, counter/punch/blast +1t, jet +6t), so visible motion leads contact instead of beginning after it. Native onset references: 36.067 sweep, 42.933 straight smear, 55.333 crescent.

Iteration 2 CHECK: `build/pairs/round-2/ember-gauntlet-s2.png` inspected. Hits now visibly land, but pre-dash snapshot anchors leave the gauntlet/impact behind the caster; jet fill is too thin and impact spokes look mechanical. ACT: caster-following rig/effects, smoother 36° fan samples without a redundant edge slab, fan outer radius 2.65 to match projected width, stronger jet vertical fill, and a native TextDisplay flare over solid backup geometry. S1/S3 impact cameras tighten and hold long enough for the client to expose the shot. Sampled display counts: S1 38, S2 32, S3 55, DEF 50.

## Final CHECK / disposition

A warm-server reload attempt used the obsolete `astera reload` command and was rejected. Its round-3 recordings are stale and excluded as evidence of the revised content. Correct `astera admin reload vfx` and `astera admin reload skills` both returned success before the final captures. No engine files were edited.

Final inspected sheet: `build/pairs/ember-gauntlet-s2.png`. Both straight punches visibly connect; no extra damaging hoop. At 43.0 the contact is present, but the source star and trailing smear are far brighter/larger. The second effect is already visible in the 43.65 recovery comparison, indicating residual render/director timing drift. The pressure hoop is rigid and short-lived compared with the source. The back pack separates visually from the moving body during wind-up.

Status: partial fidelity, not a match. Capture/review budget exhausted; remaining visual differences are explicitly retained for review rather than marked accepted. S1 has four valid comparisons plus one discarded stale recording. S2 has four capture attempts (one zero-frame failure, one stale recording, two valid comparisons). S3 and DEF each have four attempts (one stale, three valid comparisons).
