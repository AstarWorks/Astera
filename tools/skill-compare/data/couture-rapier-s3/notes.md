# Couture s3: measurement and PDCA

Source: GIe7Fna5LD4, 41.5–46 s. Native source 30000/1001 fps. Read 30 fps flip sheets and original timeline/onsets; pixel estimates below are approximate (±10%, occlusion/zoom), not instrumented segmentation. Body height H=1.8 blocks. Tick conversion: round((seconds − 41.5) × 20).

## Mapping and range
Mapping inferred from footage and supplied wiki; no skill labels in video.
S3 3 coins = 3 hits. Approach, two short-range gold sweeps, then self AoE canopy. The sustained 42.17–42.63 ring is ONE hit, not a second coin; dome at 44.55 is third.

## Measurement table
Pixel spans refer to 384px-wide flip-sheet tiles. Formula L = effect_px / body_px × 1.8. Angles are screen-plane estimates. Color samples are visual estimates excluding enemy orange halo.

| Element / measured frame | Pixels / body pixels | Conversion (blocks) | Angle/count/palette | Ticks |
|---|---|---|---|---|
| Gold crescent diameter @ 42.200s | 245/90 | 245/90 × 1.8 = 4.900 | ~25° tilt; pale gold | 10–23 |
| Second crescent diameter @ 43.100s | 330/115 | 330/115 × 1.8 = 5.165 | ~20° tilt; pale gold | 27–40 |
| Canopy diameter @ 44.767s | 330/85 | 330/85 × 1.8 = 6.988 | hemisphere; translucent gold | 61–79 |
| Thread radius @ 44.300s | 140/70 | 140/70 × 1.8 = 3.600 | 10 dark strands | 46–70 |

### Contact timing
- 42.150s → round((42.150 − 41.5) × 20) = 13 ticks; one damage event, ignore_invulnerability=true.
- 43.000s → round((43.000 − 41.5) × 20) = 30 ticks; one damage event, ignore_invulnerability=true.
- 44.550s → round((44.550 − 41.5) × 20) = 61 ticks; one damage event, ignore_invulnerability=true.

### Direction checklist
- Importance: 8/10. Wind-up precedes contact; damage and burst share a tick.
- Primary silhouette: cut/shears; secondary: scattered petals. Palette ivory/blush, gold, charcoal. No glow_color.
- Solid blade and contact rays + translucent bloom + sparse particles; out_quad/out_cubic motion; 2–4 tick hit-stop.
- Source timing takes priority over default 60/100-tick art guideline for long S2/S3/DEF/Q performances.
- Rig parts 4; one trail band + dark rim, short 4t lifetime. Generic contact burst22; S3 gold burst16. Canopy37 + gold burst16 + standing shears6 =59; thread ends at 46+14=60, before canopy at61.

## PDCA
Iteration 1: replaced invented cyan three/five/six-hit strings with source timing, ivory tools and counted impacts. The corresponding bound capture was subsequently inspected (see below).

Iteration 1 CHECK: real-client pairs inspected. Contact fill weak/missing, bound-weapon S1 retrigger corrupts caster placement and damage. ACT: add filled ivory contact star; fixed billboard on canopy; guard threads trail behind instead of ring; scissor loops and rising pose keys added to Q; camera corrected.

## Bound-weapon engine blocker (real capture)
S3 live RCON sampling during iteration 1: caster x=300.5 initially, x=301.8 at tick11 (intended 1.3-block approach), then x=306.8 by tick46 (unexpected additional 5-block S1 dash). Husk health stayed 200 throughout. Peak sampled arena display count: 58 before the extra contact-fill display was added (final authored bound is59).
Read-only trace: `PaperEntityDamageGateway.kt:79` calls `entity.damage(amount, attacker)`; `PaperItemInputListener.kt:52–55` interprets ENTITY_ATTACK as a fresh LEFT_CLICK, dispatches the held item's S1 and cancels damage. This is a Kotlin engine gap, not a reason to remove the requested binding. No Kotlin changed.
Follow-up captures explicitly use existing unbound `skill-tester` item with the same skill cast and content, to isolate visuals from the extra S1. They are NOT proof that the equipped weapon works end to end. First bound pairs are retained under `build/pairs/bound-iteration1/`.

### Audio/cut cross-check
Audio onset strength is supporting evidence only: music and narration produce non-impact onsets. Visual contact decides the damage tick.
- Contact 42.150s: nearest detected onset 41.976s (offset -0.174s); frame timing retained.
- Contact 43.000s: nearest detected onset 43.009s (offset +0.009s); frame timing retained.
- Contact 44.550s: nearest detected onset 44.728s (offset +0.178s); frame timing retained.
Detected camera/flash cuts: 42.142s, 43.01s, 45.178s, 45.245s.

Iteration2 authoring: three upright blade/loop projections restored (6 displays), gold contact petals reduced8→2 (burst22→16), keeping canopy37+burst16+projections6=59. Collapse uses6 particle emitters×6 smoke×3 emissions=108 particles, no displays.

## Isolated PDCA rounds 2–4

Round2 CHECK: inspected six/seven side-by-side source/client moments. The unbound skill-tester removes the unintended S1 recursion from the comparison. Damage sampling confirms the intended number of contacts, but is not an equipped-weapon pass. Impact cores were weak/occluded, floating blades were high, and the canopy was visibly faceted.

Round3 ACT/CHECK: lowered floating blades, strengthened impact rays, tried a solid contact core, and attached S2's final sweep to its dash. Compared all five final sheets again. Solid cores read as square slabs; the forward anchor was found to include eye height.

Round4 ACT: restored the filled star with see_through enabled, compensated forward anchors by -1.62 blocks, restored standing blade center1.4, and made S2/DEF impact visuals independent of post-hit target reselection. No further visual iteration beyond the four-round cap.

Caster-forward effects subtract eye height: feet_anchor_y = eye_y - 1.62. Standing blade center1.4 and length2.6 produce bottom = 1.4 - 2.6/2 = 0.1 block.

Remaining mismatch: Three contacts retained. Standing blade placement and hit height corrected; canopy still reads as faceted overhead panels, not the smooth ground-connected source dome. Gold sweeps are segmented and too flat.

Final isolated pairs: `build/pairs/couture-rapier-s3.png`; immutable round copy: `build/pairs/round4/isolated/couture-rapier-s3.png`. Captured frames: `build/couture-batch4/isolated/couture-rapier-s3/compare`. These artifacts remain local under ignored build/.

Capture limitation: the provided zero-speed dummy can still turn/retaliate after being hurt; dropped loot from prior reset targets is also visible in some rounds. NoAI was not enabled because it suppresses requested knockback. Neither body pose nor the dummy orientation is claimed as a match.

Final CHECK: round4 sheet opened and inspected; **fidelity FAIL / equipped engine BLOCKED**. Isolated health transitions: 3; sampled cast entity delta: 61 - 2 = 59. Camera/onset drift remains in final pairs; no visual-match claim. See the weapon REPORT for specific final failures.
