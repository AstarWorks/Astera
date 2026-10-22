# crimson-chain-axe EGO — measurement / PDCA

Source: Gpj-lvOnAgs, 125.50–141.70s. Native source 854×480 at 30000/1001 fps.
Inspected 24 fps flip sheets for S1/S2; 30 fps sheets for S3/Q, and native case frames.
Pixel estimates below use the 384×216 sheet tiles, excluding timestamp border. H = visible upright body (not weapon/cape); perspective and cape occlusion give about ±15% uncertainty. These are manual estimates, not pixel-perfect masks.
Range: **melee flurry → self-centred ground AoE / final slash**. Caster stays near origin; S2/S3 have airborne poses. Static player body does not reproduce cape/limb articulation.
Timing conversion: tick = round((video_s − 125.5) × 20). Native-frame tolerance ±0.034s; tick rounding ±0.025s.

| Contact | Source second | Tick formula |
|---|---:|---|
| 1 | 127.800 | (127.800 − 125.5) × 20 = 46 |
| 2 | 129.500 | (129.500 − 125.5) × 20 = 80 |
| 3 | 134.800 | (134.800 − 125.5) × 20 = 186 |
| 4 | 136.050 | (136.050 − 125.5) × 20 = 211 |
| 5 | 139.400 | (139.400 − 125.5) × 20 = 278 |

| Frame second | Element | Pixel estimate / body H | Conversion (blocks) | Interval (ticks) |
|---|---|---|---|---|
| 127.8 | flurry sweep width (span) | 300px / 65px | (300/65) H × 1.8 = 8.308 | 46–74 |
| 130.6 | orbit diameter (diameter) | 300px / 60px | (300/60) H × 1.8 = 9.000 | 94–152 |
| 134.3 | raised chain length (length) | 110px / 65px | (110/65) H × 1.8 = 3.046 | 169–186 |
| 134.83 | cross flash diameter (diameter) | 230px / 60px | (230/60) H × 1.8 = 6.900 | 186–190 |
| 136.1 | blade columns height (height) | 150px / 55px | (150/55) H × 1.8 = 4.909 | 211–256 |
| 139.5 | final diagonal length (length) | 340px / 55px | (340/55) H × 1.8 = 11.127 | 278–300 |

Palette manually sampled by visual estimate: crimson #E52240 dominant, bone #FFF1DB contact core, #240711 dark rim, faint red bloom. Main curved band ~0.25–0.38 blocks; bright edge 0.055 blocks. Tilt estimate 25°/−35°; descending lances ~25° below horizontal. Contact star 0.6–1.1 blocks. Rig terminal axes: three visible paired heads, 0.35 block width; linked blade curve is approximated by a rigid segmented haft.
Camera size formula: distance ≈ 1.8 / (2 × tan(40°/2) × screen-height fraction). 27% body fraction → 9.16 blocks; close-up 45% → 5.49 blocks. Camera keys approximate shot framing; no roll support. Target separation estimate 2.5 H × 1.8 = 4.5 blocks.

## Evidence and mapping
This is a trailer cinematic extension, not an independently identified E.G.O skill. Five authored damage groups are an adaptation of the five-coin enhanced S3; continuous flurry and orbit sub-pulses cannot be equated confidently with coins. Native frames put white shock at 134.80s, forest visible by 136.03s, final red wipe at 139.40s. Do not claim an official five-hit E.G.O mapping.

Audio/cut extraction from video muxed locally with supplied separate M4A (source MP4 is silent). Audio is supporting evidence only: music and narration create onsets.
{"start_s": 125.5, "end_s": 141.7, "waveformStepMs": 10, "onsets": [{"t": 125.883, "s": 25.68}, {"t": 126.289, "s": 25.17}, {"t": 126.44, "s": 88.9}, {"t": 126.916, "s": 55.28}, {"t": 127.381, "s": 105.13}, {"t": 127.578, "s": 22.78}, {"t": 127.706, "s": 58.12}, {"t": 127.857, "s": 23.17}, {"t": 128.519, "s": 25.99}, {"t": 128.67, "s": 33.47}, {"t": 130.237, "s": 35.24}, {"t": 130.353, "s": 22.77}, {"t": 130.841, "s": 25.04}, {"t": 131.421, "s": 63.03}, {"t": 131.537, "s": 47.65}, {"t": 131.967, "s": 28.37}, {"t": 133.929, "s": 25.78}, {"t": 134.254, "s": 57.73}, {"t": 135.531, "s": 28.81}, {"t": 135.961, "s": 22.89}, {"t": 136.309, "s": 44.48}, {"t": 136.471, "s": 22.79}, {"t": 136.704, "s": 31.67}, {"t": 136.959, "s": 33.99}, {"t": 137.261, "s": 42.18}, {"t": 137.725, "s": 35.2}, {"t": 138.016, "s": 43.06}, {"t": 138.283, "s": 48.5}, {"t": 140.372, "s": 25.43}, {"t": 140.756, "s": 22.96}, {"t": 141.011, "s": 28.95}], "cuts": [{"t": 126.059, "s": 14.9}, {"t": 126.293, "s": 12.1}, {"t": 126.76, "s": 17.5}, {"t": 129.529, "s": 21.2}, {"t": 129.563, "s": 21.5}, {"t": 129.63, "s": 18.3}, {"t": 130.13, "s": 16.4}, {"t": 130.197, "s": 17.1}, {"t": 134.801, "s": 32.1}, {"t": 138.772, "s": 14.4}]}

## Iterations
1. PLAN/DO: replace immediate damage / oversized self AoE, match contact ticks, author block rig + filled fan + dark rim; remove every glow_color. Capture pending.

Revised ground extent: at 136.10s the visible columns span about 320px / 55px H × 1.8 = 10.473 blocks. Use 10.8-block diameter (radius 5.4), centred on caster; source has enemy bursts on both sides. Treat as self AoE, rather than the initial target-side approximation.

## Placement / art-direction checklist
Importance 10/10. At 136.10s: eruption centred on caster feet; field radius ≈ 3H; blade bases at ground y=0. These are manual screen-space estimates relative to the caster, not inferred world coordinates from a calibrated camera.
Coordinate conversions (H=1.8): 0.4H×1.8=0.72; 0.55H×1.8=0.99; 0.61H×1.8=1.098; 1.2H×1.8=2.16; 1.55H×1.8=2.79; 2.5H×1.8=4.5; −1.1H×1.8=−1.98; 2.2H×1.8=3.96; 3H×1.8=5.4 blocks.
Counts: source roughly 30–40 blade silhouettes (occlusion prevents exact count); authored 18 shafts + 18 axe heads + 12 dark accents.
Wind-up ends on the first contact tick; target star starts on that same tick; the rig trajectory uses out_quad, the strike hold is 2–3 ticks, and the trail loses alpha over 5 ticks. The final cinematic fades remove bright core before the residual field. Main threat is the blade path, secondary element is the contact star; dark rims support the pale floor. No glow outlines. Display accounting is in the weapon report.

Forest phase refined from native frame_00317 (136.1s): foreground left columns precede the far side. Eruption begins at209; 18 shafts/heads spawn at209+i, reach full height at211+i; the field is fully established at228. Damage remains one grouped event at211. Last blade sinks by256; final wipe at278.

## Real-client CHECK / ACT log

- Iteration 1: equipped weapon, 1280×720, default 5s settle / distance 6. Inspected `build/pairs/crimson-chain-axe-ego-iteration-1.png`. S1 frames 8–51 are identical (2.2s client render stall), so that run cannot establish contact timing. Other skills reveal repeated unrequested S1 rigs/thrusts and zero dummy HP loss.
- Engine finding: `plugin/items/.../PaperItemInputListener.kt:50–55` treats every caster-origin ENTITY_ATTACK damage event as LEFT_CLICK, including damage sent by `PaperEntityDamageGateway.apply`. It dispatches the held item's S1 and cancels the damage event. The resulting extra S1 effects contaminate other casts. No Kotlin changed. This prevents an end-to-end equipped-weapon pass.
- Iteration 2: 854×480, render distance 2, settle 20s, capture lag compensation 210ms. Narrowed the fan from 1.3 to 0.35 blocks, corrected sweep plane/contact direction, lowered caster-forward contact anchors by the 1.62-block eye height, recentered Q opening camera, replaced raw text burst with glyph quads. Images saved as `build/pairs/crimson-chain-axe-ego-iteration-2.png`. Q/S1/S3 still equipped; S2 was cleared after item grant and before cast, and its HP changed 200 → 196.064 → 191.636 → 184.256 (three hits).
- Iteration 3 setup: runtime-only copy of capture script clears Caster immediately after `give_item`; skill is still invoked through the actual server/client. Committed item bindings are unchanged. These are isolated-skill diagnostics, not proof that normal equipped play works. Replaced trail text backgrounds with solid block bands, compensated observed glyph baseline offset, removed per-element forest jitter so heads meet shafts, and fixed the coil's group rotation moving its centre. This fixture distinction must remain visible in the final report.
