# crimson-chain-axe S1 — measurement / PDCA

Source: Gpj-lvOnAgs, 89.10–92.30s. Native source 854×480 at 30000/1001 fps.
Inspected 24 fps flip sheets for S1/S2; 30 fps sheets for S3/Q, and native case frames.
Pixel estimates below use the 384×216 sheet tiles, excluding timestamp border. H = visible upright body (not weapon/cape); perspective and cape occlusion give about ±15% uncertainty. These are manual estimates, not pixel-perfect masks.
Range: **melee / chain thrust**. Caster stays near origin; S2/S3 have airborne poses. Static player body does not reproduce cape/limb articulation.
Timing conversion: tick = round((video_s − 89.1) × 20). Native-frame tolerance ±0.034s; tick rounding ±0.025s.

| Contact | Source second | Tick formula |
|---|---:|---|
| 1 | 89.550 | (89.550 − 89.1) × 20 = 9 |
| 2 | 89.950 | (89.950 − 89.1) × 20 = 17 |
| 3 | 90.900 | (90.900 − 89.1) × 20 = 36 |

| Frame second | Element | Pixel estimate / body H | Conversion (blocks) | Interval (ticks) |
|---|---|---|---|---|
| 89.55 | upper crescent width (span) | 300px / 65px | (300/65) H × 1.8 = 8.308 | 9–23 |
| 89.55 | crescent band (thickness) | 9px / 65px | (9/65) H × 1.8 = 0.249 | 9–23 |
| 89.95 | contact star (diameter) | 45px / 90px | (45/90) H × 1.8 = 0.900 | 17–24 |
| 90.9 | horizontal thrust (length) | 330px / 95px | (330/95) H × 1.8 = 6.253 | 36–54 |
| 89.33 | axe link (width) | 14px / 80px | (14/80) H × 1.8 = 0.315 | 4–9 |

Palette manually sampled by visual estimate: crimson #E52240 dominant, bone #FFF1DB contact core, #240711 dark rim, faint red bloom. Main curved band ~0.25–0.38 blocks; bright edge 0.055 blocks. Tilt estimate 25°/−35°; descending lances ~25° below horizontal. Contact star 0.6–1.1 blocks. Rig terminal axes: three visible paired heads, 0.35 block width; linked blade curve is approximated by a rigid segmented haft.
Camera size formula: distance ≈ 1.8 / (2 × tan(40°/2) × screen-height fraction). 27% body fraction → 9.16 blocks; close-up 45% → 5.49 blocks. Camera keys approximate shot framing; no roll support. Target separation estimate 2.5 H × 1.8 = 4.5 blocks.

## Evidence and mapping
Three visible contacts at upper crescent, close return, and horizontal chain thrust. Je m’expulse (3 coins) is a tentative S1 candidate; not enough HUD evidence to prove the label.

Audio/cut extraction from video muxed locally with supplied separate M4A (source MP4 is silent). Audio is supporting evidence only: music and narration create onsets.
{"start_s": 89.1, "end_s": 92.3, "waveformStepMs": 10, "onsets": [{"t": 89.228, "s": 27.68}, {"t": 90.075, "s": 22.76}, {"t": 90.261, "s": 21.07}, {"t": 90.923, "s": 21.79}, {"t": 91.283, "s": 102.44}, {"t": 91.817, "s": 36.58}, {"t": 92.072, "s": 27.39}], "cuts": [{"t": 89.957, "s": 12.1}, {"t": 90.791, "s": 13.8}, {"t": 90.857, "s": 18.9}, {"t": 91.291, "s": 14.8}]}

## Iterations
1. PLAN/DO: replace immediate damage / oversized self AoE, match contact ticks, author block rig + filled fan + dark rim; remove every glow_color. Capture pending.

## Placement / art-direction checklist
Importance 4/10. At 89.55s: crescent centre ≈ (0, 0.55H); endpoint ≈ (2.5H, 0.61H). These are manual screen-space estimates relative to the caster, not inferred world coordinates from a calibrated camera.
Coordinate conversions (H=1.8): 0.4H×1.8=0.72; 0.55H×1.8=0.99; 0.61H×1.8=1.098; 1.2H×1.8=2.16; 1.55H×1.8=2.79; 2.5H×1.8=4.5; −1.1H×1.8=−1.98; 2.2H×1.8=3.96; 3H×1.8=5.4 blocks.
Counts: 2 curved passes + 1 horizontal thrust; 3 terminal heads in rig; impact 1 star / contact.
Wind-up ends on the first contact tick; target star starts on that same tick; the rig trajectory uses out_quad, the strike hold is 2–3 ticks, and the trail loses alpha over 5 ticks. The final cinematic fades remove bright core before the residual field. Main threat is the blade path, secondary element is the contact star; dark rims support the pale floor. No glow outlines. Display accounting is in the weapon report.

Native-rate cut-in check: `crimson-cut-s1/flip_001.jpg`: blur at 90.800/90.833, face at 90.867, horizontal strike at 90.900. Thus impact remains tick (90.900−89.100)×20=36; no extra coin on the face cut.

## Real-client CHECK / ACT log

- Iteration 1: equipped weapon, 1280×720, default 5s settle / distance 6. Inspected `build/pairs/crimson-chain-axe-s1-iteration-1.png`. S1 frames 8–51 are identical (2.2s client render stall), so that run cannot establish contact timing. Other skills reveal repeated unrequested S1 rigs/thrusts and zero dummy HP loss.
- Engine finding: `plugin/items/.../PaperItemInputListener.kt:50–55` treats every caster-origin ENTITY_ATTACK damage event as LEFT_CLICK, including damage sent by `PaperEntityDamageGateway.apply`. It dispatches the held item's S1 and cancels the damage event. The resulting extra S1 effects contaminate other casts. No Kotlin changed. This prevents an end-to-end equipped-weapon pass.
- Iteration 2: 854×480, render distance 2, settle 20s, capture lag compensation 210ms. Narrowed the fan from 1.3 to 0.35 blocks, corrected sweep plane/contact direction, lowered caster-forward contact anchors by the 1.62-block eye height, recentered Q opening camera, replaced raw text burst with glyph quads. Images saved as `build/pairs/crimson-chain-axe-s1-iteration-2.png`. Q/S1/S3 still equipped; S2 was cleared after item grant and before cast, and its HP changed 200 → 196.064 → 191.636 → 184.256 (three hits).
- Iteration 3 setup: runtime-only copy of capture script clears Caster immediately after `give_item`; skill is still invoked through the actual server/client. Committed item bindings are unchanged. These are isolated-skill diagnostics, not proof that normal equipped play works. Replaced trail text backgrounds with solid block bands, compensated observed glyph baseline offset, removed per-element forest jitter so heads meet shafts, and fixed the coil's group rotation moving its centre. This fixture distinction must remain visible in the final report.
