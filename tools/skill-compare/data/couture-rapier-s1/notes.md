# Couture s1: measurement and PDCA

Source: GIe7Fna5LD4, 11.5–14.5 s. Native source 30000/1001 fps. Read 30 fps flip sheets and original timeline/onsets; pixel estimates below are approximate (±10%, occlusion/zoom), not instrumented segmentation. Body height H=1.8 blocks. Tick conversion: round((seconds − 11.5) × 20).

## Mapping and range
Mapping inferred from footage and supplied wiki; no skill labels in video.
S1 base 1 coin = 1 implemented hit. Dash-melee: crouched wind-up, pass through victim; trailing crescent is not another hit.

## Measurement table
Pixel spans refer to 384px-wide flip-sheet tiles. Formula L = effect_px / body_px × 1.8. Angles are screen-plane estimates. Color samples are visual estimates excluding enemy orange halo.

| Element / measured frame | Pixels / body pixels | Conversion (blocks) | Angle/count/palette | Ticks |
|---|---|---|---|---|
| X height @ 13.167s | 200/110 | 200/110 × 1.8 = 3.273 | ±45°, 3 rays, ivory | 31–40 |
| Trailing crescent diameter @ 13.367s | 155/55 | 155/55 × 1.8 = 5.073 | ~25° tilt, blush/ivory | 35–45 |
| Tool length @ 12.967s | 120/80 | 120/80 × 1.8 = 2.700 | low -25°, iron/charcoal | 0–31 |

### Contact timing
- 13.050s → round((13.050 − 11.5) × 20) = 31 ticks; one damage event, ignore_invulnerability=true.

### Direction checklist
- Importance: 3/10. Wind-up precedes contact; damage and burst share a tick.
- Primary silhouette: cut/shears; secondary: scattered petals. Palette ivory/blush, gold, charcoal. No glow_color.
- Solid blade and contact rays + translucent bloom + sparse particles; out_quad/out_cubic motion; 2–4 tick hit-stop.
- Source timing takes priority over default 60/100-tick art guideline for long S2/S3/DEF/Q performances.
- Rig parts 4; one trail band + dark rim, short 4t lifetime. Generic contact burst22; S3 gold burst16. Canopy37 + gold burst16 + standing shears6 =59; thread ends at 46+14=60, before canopy at61.

## PDCA
Iteration 1: replaced invented cyan three/five/six-hit strings with source timing, ivory tools and counted impacts. The corresponding bound capture was subsequently inspected (see below).

## Bound-weapon engine blocker (real capture)
S3 live RCON sampling during iteration 1: caster x=300.5 initially, x=301.8 at tick11 (intended 1.3-block approach), then x=306.8 by tick46 (unexpected additional 5-block S1 dash). Husk health stayed 200 throughout. Peak sampled arena display count: 58 before the extra contact-fill display was added (final authored bound is59).
Read-only trace: `PaperEntityDamageGateway.kt:79` calls `entity.damage(amount, attacker)`; `PaperItemInputListener.kt:52–55` interprets ENTITY_ATTACK as a fresh LEFT_CLICK, dispatches the held item's S1 and cancels damage. This is a Kotlin engine gap, not a reason to remove the requested binding. No Kotlin changed.
Follow-up captures explicitly use existing unbound `skill-tester` item with the same skill cast and content, to isolate visuals from the extra S1. They are NOT proof that the equipped weapon works end to end. First bound pairs are retained under `build/pairs/bound-iteration1/`.

### Audio/cut cross-check
Audio onset strength is supporting evidence only: music and narration produce non-impact onsets. Visual contact decides the damage tick.
- Contact 13.050s: nearest detected onset 13.056s (offset +0.006s); frame timing retained.
Detected camera/flash cuts: 13.08s, 13.146s, 13.213s, 14.281s.

## Isolated PDCA rounds 2–4

Round2 CHECK: inspected six/seven side-by-side source/client moments. The unbound skill-tester removes the unintended S1 recursion from the comparison. Damage sampling confirms the intended number of contacts, but is not an equipped-weapon pass. Impact cores were weak/occluded, floating blades were high, and the canopy was visibly faceted.

Round3 ACT/CHECK: lowered floating blades, strengthened impact rays, tried a solid contact core, and attached S2's final sweep to its dash. Compared all five final sheets again. Solid cores read as square slabs; the forward anchor was found to include eye height.

Round4 ACT: restored the filled star with see_through enabled, compensated forward anchors by -1.62 blocks, restored standing blade center1.4, and made S2/DEF impact visuals independent of post-hit target reselection. No further visual iteration beyond the four-round cap.

One bound capture followed by three isolated comparisons; bound S1 first-camera startup settled late.

Remaining mismatch: Passing X and trailing crescent are correctly one contact; source crouch and cloth pose remain absent, rays are harder and less luminous, and the recovery separation is too small.

Final isolated pairs: `build/pairs/couture-rapier-s1.png`; immutable round copy: `build/pairs/round4/isolated/couture-rapier-s1.png`. Captured frames: `build/couture-batch4/isolated/couture-rapier-s1/compare`. These artifacts remain local under ignored build/.

Capture limitation: the provided zero-speed dummy can still turn/retaliate after being hurt; dropped loot from prior reset targets is also visible in some rounds. NoAI was not enabled because it suppresses requested knockback. Neither body pose nor the dummy orientation is claimed as a match.

Final CHECK: round4 sheet opened and inspected; **fidelity FAIL / equipped engine BLOCKED**. Isolated health transitions: 1; sampled cast entity delta: 35 - 2 = 33. Camera/onset drift remains in final pairs; no visual-match claim. See the weapon REPORT for specific final failures.
