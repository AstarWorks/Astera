# crimson-chain-axe S3 — measurement / PDCA

Source: Gpj-lvOnAgs, 79.80–86.80s. Native source 854×480 at 30000/1001 fps.
Inspected 24 fps flip sheets for S1/S2; 30 fps sheets for S3/Q, and native case frames.
Pixel estimates below use the 384×216 sheet tiles, excluding timestamp border. H = visible upright body (not weapon/cape); perspective and cape occlusion give about ±15% uncertainty. These are manual estimates, not pixel-perfect masks.
Range: **melee / long chain sweep; aerial recovery**. Caster stays near origin; S2/S3 have airborne poses. Static player body does not reproduce cape/limb articulation.
Timing conversion: tick = round((video_s − 79.8) × 20). Native-frame tolerance ±0.034s; tick rounding ±0.025s.

| Contact | Source second | Tick formula |
|---|---:|---|
| 1 | 80.300 | (80.300 − 79.8) × 20 = 10 |
| 2 | 80.550 | (80.550 − 79.8) × 20 = 15 |
| 3 | 81.400 | (81.400 − 79.8) × 20 = 32 |
| 4 | 82.250 | (82.250 − 79.8) × 20 = 49 |
| 5 | 83.300 | (83.300 − 79.8) × 20 = 70 |

| Frame second | Element | Pixel estimate / body H | Conversion (blocks) | Interval (ticks) |
|---|---|---|---|---|
| 80.33 | low sweep width (span) | 350px / 65px | (350/65) H × 1.8 = 9.692 | 10–21 |
| 80.6 | diagonal band (thickness) | 18px / 85px | (18/85) H × 1.8 = 0.381 | 15–29 |
| 81.47 | vertical crescent (height) | 160px / 75px | (160/75) H × 1.8 = 3.840 | 32–46 |
| 82.27 | crossed slash span (span) | 300px / 75px | (300/75) H × 1.8 = 7.200 | 49–63 |
| 83.3 | burst diameter (diameter) | 190px / 65px | (190/65) H × 1.8 = 5.262 | 70–78 |
| 84.5 | orbit diameter (diameter) | 290px / 60px | (290/60) H × 1.8 = 8.700 | 84–124 |

Palette manually sampled by visual estimate: crimson #E52240 dominant, bone #FFF1DB contact core, #240711 dark rim, faint red bloom. Main curved band ~0.25–0.38 blocks; bright edge 0.055 blocks. Tilt estimate 25°/−35°; descending lances ~25° below horizontal. Contact star 0.6–1.1 blocks. Rig terminal axes: three visible paired heads, 0.35 block width; linked blade curve is approximated by a rigid segmented haft.
Camera size formula: distance ≈ 1.8 / (2 × tan(40°/2) × screen-height fraction). 27% body fraction → 9.16 blocks; close-up 45% → 5.49 blocks. Camera keys approximate shot framing; no roll support. Target separation estimate 2.5 H × 1.8 = 4.5 blocks.

## Evidence and mapping
Five contacts fit the five-coin enhanced S3 candidate. Original name Blade Forest retained; no blade forest actually occurs in this selected attack, so that visual is now reserved for Q.

Audio/cut extraction from video muxed locally with supplied separate M4A (source MP4 is silent). Audio is supporting evidence only: music and narration create onsets.
{"start_s": 79.8, "end_s": 86.8, "waveformStepMs": 10, "onsets": [{"t": 79.812, "s": 38.82}, {"t": 80.439, "s": 28.33}, {"t": 80.694, "s": 32.17}, {"t": 80.949, "s": 28.91}, {"t": 81.634, "s": 75.39}, {"t": 82.238, "s": 60.2}, {"t": 82.807, "s": 54.32}, {"t": 83.167, "s": 31.97}, {"t": 84.049, "s": 38.86}, {"t": 84.479, "s": 50.45}, {"t": 85.024, "s": 76.99}, {"t": 86.615, "s": 70.01}], "cuts": [{"t": 80.213, "s": 17.7}, {"t": 80.314, "s": 18.3}, {"t": 81.381, "s": 14.5}, {"t": 82.149, "s": 16.6}, {"t": 82.249, "s": 16.1}, {"t": 83.317, "s": 22.9}, {"t": 83.383, "s": 19.7}, {"t": 83.417, "s": 24.1}, {"t": 83.884, "s": 42.7}, {"t": 83.951, "s": 24.5}]}

## Iterations
1. PLAN/DO: replace immediate damage / oversized self AoE, match contact ticks, author block rig + filled fan + dark rim; remove every glow_color. Capture pending.

## Placement / art-direction checklist
Importance 8/10. At 80.33s: low sweep centre ≈ (0, 0.4H); tip contact ≈ (2.5H, 0.61H); recovery ring centre ≈ (0, 1.2H). These are manual screen-space estimates relative to the caster, not inferred world coordinates from a calibrated camera.
Coordinate conversions (H=1.8): 0.4H×1.8=0.72; 0.55H×1.8=0.99; 0.61H×1.8=1.098; 1.2H×1.8=2.16; 1.55H×1.8=2.79; 2.5H×1.8=4.5; −1.1H×1.8=−1.98; 2.2H×1.8=3.96; 3H×1.8=5.4 blocks.
Counts: 4 distinct chain passes + 1 blast; recovery 3 tilted orbit planes.
Wind-up ends on the first contact tick; target star starts on that same tick; the rig trajectory uses out_quad, the strike hold is 2–3 ticks, and the trail loses alpha over 5 ticks. The final cinematic fades remove bright core before the residual field. Main threat is the blade path, secondary element is the contact star; dark rims support the pale floor. No glow outlines. Display accounting is in the weapon report.

## Real-client CHECK / ACT log

- Iteration 1: equipped weapon, 1280×720, default 5s settle / distance 6. Inspected `build/pairs/crimson-chain-axe-s3-iteration-1.png`. S1 frames 8–51 are identical (2.2s client render stall), so that run cannot establish contact timing. Other skills reveal repeated unrequested S1 rigs/thrusts and zero dummy HP loss.
- Engine finding: `plugin/items/.../PaperItemInputListener.kt:50–55` treats every caster-origin ENTITY_ATTACK damage event as LEFT_CLICK, including damage sent by `PaperEntityDamageGateway.apply`. It dispatches the held item's S1 and cancels the damage event. The resulting extra S1 effects contaminate other casts. No Kotlin changed. This prevents an end-to-end equipped-weapon pass.
- Iteration 2: 854×480, render distance 2, settle 20s, capture lag compensation 210ms. Narrowed the fan from 1.3 to 0.35 blocks, corrected sweep plane/contact direction, lowered caster-forward contact anchors by the 1.62-block eye height, recentered Q opening camera, replaced raw text burst with glyph quads. Images saved as `build/pairs/crimson-chain-axe-s3-iteration-2.png`. Q/S1/S3 still equipped; S2 was cleared after item grant and before cast, and its HP changed 200 → 196.064 → 191.636 → 184.256 (three hits).
- Iteration 3 setup: runtime-only copy of capture script clears Caster immediately after `give_item`; skill is still invoked through the actual server/client. Committed item bindings are unchanged. These are isolated-skill diagnostics, not proof that normal equipped play works. Replaced trail text backgrounds with solid block bands, compensated observed glyph baseline offset, removed per-element forest jitter so heads meet shafts, and fixed the coil's group rotation moving its centre. This fixture distinction must remain visible in the final report.
