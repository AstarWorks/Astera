# Colossus Fall — S3, retained case `zwei-s3`

Source 18.6–23.7 s. Three coins = hits t20/46/70. Range: initial self-centred melee sweep, dash-melee 3 blocks in 2t, target-centred planted-sword discharge. Importance 9/10.

| Measured frame | Element | Formula / target | Authored timeline |
|---|---|---|---|
| 19.65 | Waist ring, 300° | inner 0.89H × 1.8 = 1.602 → 1.6; outer 1.83H × 1.8 = 3.294 → 3.3; centre .47H × 1.8 = .846 → .85 | draw 24 body + 24 white edge segments t16–18, expire t28 |
| 19.65 | Broad gold band / white lip | radial width 3.3 - 1.6 = 1.7; edge .067H × 1.8 = .1206 → .12 | body 1.58 + edge .12; seven tail segments dark brown |
| 20.55 | Dragging sword | blade .97H × 1.8 = 1.746 → 1.75; pivot .5H × 1.8 = .9; tip .9 - 2sin(30°) ≈ -.1 | scrape t28–44; removed 355° yaw discontinuity |
| 21.10 | Short thrust/lunge | translation 1.67H × 1.8 = 3.006 → 3.0; smear height .5H × 1.8 = .9 | dash t44–46; hit t46 |
| 21.80 | Blade behind/below raised hand | hand 1.083H × 1.8 = 1.9494 → 1.95; angle 215° | quiet hold 56–67; removed floor telegraph |
| 22.35 / supplied ref-star.png | 18 thick connected zigzag spikes | lengths .67–1.94H × 1.8 = 1.206–3.492 → 1.2–3.5; root .122H × 1.8 = .2196 → .22; tip .056H × 1.8 = .1008 → .10 | grow t70–72; kink ±20°; retract through t81 |
| 22.35 | White root cores with yellow edges | core .072H × 1.8 = .1296 → .13, outer .22; yellow border (.22 - .13)/2 = .045 | 12 root overlays, alternate white/yellow t74/76 |
| 22.35 | Pillar, off top | at least 4.44H × 1.8 = 7.992 → 8, core .4, bloom .9 | t70–81 |
| 22.35 | Crossbar at waist | length 2.5H × 1.8 = 4.5; height .583H × 1.8 = 1.0494 → 1.05 | t70–80 |
| 22.80 | 8 floor cracks, 2 segments each | lengths .22–.66H × 1.8 = .396–1.188 → .4–1.18 | t82–94, after star to fit budget |

S3 star budget: 18×2 spike segments + 12 white cores + pillar/bloom/bar/bloom 4 + planted sword 6 = 58 live displays. Cracks replace the star: 16 + sword 6 = 22. Ring: 24×2 + sword 6 = 54 (at most 60 at the scrape handoff). Natural sword materials, emissive opaque gold/white shapes; glass only for bloom.

The old star used independent jittered formations for roots/tips, so corresponding pieces were disconnected. Explicit endpoints now share the exact kink. Block transforms are centred by VfxTimeline; old pillar/bar corner offsets have been removed. Camera switches to the wide high shot at t14, before the ring begins, and stays until t29. At the star shot: horizontal coverage = 2 × 7 × tan(50°/2) ≈ 6.53 blocks; the longest spikes span most of it.

Remaining approximations: two rectangular width steps rather than continuous pointed triangles; only 12 of 18 roots get white overlays to fit budget; cracks delayed until t82; no measured knockback distance from a fresh client. Initial hit radius 4.3 includes 1 block of contact allowance outside the 3.3 ring so the case's target at 4.2 is actually hit. The stationary caster has no source's small anticipation shuffle. Vanilla held sword remains visible.

Pairs moments: `19.65:ring 20.55:scrape 21.10:thrust 21.80:raise 22.35:star 22.80:planted`. Expected output `build/pairs/zwei-s3.png` (not generated).

Measurement method: hand-read proportions from the supplied video, not a recovered 3D scene. Body height H = 1.8 blocks. Foreshortened widths have about 10–15% uncertainty. Times below are video times; runtime tick = round((video time - case start) × 20). The supplied old analysis mislabels skill boundaries; the actual frames and wiki coin counts take precedence.

No new real-client capture was run: the task preamble prohibits local Docker/Gradle, and `vfx-shot.sh` invokes both internally. No image is claimed as a verified final pair. The previous S3 mismatch sheet was inspected at `/IdeaProjects/Astera/.claude/worktrees/agent-a313427dc7e668405/build/pairs.png`; it predates these edits.
