# Couture ego: measurement and PDCA

Source: GIe7Fna5LD4, 47.3–54.5 s. Native source 30000/1001 fps. Read 30 fps flip sheets and original timeline/onsets; pixel estimates below are approximate (±10%, occlusion/zoom), not instrumented segmentation. Body height H=1.8 blocks. Tick conversion: round((seconds − 47.3) × 20).

## Mapping and range
Mapping inferred from footage and supplied wiki; no skill labels in video. E.G.O slot adapts final suspended-shears sequence; not a verified wiki E.G.O.
Wiki has no E.G.O entry. Final 47.3–54.5 sequence adapted to Q, 100 E.G.O. Two impact clusters at 50.30 and 52.80; no official coin count claimed. Ranged target strike, suspended shears; caster stays grounded.

## Measurement table
Pixel spans refer to 384px-wide flip-sheet tiles. Formula L = effect_px / body_px × 1.8. Angles are screen-plane estimates. Color samples are visual estimates excluding enemy orange halo.

| Element / measured frame | Pixels / body pixels | Conversion (blocks) | Angle/count/palette | Ticks |
|---|---|---|---|---|
| Suspended blade length @ 49.333s | 95/50 | 95/50 × 1.8 = 3.420 | 3 needles; ivory/charcoal | 0–65 |
| Final scissors blade @ 52.500s | 130/52 | 130/52 × 1.8 = 4.500 | paired ±24° closing to ±8° | 90–120 |
| Final sweep diameter @ 53.000s | 370/110 | 370/110 × 1.8 = 6.055 | ivory, dark rim | 106–124 |

### Contact timing
- 50.300s → round((50.300 − 47.3) × 20) = 60 ticks; one damage event, ignore_invulnerability=true.
- 52.800s → round((52.800 − 47.3) × 20) = 110 ticks; one damage event, ignore_invulnerability=true.

### Direction checklist
- Importance: 10/10. Wind-up precedes contact; damage and burst share a tick.
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
- Contact 50.300s: nearest detected onset 50.261s (offset -0.039s); frame timing retained.
- Contact 52.800s: nearest detected onset 52.966s (offset +0.166s); frame timing retained.
Detected camera/flash cuts: 47.547s, 50.35s, 50.484s, 50.517s, 52.853s, 52.886s, 52.92s, 52.953s.

## Isolated PDCA rounds 2–4

Round2 CHECK: inspected six/seven side-by-side source/client moments. The unbound skill-tester removes the unintended S1 recursion from the comparison. Damage sampling confirms the intended number of contacts, but is not an equipped-weapon pass. Impact cores were weak/occluded, floating blades were high, and the canopy was visibly faceted.

Round3 ACT/CHECK: lowered floating blades, strengthened impact rays, tried a solid contact core, and attached S2's final sweep to its dash. Compared all five final sheets again. Solid cores read as square slabs; the forward anchor was found to include eye height.

Round4 ACT: restored the filled star with see_through enabled, compensated forward anchors by -1.62 blocks, restored standing blade center1.4, and made S2/DEF impact visuals independent of post-hit target reselection. No further visual iteration beyond the four-round cap.

Rising needles lowered1.2 blocks after round2; final forward-anchored sweep corrected by eye-height subtraction. Q is an interpretation, not a verified E.G.O identification.

Remaining mismatch: Two suspended-shears contact clusters retained. Large tools are recognisable crossed bars, but handles, chains, tapered blades and airborne character pose do not reproduce the source silhouette. Caster-only flash is absent in observer pairs.

Final isolated pairs: `build/pairs/couture-rapier-ego.png`; immutable round copy: `build/pairs/round4/isolated/couture-rapier-ego.png`. Captured frames: `build/couture-batch4/isolated/couture-rapier-ego/compare`. These artifacts remain local under ignored build/.

Capture limitation: the provided zero-speed dummy can still turn/retaliate after being hurt; dropped loot from prior reset targets is also visible in some rounds. NoAI was not enabled because it suppresses requested knockback. Neither body pose nor the dummy orientation is claimed as a match.

Final CHECK: round4 sheet opened and inspected; **fidelity FAIL / equipped engine BLOCKED**. Isolated health transitions: 2; sampled cast entity delta: 46 - 3 = 43. Camera/onset drift remains in final pairs; no visual-match claim. See the weapon REPORT for specific final failures.
