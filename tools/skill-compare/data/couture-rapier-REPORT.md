# Couturier's Rapier — video fidelity report

**Status: content authored; equipped-weapon end-to-end fidelity is blocked by an engine event-routing bug. Do not read isolated VFX captures as an equipped-weapon pass.** No Kotlin changed. Generic charge replaces the old invented poise/sprint bookkeeping on S1/DEF; identity-specific ally shields, automatic follow-up variants and bespoke stack rules are not implemented. No unit tests or test suites run. All original item/S1/S2/S3/DEF names retained; Q adds the original name Final Fitting.

Reference: `GIe7Fna5LD4`, supplied native 854×480, 30000/1001 fps video plus separate audio. The separate audio was muxed into `/tmp/couture-reference/GIe7Fna5LD4.mp4` for the existing compare tools; source media are not committed. 30 fps flip sheets were inspected around contacts. Per-case notes contain pixel/body-height estimates, arithmetic, onset cross-checks and PDCA findings. Pixel estimates have roughly ±10% uncertainty; this is manual frame measurement, not automated segmentation.

| Slot / name | Video seconds | Wiki coins / authored damage beats | Range and movement | Final pairs | Iterations / status |
|---|---|---|---|---|---|
| left_click / Basting Stitch | 11.50–14.50 | base S1: 1 / tick31 | dash-melee, passing cut | `build/pairs/couture-rapier-s1.png` | 4 / fidelity FAIL; engine BLOCKED |
| right_click / Hemline Rush | 26.40–31.40 | base S2: 3 / ticks13,45,79 | two ranged shears projections, then dash-melee | `build/pairs/couture-rapier-s2.png` | 4 / fidelity FAIL; engine BLOCKED |
| sneak_right / Gilded Salvo | 41.50–46.00 | S3: 3 / ticks13,30,61 | short approach, two sweeps, self AoE canopy r3.6 | `build/pairs/couture-rapier-s3.png` | 4 / fidelity FAIL; engine BLOCKED |
| sneak_left / Fitting Step | 36.00–40.00 | counter: 1 / tick44 | stance, approach, melee reply | `build/pairs/couture-rapier-def.png` | 4 / fidelity FAIL; engine BLOCKED |
| drop / Final Fitting | 47.30–54.50 | no wiki E.G.O entry / 2 clusters, ticks60,110 | suspended ranged shears; no intended caster dash | `build/pairs/couture-rapier-ego.png` | 4 / fidelity FAIL; engine BLOCKED |

## Mapping qualifications

The video has no reliable skill-name labels. S1 uses the single-coin base variant, S2 the three-coin base variant. Alternate one-coin/reuse and two-coin variants in the kit are not silently combined into extra hits. S3's 42.17–42.63 gold ring is one sustained contact, not two hits; the later slash and canopy are hits two and three. Q adapts the final suspended-shears sequence; the supplied wiki does not establish it as E.G.O or supply a coin count. Its `ego_cost: 100` is the requested Astera binding convention.

Damage beats use `ignore_invulnerability`; cosmetic follow-through, flash and after-effects do not add damage. Source timing takes priority over the usual 60/100-tick art guideline: S2 lasts100t, S3 90t, DEF80t, Q144t including recovery.

## Engine blockers and capture evidence

1. **Equipped weapon cancels PvE skill damage and retriggers S1.** Real-client S3 RCON sampling found the husk remained200HP. Caster x300.5→301.8 at tick11 is the authored1.3-block approach, then x306.8 by tick46 is an unintended extra5-block S1 dash. Read-only tracing found `plugin/combat/.../PaperEntityDamageGateway.kt:79` calls `entity.damage(amount, attacker)` and `plugin/items/.../PaperItemInputListener.kt:52–55` treats that ENTITY_ATTACK event as a new LEFT_CLICK and cancels it when the weapon binding handles it. Fix needs an engine-generated-damage guard in the input route. No content workaround that removes the binding or disguises damage is shipped.
2. **No content received-hit callback for the counter.** DEF is explicitly a timed stance/reply approximation. It cannot wait for an actual incoming attack using the available skill schema.
3. **Player body animation is not driven by weapon rigs.** Crouch, cloth, aerial sprite poses and multi-limb tailoring motion remain ordinary Minecraft player animation; the tool itself is a moving display rig.
4. **Vanilla render limits:** brightness15 is not a bloom shader; quads/blocks approximate tapered slash meshes and the canopy is faceted. The source's exposure, cloth silhouette and continuous translucent surface are not exact.
5. **Observer cut-ins:** screen flashes target the caster, as required by the art rules. The spectator compare camera does not receive those title overlays; this is recorded rather than flashing unrelated viewers.

The original bound-weapon images are retained under `build/pairs/bound-iteration1/`. Isolated follow-up captures use the existing unbound `skill-tester` item, the same authored skill and the same real client, solely to diagnose visuals. They cannot establish that the requested equipped weapon works end to end.

## Budget and verification

- All large-shape `glow_color` removed; ivory/gold solid fills, charcoal accents, translucent bloom and sparse particles.
- Worst authored overlap: canopy36 panels + ground1 + gold contact16 + standing shears6 = **59 live displays**. Thread ends at tick60 before canopy tick61. Rig fans use one outer band and one dark rim, 25-degree subdivisions and4t lifetime.
- Final S3 arena count peaked at61 non-player/non-husk entities, including2 pre-cast loot entities: **61 − 2 = 59** cast displays. This agrees with the authored overlap bound. Sampling is not a proof against shorter-than-sample spikes; the static overlap bound is the budget check.
- Final `./gradlew :tools:content-lint:run`: **BUILD SUCCESSFUL**, 23s. `git diff --check`: clean. No tests run.
- Capture workflow uses the existing `tools/vfx-shot/vfx-shot.sh` functions; batch orchestration is temporary under `/tmp`, reusing one server/client pair and resetting the dummy and camera between casts. No capture infrastructure changes are committed.

## Final CHECK — four iterations per skill

All five final round4 pair sheets were opened and inspected. **None is signed off as matching the video.** The four-iteration stopping condition is reached. Original bound captures plus three isolated follow-up comparisons are retained; S1’s first bound capture is stored with round2 because it was acquired in the shared capture batch. Final isolated frames are under `build/couture-batch4/isolated/`; immutable final pairs under `build/pairs/round4/isolated/`. `build/pairs/<case>.png` is the requested last pair per skill. Build artifacts are local, ignored files; cases and report are committed.

| Skill | Final remaining mismatches | Attribution / next requirement |
|---|---|---|
| S1 | Thin/hard X instead of brilliant tapered cross; no low body stance; recovery too close; final hit panel precedes visible contact | Body pose/render capability gaps plus unresolved trail/spacing and capture alignment |
| S2 | Raised shears missing the source silhouette; projected cut too rectangular; final camera sometimes shows pre-dash caster | Needs finer tool geometry/pose and stable server-tick camera synchronization; fixed strike locations are approximate after knockback |
| S3 | Gold crescent segmented and flat; canopy floats visually as panels rather than a ground-connected hemisphere; loops barely readable | Remaining geometry/orientation authoring mismatch, limited smooth surface rendering; do not attribute all placement problems to the engine |
| DEF | Pink aura weak, contact exposure missing, stance sometimes appears late; reply is timed | No received-hit callback; camera/visual onset drift remains; no claim of reactive counter |
| Q | Straight bars instead of detailed shears/chains; missing airborne character; weak hit fill | Geometry/body-animation gaps; source sequence identification as E.G.O remains unverified |

The filled star remains faint or unreadable even with see_through; the cause is unresolved, not a demonstrated glyph-engine defect. Brightness15 does not reproduce source bloom. Round4 onset/camera alignment varies by several frames (DEF roughly half a second in the selected reply panel); configured tick arithmetic is correct but wall-clock video matching is **not** verified. No arbitrary offset was applied to hide this.

Capture-harness limitation: the supplied zero-speed/follow-range dummy still turns and retaliates after hurt. Knockback is preserved, but this does not satisfy the intended AI-free orientation constraint. Prior dummy reset drops appear in-frame. NoAI was not used because it suppresses knockback. These captures expose the limitation; they are not clean motion-lock acceptance evidence.

## Final isolated telemetry

Health transitions prove only the isolated cast's hit count. Initial health200; attacker used unbound skill-tester. Counts exclude players/husks and include loot, so the unchanged pre-cast baseline is subtracted.

| Slot | Health sequence | Damage transitions | Arena peak − baseline |
|---|---|---|---|
| s1 | 200.000 → 192.620 | 1 | 35 − 2 = 33 |
| s2 | 200.000 → 197.060 → 194.120 → 190.184 | 3 | 54 − 2 = 52 |
| s3 | 200.000 → 196.064 → 192.128 → 187.208 | 3 | 61 − 2 = 59 |
| def | 200.000 → 194.096 | 1 | 30 − 3 = 27 |
| ego | 200.000 → 194.096 → 182.288 | 2 | 46 − 3 = 43 |
