# Tower Shield — DEF

Source 23.7–31.8 s includes repeated enemy attacks/guard uses. One guard coin, zero damage beats. One cast is condensed to 60t (3 s), not a scripted replay of the enemy's eight-second attack sequence. Range: self defensive stance; stationary. Importance 5/10.

| Frame | Element / hand measurement | Conversion / authored value | Timeline |
|---|---|---|---|
| 24.03–24.37 | Physical tower shield, approximately body height | 1.05H × 1.8 = 1.89 → 1.9 high; 0.53H × 1.8 = 0.954 → 0.95 wide | seven solid rig parts t0–58 |
| 24.03–25.03 | Gold translucent outer oval | height 1.39H × 1.8 = 2.502 → 2.5; width 0.83H × 1.8 = 1.494 → 1.5 | seven horizontal glass strips t0–60 |
| 26.70 | Bright blocked-hit flash, surface sparks | small orange embers around 0.8H × 1.8 = 1.44 → y1.3–1.4 | representative spark beats t18/38/50 |
| 27.03–28.03 | Guard holds rather than damaging counterattack | no damage or unconditional end bash | resistance 0–60; charge 0/20/40 |

14 live displays: shield 7 + aura 7; particles add no displays. No glow outlines or oversized crest glyph. Physical/knockback resistance uses existing attributes. The intent renderer has no dedicated shield primitive; `shield` retains measurements and the supported smear is a coarse preview proxy.

Gaps: frontal-only mitigation, reactive block sparks and perfect-block detection are not exposed by this sequence YAML. Sparks here are scheduled, resistance is omnidirectional, charge accrues on elapsed time. No false claim that the source's incoming attacks were reproduced. Vanilla held sword remains visible.

Pairs moments: `23.90:raise 24.20:hold 24.60:sparks 25.60:late-guard 26.50:recovered`. Expected output `build/pairs/bastion-greatsword-def.png` (not generated).

Measurement method: hand-read proportions from the supplied video, not a recovered 3D scene. Body height H = 1.8 blocks. Foreshortened widths have about 10–15% uncertainty. Times below are video times; runtime tick = round((video time - case start) × 20). The supplied old analysis mislabels skill boundaries; the actual frames and wiki coin counts take precedence.

No new real-client capture was run: the task preamble prohibits local Docker/Gradle, and `vfx-shot.sh` invokes both internally. No image is claimed as a verified final pair. The previous S3 mismatch sheet was inspected at `/IdeaProjects/Astera/.claude/worktrees/agent-a313427dc7e668405/build/pairs.png`; it predates these edits.
