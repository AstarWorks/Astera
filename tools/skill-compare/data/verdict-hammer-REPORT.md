# Verdict Hammer — video reproduction audit

Source: supplied Hc6CJXJrzME video + separate audio, supplied wiki kit. The source has lyrics but no readable skill titles. Mapping S1/S2/DEF/S3 follows visible sequence and wiki coin counts, not the earlier analysis's inconsistent labels. The black-clad ally at 38.7–42s is excluded. The 54.3–65.2s sequence is present, but **its identification as E.G.O is unconfirmed**: Q is an original cinematic adaptation, not a claim about the identity's actual kit.

| Input / original name | Video range | Wiki coins / damage beats | Range type and movement | Final pairs | Iterations |
|---|---|---|---|---|---|
| S1 / Gavel Strike | 10.9–14.6 | 2 / 2 (31,48t) | Dash-melee, 5.5 blocks approach; two forward cones | `build/pairs/verdict-hammer-s1.png` | 4 |
| S2 / Heavenly Stakes | 35.3–38.2 | 1 / 1 (26t) | AoE at aimed target; 12-block acquisition, 3.2-block radius, no dash | `build/pairs/verdict-hammer-s2.png` | 4 |
| S3 / Obsidian Judgment | 25.4–32.6 | 3 / 3 (53,80,119t) | Target AoE then dash-melee; 2 + 3.5 blocks approach | `build/pairs/verdict-hammer-s3.png` | 4 |
| DEF / Vigil | 15.8–19.8 | 2 / 2 (24,53t) | Target AoE then dash-melee, 3.5 blocks approach | `build/pairs/verdict-hammer-def.png` | 4 |
| Q / Covenant Toll | 54.3–65.2 | unknown / 4 interpreted pulses (71,102,128,191t) | Target field AoE, 3 + 3 blocks approach, max 3 victims | `build/pairs/verdict-hammer-ego.png` | 4 |

All five bindings exist. Q consumes 100 E.G.O, zero energy. All explicit hits bypass invulnerability; approach dashes do not damage. Existing weapon and skill display names retained; Q had no existing name. No source names/quotes in content. No Kotlin changes or test suites.

## Measurement basis

Each case includes source-clock camera keys, actor positions, weapon pose keys, effects, and `notes.md` with pixel/body ratios, explicit ×1.8 conversions, impact ticks, native-frame observations, audio onsets and camera cuts. Source is 30000/1001 fps; 24/30 fps flip sheets were examined around attacks, and native frames are cached by the comparison tool. Separate audio and silent video were muxed with `-c copy` into ignored local cache; supplied inputs were not changed.

The 30.717s hammer cluster precedes the terminal S3 smash: it is visual preparation, not an invented fourth coin. Q's cinematic includes non-damage cut-ins; four pulses are an adaptation, not a wiki assertion. Approximate measurements carry ±3 px silhouette uncertainty; captions/hair/antlers are excluded from the body-height baseline.

## Art direction and budget

- Importance: S1 4/10, S2 5/10, DEF 6/10, S3 8/10, Q 10/10. Gold core / pale edge / dark hammer and fragments; violet movement afterimage only. Removed teal attack sparks mistaken for background lights.
- Six-piece black-and-gold hammer rig rotates about a hand pivot. Gold fan comes from its sweep; pale outer band, translucent inner band and black hammer keep contrast. Hit-stop has corresponding VFX freeze windows.
- Two geometric nails hover and converge before impact; their solid cores, brief translucent columns and expanding ground ring replace outlined item displays. No `glow_color` remains in verdict content.
- Construction-bound display peaks: Q = 20 stakes × 2 parts + 8 ring segments + 6 hammer parts + 5 impact elements = **59**. A swing reserves at most 17 samples × 2 bands + 6 hammer parts + 5 impact elements = 45; overlapping recovery rig adds 6 (51), or terminal ground ring adds 8 (53). Paired summon = 6 nail parts + 4 columns + 16 ring segments + 5 impact elements = 31. Particle emissions do not spawn displays. These are content bounds, not profiler measurements.
- Q caps victims at three: 25 particles × 3 = 75 per impact, plus 16 ring particles/tick for 6 ticks = 96; four beats total at most 4 × (75 + 96) = 684 particles. No continuous field damage.
- Source editorial timing exceeds the art guide's 60/100t preference (S3 144t, Q 218t). Preserving the requested video clock takes priority; individual effects have short lifetimes except the persistent Q field.

## Known limitations / gaps to distinguish from tuning

- Player avatar pose/hair/antlers and sprite deformation cannot be authored through these skill/VFX YAMLs. Weapon motion is a display rig; Minecraft player limbs do not perform the source's prone slide or airborne twist.
- Existing title overlays cannot reproduce photographic face cut-ins, scene inversion, or the original crystalline tower silhouette. Q uses the available white/vignette/gold-burst overlays. Observer camera is a capture director; gameplay does not take over all spectators' cameras. Caster-only flashes are not visible in spectator pairs.
- S2's wiki nonlethal floor and defense-trigger bypass are not represented by the used damage mechanic; no engine change was made. DEF reproduces the shown two-hit action on input, not a reactive counter trigger.
- Kit-specific resources/passives are represented by existing rupture stacks; exact Preservation/Promised Nail/Tremor conversion is outside this content's existing status vocabulary. S3 consumes rupture at its final hit.
- Persistent background circuitry belongs to the identity's passive arena state and is not reproduced as per-cast background geometry.
- Flat quads and an eight-segment Q ground ring approximate the source's tapered volumetric light and irregular continuous ring. Q uses 20 stakes rather than the uncertain ~25 in the source to respect the 60-display ceiling.
- Target acquisition is re-evaluated on delayed damage. A moving enemy can leave the fixed summon visual; snapshot pose fixes the Q field but does not bind other nail damage to the original selected entity. The stationary-dummy capture does not validate moving-target tracking; not claimed exact.

## Validation and PDCA log

- Final content validator: **364 files passed, 0 failed**, BUILD SUCCESSFUL. No unit tests/test suites run. Earlier validator: first pass 345 files passed, zero failures; repeat after Q rig addition 346 files passed, zero failures.
- S1 capture attempt 1 failed before server start: Docker build Gradle journal cache lock timed out (`/home/gradle/.gradle/caches/journal-1`, another process owner). No compare claim is made from that attempt.
- Completed comparisons are judged by paired images, not build success. All skills remain partial visual matches at the four-iteration limit; see final assessment below.
- Q iteration 1 produced real frames and pairs, inspected manually: missing field (runtime 200t VFX limit), overly wide camera, face cut looking at the old caster position. Corrected with a delayed 192t field, a 0.55 camera dolly, and a new face target; increased high-importance impact scales without increasing display counts.
- Capture queue adaptation: stopped only this worktree's waiting capture processes, then used a blocking lock on global slot 2 for a serial batch. Nested capture uses a private lock directory while that global lock is held; the subnet lock remains the shared global lock. The batch reuses one dedicated server to avoid redundant image builds. After the preceding lock owner exited but orphaned waiting shells retained slot 2 (observed PPID=1), the batch was moved to an isolated temporary verdict-recovery.lock, with 854x480 capture resolution. No existing holder was killed; no shared configuration or Kotlin files were changed. This is an environment workaround, not a normal increase to the shared capture configuration.

## Final visual assessment

The implementation preserves the measured damage counts, ordering and range categories. **None is certified as a visual match.** Four real-client iterations per skill are the requested stopping limit. Final pairs remain in the ignored `build/pairs/` directory; case definitions, measurement tables and this report are committed.

| Skill | Remaining visible mismatch | Cause / next tuning opportunity |
|---|---|---|
| S1 | Arc is faceted and too vertically circular; hit radiance is a pixel star and appears late in the first-hit pair; raised wind-up pose differs | Flat trail samples and font glyphs approximate curved light. Arc orientation and camera could still be tuned; player limb/body deformation needs engine support. |
| S2 | Straight T nails lack tapered crystalline filigree; columns read as glass slabs; ring too regular; late nails remain elevated | Budgeted block geometry and vanilla rendering. More content geometry could trade against other effects; volumetric bloom requires rendering support. |
| S3 | Horizontal thrust reads as swing; preparatory hammer cluster is sparse; terminal dome/whiteout absent; final selected hit frames miss star flashes | Pose/rig approximation remains tunable; spectator lacks caster-only cut-in. No fourth damage hit added for the preparation cluster. |
| DEF | Hover nails and final star are cropped; source final burst is much stronger; caster never performs airborne twist | Spectator overlay and player-pose limitations; star size/placement is still an approximation. |
| Q | Field is sparse and regular; face/tower/inversion sequence is not reproduced; oversized glyph impacts climb out of frame and replace broad radiance | 20 stakes under display cap, missing sprite/shader/cinematic capabilities. Q attribution remains unconfirmed; original adaptation rather than verified identity E.G.O. |

Round 1 exposed edge-on quads, dark glass columns and coarse fan panels. Round 2 changed world orientation, camera framing and motion, then revealed misplaced glyph centers and end-on nail crossguards. Round 3 corrected glyph centering, crossguard axes, connected field stakes and light column material. The final S1/S2/S3/DEF pass reduces fan interior opacity and attempts to hold nail height during shrink; the final S2 pair still shows an elevated late nail silhouette, so this correction is not accepted as complete. Q's original failed-field capture counts as its first iteration; batch rounds 1–3 are Q iterations 2–4.

A temporary RCON entity-selector sampling attempt reported zero throughout despite visible displays, so it is not valid live-budget evidence (display rendering may be packet-only). Only the construction bounds above are asserted. Real capture also shows a several-tick visual onset offset in some hit pairs; authored damage ticks do not prove pixel-exact client timing.

Final capture batch completed and its dedicated server was stopped. All final pair images were opened and visually reviewed. Final content lint passed after the last content edit; subsequent changes only document the observations. No pushes, engine edits or other-weapon edits.
