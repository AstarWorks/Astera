# Ember Gauntlet — final source fidelity report

Committed implementation and comparisons are reviewable, but **visual matching is not achieved**. The capture/review limit was reached with the differences below. Source is `vBob-jTmlnc`, Firefist Office Survivor Gregor only; no Don footage was used. Native 30 fps inspection corrected the earlier hypotheses. Measurement tables, conversion arithmetic, camera/actor/pose/effect intent, and PDCA notes are in each case directory.

| Binding / original skill | Video seconds | Coins → explicit hits (ticks) | Range and movement | Final inspected pairs | Iterations / result |
|---|---|---|---|---|---|
| left_click / Furnace Jab | 35.5–38.5 | 2 → 2 (12,36) | dash-melee sweep + short straight jet | `build/pairs/ember-gauntlet-s1.png` | 4 valid reviews; partial |
| right_click / Hoop Cannon | 42.7–44.7 | 2 → 2 (6,22) | dash-melee punches, visual pressure hoop | `build/pairs/ember-gauntlet-s2.png` | 4 attempts, 2 valid reviews; partial |
| sneak_right / Inferno Furnace | 54.5–58.8 | 3 → 3 (20,26,56) | step-in sweep/punch, planted short ring blast | `build/pairs/ember-gauntlet-s3.png` | 4 attempts, 3 valid reviews; partial |
| sneak_left / Flare Guard | 47.5–49.2 | 1 → 1 (19) | planted melee retaliation | `build/pairs/ember-gauntlet-def.png` | 4 attempts, 3 valid reviews; partial |
| drop / Cinder Heart | NOT SHOWN | no source kit → original 3-hit extension | furnace sequence based on S3 | no fabricated source pair; `build/vfx-shot/ember-gauntlet-ego/side/sheet.png` | 1 real-client capture; unreferenced |

S1 had an additional discarded stale-content recording. S2's first attempt extracted zero frames. One warm-server round for all four cases failed to reload because the wrapper used an obsolete command. These recordings do not verify the revision. Both correct commands, `astera admin reload vfx` and `astera admin reload skills`, succeeded before the final captures. Final captures use unchanged capture-tool functions and fresh targets under one global capture slot; Kotlin and repository tooling were not edited.

E.G.O is absent from both supplied identity footage and wiki kit, so Cinder Heart is explicitly an original extension, not a claimed reproduction. It costs 100 E.G.O and zero energy. The side capture confirms the three-stage effect renders; it does not test player resource charging because the capture tool uses an administrative cast. All five bindings are authored. Burn DoT is separate from coin strikes; visual hoops add no damage. Damage has `ignore_invulnerability: true` on every explicit hit.

## Final comparison findings

- **S1:** The wider crescent and jet now render and follow the dash. At 36.15 the capture is still in wind-up; the sweep is visible at 36.35. Jet/contact at 37.3 is much dimmer and narrower in screen area than the reference, and the punch-in does not match. The arc remains polygonal, the impact spokes mechanical, and the fire particles linger through recovery. Native text flares do not provide a reliable source-facing silhouette.
- **S2:** Both straight punches visibly connect; no extra damaging hoop. At 43.0 the contact is present, but the source star and trailing smear are far brighter/larger. The second effect is already visible in the 43.65 recovery comparison, indicating residual render/director timing drift. The pressure hoop is rigid and short-lived compared with the source. The back pack separates visually from the moving body during wind-up.
- **S3:** Sweep, straight punch and two-hoop final blast all appear; the three contacts are readable. At 55.55 the initial fan and punch geometry overlap too much. At 57.3 the hoop scale is plausible but the blast is thin, dim and mechanical; the hand-drawn white-hot star is absent from this camera. The close-up and return-wide camera cuts are only approximate. Source diagonal cut-in framing is missing from the spectator view.
- **DEF:** The single retaliation and upright hoop appear around 48.5–48.6. The source sweep is flatter, softer and more intense; the capture has a tall angular fan and a weak hoop against the target. The source incoming attacker movement is absent because the target is intentionally AI-free. Particles persist after the source tail fades. The skill is a timed manual guard, not a reactive counter.

## Engine/content limits and unresolved implementation differences

- Existing YAML cannot articulate the Minecraft player into the source crouch, coat, arm or backpack hose. Display gauntlets approximate weapon motion only.
- The defensive YAML sequence is a manual timed guard/retaliation. Incoming-hit-triggered clashable counter behavior and the separate fuel/overheat bands are not reproduced; energy refill is the approximation.
- Caster screen flashes are authored at ticks 49/50/53 for S3/E.G.O, but spectator capture does not see caster title overlays. Diagonal scene masks and source compositing are not reproduced.
- Solid emissive blocks, translucent bloom and particles do not reproduce additive hand-drawn fire. This is an observed fidelity limitation, not proof that every better content solution is impossible. Text flares were weak/absent in compare views; the E.G.O side frame 56 shows a large flare displaced toward the caster. This remains an unresolved view/placement problem, not a verified engine defect.
- Caster-relative contact placement keeps display count independent of enemies, but knockback can separate it from the target. Camera cuts, render lag and exposure still differ. 20 fps client output versus 30 fps reference limits single-frame matching; timing discrepancies above are not excused as engine gaps.
- Identification confidence: S1 high, S2 high-medium, S3 high, DEF medium (incoming action plus single retaliation; no readable skill label). Later checkerboard exchanges are repeated counters, not extra coins.

## Display budget and validation

Final conservative simultaneous upper bounds: S1 39; S2 33; S3/E.G.O 58; DEF 51. Sweep budget is 3 rig parts + at most 8 sampled trail segments × 3 surfaces (soft, crisp, dark rim) + 10 impact displays = 37; persistent pack adds 2 = 39. Punch adds 19 and may overlap the sweep: 37+19+2=58. DEF adds one 12-segment hoop: 37+12+2=51. Jet: 3 rig + 5 solid tongues + bloom + 10 impact + 5 text faces =24, plus pack=26. Hoop punch:19+12+2=33. Furnace:24+24+2=50. No per-target display multiplication.

Final live sampling observed S2 33, S3 57, DEF 50. Prior valid S1 revision sampled 38; final S1 uses the conservative 39 bound (no final live sampler). Counts query entity types sequentially, so sampled peaks are approximate, not atomic proofs. Raw samples remain at `build/ember-live-displays.json`. Every owned ember `glow_color` was removed; large shapes use fills with brightness 15 and dark accents.

`./gradlew :tools:content-lint:run`: **367 passed, 0 failed, 0 missing schemas**. `git diff --check` passed. No unit tests or test suites were run. Only owned ember content and required compare metadata/report are committed.

## Reproducing the media review

Supplied video-only MP4 and M4A were losslessly muxed to `/tmp/ember-reference/vBob-jTmlnc.mp4`. Example: `SKILL_COMPARE_VIDEO_DIR=/tmp/ember-reference make skill-pairs CASE=ember-gauntlet-s1 AT="35.8:windup 36.15:hit1 36.35:sweep 36.95:jet-start 37.3:hit2 37.8:recovery"`. Other measured moments are in each case's notes. Captures use 854×480, render distance 2, CAST_LAG_MS=210; this calibration still leaves shot-dependent drift. Source media and generated images remain in ignored cache/build paths, not git. Final four pairs are retained at the paths above; earlier comparisons remain under `build/pairs/iteration-1`, `iteration-2`, and `round-2`.
