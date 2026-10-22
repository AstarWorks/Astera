# Ember Gauntlet def: PLAN / MEASURE

Reference: vBob-jTmlnc, 47.5–49.2 s. Native 30 fps sheets inspected under scratchpad/pm/flip/vBob-jTmlnc/ember-*; full-size stills under /tmp/ember-measure. Estimates are manually bounded pixels, not segmentation ground truth. Measurements exclude bloom where a solid edge exists; obscured bodies are explicitly marked. Character standing height is 1.8 blocks.

Range: **melee counter sweep; caster planted, opponent approaches**. Wiki coins: **1**. Importance: 4/10.
Hit evidence: 48.467 s. Rounded ticks use `round((frame_time − case.start_s) × 20)`; tolerance ±1 tick (30 fps → 20 tps). Onsets corroborate impact windows, not coin counts: [{'t': 47.848, 's': 60.88}, {'t': 48.522, 's': 87.72}, {'t': 48.812, 's': 34.16}].

| Frame time (s) | Element | Measured pixels / observation | Conversion (formula beside result) | Local ticks | Shape / palette |
|---|---|---|---|---|---|
| 48.600 | body reference | standing enemy head≈180, sole≈310: H≈130px | 1 H =1.8 blocks; same-depth approximate calibration | 0–34 | caster braces while opponent approaches |
| 48.600 | counter sweep width | x≈120–445:325px | 325/130=2.50 H; ×1.8=4.50 blocks | 19–30 | yellow-orange horizontal sweep |
| 48.600 | counter hoop | x≈295–415, y≈185–300:115px tall | 115/130=0.885 H; ×1.8=1.592 blocks | 19–27 | one vertical pressure loop, visual only |
| 48.600 | sweep height above ground | centre≈220, ground≈310:90px | 90/130=0.692 H; ×1.8=1.246 blocks | 19–30 | brass fist stays near chest |

## Shot and timing decisions

Camera keys reproduce wide setup, tighter attack framing, impact punch-in and recovery. Camera positions are calibrated estimates from visible body fraction, not an exact solve of the source camera. No unmeasurable coordinates are presented as exact.

Weapon pose: dark cuff and brass nozzle, forearm at waist/chest height, drawn back before striking, forward on straight punches; horizontal rotation for the sweeping fist. Minecraft player body cannot reproduce the source crouch/coat or articulated arm using this content schema; the separate display rig approximates the weapon motion.

The source's hand-drawn fill is mostly opaque yellow with orange edges, sparse dark smoke and a short pale impact centre. Rings are visual pressure effects, not independent coins. VFX are caster-relative to keep display count independent of enemies hit. Explicit damage occurs once per coin with ignore_invulnerability; burn is a separate short DoT.

## CHECK / ACT

Iteration 1: capture requested; comparison pending. Do not interpret content validation as visual acceptance.

Mapping confidence: medium. Incoming enemy action and repeated single retaliation support counter identification; the footage has no readable skill label. 49.9–51.6 contains additional counter exchanges, not extra coins in this case. Current guard is a timed manual response: reactive clash/counter triggering is an engine gap.

Native-frame confirmation: `ember-def-native/flip_001.jpg` (30 fps, 48.300–48.933) inspected. Single white/yellow contact glint at 48.467, flame sweep expands at 48.500, one pressure loop is clear at 48.533–48.633, tail fades at 48.900. `round((48.467−47.5)×20)=19` is the one damage tick; the ring is not a second hit.

Iteration 1 CHECK: `build/pairs/iteration-1/ember-gauntlet-def.png` inspected. Counter appears several ticks late; sweep reads as a steep C rather than a shallow horizontal ellipse; pressure hoop missing. ACT: reduce sweep plane tilt from 22° to 8°, add DEF-only 12-segment upright pressure hoop, use solid impact spokes, apply the same client latency calibration. The source incoming attack is not synthesized by this defensive skill; only the counter is implemented.

Pack reference at 37.200: visible shell about 60 px wide × 140 px high against ~275 px body: width 60/275×1.8=0.393 blocks; height 140/275×1.8=0.916 blocks. Two-part caster-following pack uses 0.5×0.85×0.3 blocks (coarse voxel approximation); no articulated hose. +2 live displays per cast.

Range refinement: straight flame/punch cones narrowed from 50° to 18°; the axial-cone estimate is 2×3.6×tan(9°)=1.140 blocks. The actual engine bounds radial distance, so maximum chord width is 2×3.6×sin(9°)=1.126 blocks (old 50° cone: 2×3.6×sin(25°)=3.043 blocks). Sweeping fist uses a 100° front arc with range 3.0 blocks. These are direct short-range hits, not travelling damage projectiles; visible hoops remain non-damaging.

Cone apex corrected to the nozzle: standing eye height 1.62−0.52=1.10 blocks. The engine tests body centres, so a narrow cone from eye height would miss a nearby torso; this content offset keeps the damage region on the authored flame.

Wind-up/contact separation: sweep visual starts S1 t10, S3 t17, DEF t18; straight punches start S2 t4/t20 and S3 t24; final blast starts S3 t54. Explicit damage ticks unchanged. Impact-spoke elements are delayed inside each VFX (sweep +2t, counter/punch/blast +1t, jet +6t), so visible motion leads contact instead of beginning after it. Native onset references: 36.067 sweep, 42.933 straight smear, 55.333 crescent.

Iteration 2 CHECK: `build/pairs/round-2/ember-gauntlet-def.png` inspected. Hits now visibly land, but pre-dash snapshot anchors leave the gauntlet/impact behind the caster; jet fill is too thin and impact spokes look mechanical. ACT: caster-following rig/effects, smoother 36° fan samples without a redundant edge slab, fan outer radius 2.65 to match projected width, stronger jet vertical fill, and a native TextDisplay flare over solid backup geometry. S1/S3 impact cameras tighten and hold long enough for the client to expose the shot. Sampled display counts: S1 38, S2 32, S3 55, DEF 50.

## Final CHECK / disposition

A warm-server reload attempt used the obsolete `astera reload` command and was rejected. Its round-3 recordings are stale and excluded as evidence of the revised content. Correct `astera admin reload vfx` and `astera admin reload skills` both returned success before the final captures. No engine files were edited.

Final inspected sheet: `build/pairs/ember-gauntlet-def.png`. The single retaliation and upright hoop appear around 48.5–48.6. The source sweep is flatter, softer and more intense; the capture has a tall angular fan and a weak hoop against the target. The source incoming attacker movement is absent because the target is intentionally AI-free. Particles persist after the source tail fades. The skill is a timed manual guard, not a reactive counter.

Status: partial fidelity, not a match. Capture/review budget exhausted; remaining visual differences are explicitly retained for review rather than marked accepted. S1 has four valid comparisons plus one discarded stale recording. S2 has four capture attempts (one zero-frame failure, one stale recording, two valid comparisons). S3 and DEF each have four attempts (one stale, three valid comparisons).
