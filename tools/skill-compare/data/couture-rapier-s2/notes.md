# Couture s2: measurement and PDCA

Source: GIe7Fna5LD4, 26.4–31.4 s. Native source 30000/1001 fps. Read 30 fps flip sheets and original timeline/onsets; pixel estimates below are approximate (±10%, occlusion/zoom), not instrumented segmentation. Body height H=1.8 blocks. Tick conversion: round((seconds − 26.4) × 20).

## Mapping and range
Mapping inferred from footage and supplied wiki; no skill labels in video.
S2 base 3 coins = 3 hits. Hybrid ranged projection (first two) and dash-melee (last). Caster stands apart while summoned shears strike; do not treat every beat as a seven-block dash.

## Measurement table
Pixel spans refer to 384px-wide flip-sheet tiles. Formula L = effect_px / body_px × 1.8. Angles are screen-plane estimates. Color samples are visual estimates excluding enemy orange halo.

| Element / measured frame | Pixels / body pixels | Conversion (blocks) | Angle/count/palette | Ticks |
|---|---|---|---|---|
| Sweep diameter @ 27.067s | 265/90 | 265/90 × 1.8 = 5.300 | ~25° tilt, ivory + charcoal | 10–23 |
| Caster to target @ 28.767s | 190/65 | 190/65 × 1.8 = 5.262 | horizontal; remote shears | 42–55 |
| Contact X height @ 30.400s | 180/95 | 180/95 × 1.8 = 3.411 | ±45°, ivory | 76–88 |

### Contact timing
- 27.050s → round((27.050 − 26.4) × 20) = 13 ticks; one damage event, ignore_invulnerability=true.
- 28.650s → round((28.650 − 26.4) × 20) = 45 ticks; one damage event, ignore_invulnerability=true.
- 30.350s → round((30.350 − 26.4) × 20) = 79 ticks; one damage event, ignore_invulnerability=true.

### Direction checklist
- Importance: 6/10. Wind-up precedes contact; damage and burst share a tick.
- Primary silhouette: cut/shears; secondary: scattered petals. Palette ivory/blush, gold, charcoal. No glow_color.
- Solid blade and contact rays + translucent bloom + sparse particles; out_quad/out_cubic motion; 2–4 tick hit-stop.
- Source timing takes priority over default 60/100-tick art guideline for long S2/S3/DEF/Q performances.
- Rig parts 4; one trail band + dark rim, short 4t lifetime. Generic contact burst22; S3 gold burst16. Canopy37 + gold burst16 + standing shears6 =59; thread ends at 46+14=60, before canopy at61.

## PDCA
Iteration 1: replaced invented cyan three/five/six-hit strings with source timing, ivory tools and counted impacts. The corresponding bound capture was subsequently inspected (see below).

Pre-capture correction: final cut-in begins at tick76 but visible victim contact is ~30.367s → round((30.367−26.4)×20)=79. Damage/cross moved to79; no extra damage on smear. First ranged cut gains a 5.4-block solid seam (2 displays).

Iteration 1 CHECK: real-client pairs viewed at build/pairs/couture-rapier-s2.png. Camera too wide (body about 65% of source); full-width fan reads as solid tiles; final contact missed after dash overshoot. ACT: camera distance ×0.65; trail outer 28% only, 25° segments; dash begins78 for contact79. Warm bloom remains limited by vanilla rendering.

## Bound-weapon engine blocker (real capture)
S3 live RCON sampling during iteration 1: caster x=300.5 initially, x=301.8 at tick11 (intended 1.3-block approach), then x=306.8 by tick46 (unexpected additional 5-block S1 dash). Husk health stayed 200 throughout. Peak sampled arena display count: 58 before the extra contact-fill display was added (final authored bound is59).
Read-only trace: `PaperEntityDamageGateway.kt:79` calls `entity.damage(amount, attacker)`; `PaperItemInputListener.kt:52–55` interprets ENTITY_ATTACK as a fresh LEFT_CLICK, dispatches the held item's S1 and cancels damage. This is a Kotlin engine gap, not a reason to remove the requested binding. No Kotlin changed.
Follow-up captures explicitly use existing unbound `skill-tester` item with the same skill cast and content, to isolate visuals from the extra S1. They are NOT proof that the equipped weapon works end to end. First bound pairs are retained under `build/pairs/bound-iteration1/`.

### Audio/cut cross-check
Audio onset strength is supporting evidence only: music and narration produce non-impact onsets. Visual contact decides the damage tick.
- Contact 27.050s: nearest detected onset 26.980s (offset -0.070s); frame timing retained.
- Contact 28.650s: nearest detected onset 27.956s (offset -0.694s); frame timing retained.
- Contact 30.350s: nearest detected onset 30.603s (offset +0.253s); frame timing retained.
Detected camera/flash cuts: 28.629s, 30.197s, 30.23s.

## Isolated PDCA rounds 2–4

Round2 CHECK: inspected six/seven side-by-side source/client moments. The unbound skill-tester removes the unintended S1 recursion from the comparison. Damage sampling confirms the intended number of contacts, but is not an equipped-weapon pass. Impact cores were weak/occluded, floating blades were high, and the canopy was visibly faceted.

Round3 ACT/CHECK: lowered floating blades, strengthened impact rays, tried a solid contact core, and attached S2's final sweep to its dash. Compared all five final sheets again. Solid cores read as square slabs; the forward anchor was found to include eye height.

Round4 ACT: restored the filled star with see_through enabled, compensated forward anchors by -1.62 blocks, restored standing blade center1.4, and made S2/DEF impact visuals independent of post-hit target reselection. No further visual iteration beyond the four-round cap.

Final sweep moved from tick76 to78 and follows the dash; first two contact effects now use fixed strike locations to survive ray target loss immediately after knockback.

Remaining mismatch: Three contacts and ranged/ranged/dash ordering retained. Raised tool silhouette and diagonal projected second cut remain too simple; final sweep is segmented and source bloom is absent.

Final isolated pairs: `build/pairs/couture-rapier-s2.png`; immutable round copy: `build/pairs/round4/isolated/couture-rapier-s2.png`. Captured frames: `build/couture-batch4/isolated/couture-rapier-s2/compare`. These artifacts remain local under ignored build/.

Capture limitation: the provided zero-speed dummy can still turn/retaliate after being hurt; dropped loot from prior reset targets is also visible in some rounds. NoAI was not enabled because it suppresses requested knockback. Neither body pose nor the dummy orientation is claimed as a match.

Final CHECK: round4 sheet opened and inspected; **fidelity FAIL / equipped engine BLOCKED**. Isolated health transitions: 3; sampled cast entity delta: 54 - 2 = 52. Camera/onset drift remains in final pairs; no visual-match claim. See the weapon REPORT for specific final failures.
