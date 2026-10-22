# crimson-chain-axe S2 — measurement / PDCA

Source: Gpj-lvOnAgs, 96.00–101.50s. Native source 854×480 at 30000/1001 fps.
Inspected 24 fps flip sheets for S1/S2; 30 fps sheets for S3/Q, and native case frames.
Pixel estimates below use the 384×216 sheet tiles, excluding timestamp border. H = visible upright body (not weapon/cape); perspective and cape occlusion give about ±15% uncertainty. These are manual estimates, not pixel-perfect masks.
Range: **ranged lances → melee chain sweep**. Caster stays near origin; S2/S3 have airborne poses. Static player body does not reproduce cape/limb articulation.
Timing conversion: tick = round((video_s − 96) × 20). Native-frame tolerance ±0.034s; tick rounding ±0.025s.

| Contact | Source second | Tick formula |
|---|---:|---|
| 1 | 97.500 | (97.500 − 96) × 20 = 30 |
| 2 | 98.800 | (98.800 − 96) × 20 = 56 |
| 3 | 100.050 | (100.050 − 96) × 20 = 81 |

| Frame second | Element | Pixel estimate / body H | Conversion (blocks) | Interval (ticks) |
|---|---|---|---|---|
| 97.25 | spoke star diameter (diameter) | 150px / 60px | (150/60) H × 1.8 = 4.500 | 0–27 |
| 97.58 | descending lance (length) | 300px / 65px | (300/65) H × 1.8 = 8.308 | 31–45 |
| 97.58 | two lances separation (gap) | 15px / 65px | (15/65) H × 1.8 = 0.415 | 31–45 |
| 98.83 | crescent width (span) | 330px / 70px | (330/70) H × 1.8 = 8.486 | 56–69 |
| 100.17 | rising crescent (height) | 200px / 80px | (200/80) H × 1.8 = 4.500 | 81–95 |

Palette manually sampled by visual estimate: crimson #E52240 dominant, bone #FFF1DB contact core, #240711 dark rim, faint red bloom. Main curved band ~0.25–0.38 blocks; bright edge 0.055 blocks. Tilt estimate 25°/−35°; descending lances ~25° below horizontal. Contact star 0.6–1.1 blocks. Rig terminal axes: three visible paired heads, 0.35 block width; linked blade curve is approximated by a rigid segmented haft.
Camera size formula: distance ≈ 1.8 / (2 × tan(40°/2) × screen-height fraction). 27% body fraction → 9.16 blocks; close-up 45% → 5.49 blocks. Camera keys approximate shot framing; no roll support. Target separation estimate 2.5 H × 1.8 = 4.5 blocks.

## Evidence and mapping
Two diagonal lances share one impact window; the later caster-centred glow is a wind-up, not another target hit. Three contacts fit Je m’abjecte, but tier/name identification remains uncertain. Original name Hooked Lunge retained; the old seven-block dash contradicted the stationary caster.

Audio/cut extraction from video muxed locally with supplied separate M4A (source MP4 is silent). Audio is supporting evidence only: music and narration create onsets.
{"start_s": 96.0, "end_s": 101.5, "waveformStepMs": 10, "onsets": [{"t": 97.463, "s": 38.76}, {"t": 97.776, "s": 27.5}, {"t": 98.45, "s": 54.22}, {"t": 98.577, "s": 44.49}, {"t": 99.053, "s": 32.61}, {"t": 100.11, "s": 43.85}, {"t": 100.609, "s": 28.41}, {"t": 101.12, "s": 56.51}, {"t": 101.271, "s": 27.49}, {"t": 101.387, "s": 25.13}], "cuts": []}

## Iterations
1. PLAN/DO: replace immediate damage / oversized self AoE, match contact ticks, author block rig + filled fan + dark rim; remove every glow_color. Capture pending.

## Placement / art-direction checklist
Importance 6/10. At 97.58s: lance endpoint ≈ (2.5H, 0.61H); starts above-left ≈ (−1.1H, 2.2H); spoke centre ≈ (0, 1.55H). These are manual screen-space estimates relative to the caster, not inferred world coordinates from a calibrated camera.
Coordinate conversions (H=1.8): 0.4H×1.8=0.72; 0.55H×1.8=0.99; 0.61H×1.8=1.098; 1.2H×1.8=2.16; 1.55H×1.8=2.79; 2.5H×1.8=4.5; −1.1H×1.8=−1.98; 2.2H×1.8=3.96; 3H×1.8=5.4 blocks.
Counts: 8 spokes; 2 simultaneous lances = 1 hit; 1 sweep; 1 rising cut.
Wind-up ends on the first contact tick; target star starts on that same tick; the rig trajectory uses out_quad, the strike hold is 2–3 ticks, and the trail loses alpha over 5 ticks. The final cinematic fades remove bright core before the residual field. Main threat is the blade path, secondary element is the contact star; dark rims support the pale floor. No glow outlines. Display accounting is in the weapon report.

Native-rate cut-in check: `crimson-cut-s2/flip_001.jpg` puts the first target flash at 97.500s, so first contact moved from tick 31 to tick 30. 97.367s is the one-frame camera smear, not a contact.

Full native-frame revisit (cache frame_00024, ~96.80s, resized 1280×720): upright enemy hat-to-feet H≈170px; spoke centre (636,250), ground y≈650, diameter≈400px. Centre height=(650−250)/170×1.8=4.235 blocks → authored 4.3, replacing the low 2.8-block estimate. Diameter=400/170×1.8=4.235 blocks (spoke arms authored 2.4, diameter4.8; uncertainty includes perspective). Red coil spans x≈300–980: (980−300)/170×1.8=7.2 blocks, radius3.6. Coil height≈5 blocks and1.5 turns. Display count 8 spokes+24 red segments+24 dark rims=56; all end before tick30 damage.

Silhouette check at 96.80s: the red coil surrounds the high radial centre in the vertical image plane; a vertical-axis helix gave horizontal ovals. Tilt the helix 90° around local forward Z so its axis points toward the side camera. Centre y=4.3, radius=3.6 → vertical bounds 4.3±3.6 = 0.7..7.9 blocks. Depth=1.0 is an unmeasured narrow-depth approximation; 1.5 turns × 2π × 3.6 / 24 = 1.414 blocks per segment (use 1.5 for slight overlap).

## Real-client CHECK / ACT log

- Iteration 1: equipped weapon, 1280×720, default 5s settle / distance 6. Inspected `build/pairs/crimson-chain-axe-s2-iteration-1.png`. S1 frames 8–51 are identical (2.2s client render stall), so that run cannot establish contact timing. Other skills reveal repeated unrequested S1 rigs/thrusts and zero dummy HP loss.
- Engine finding: `plugin/items/.../PaperItemInputListener.kt:50–55` treats every caster-origin ENTITY_ATTACK damage event as LEFT_CLICK, including damage sent by `PaperEntityDamageGateway.apply`. It dispatches the held item's S1 and cancels the damage event. The resulting extra S1 effects contaminate other casts. No Kotlin changed. This prevents an end-to-end equipped-weapon pass.
- Iteration 2: 854×480, render distance 2, settle 20s, capture lag compensation 210ms. Narrowed the fan from 1.3 to 0.35 blocks, corrected sweep plane/contact direction, lowered caster-forward contact anchors by the 1.62-block eye height, recentered Q opening camera, replaced raw text burst with glyph quads. Images saved as `build/pairs/crimson-chain-axe-s2-iteration-2.png`. Q/S1/S3 still equipped; S2 was cleared after item grant and before cast, and its HP changed 200 → 196.064 → 191.636 → 184.256 (three hits).
- Iteration 3 setup: runtime-only copy of capture script clears Caster immediately after `give_item`; skill is still invoked through the actual server/client. Committed item bindings are unchanged. These are isolated-skill diagnostics, not proof that normal equipped play works. Replaced trail text backgrounds with solid block bands, compensated observed glyph baseline offset, removed per-element forest jitter so heads meet shafts, and fixed the coil's group rotation moving its centre. This fixture distinction must remain visible in the final report.
