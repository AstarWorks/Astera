# Citadel Dawn — original E.G.O derivative

There is no E.G.O cast in the supplied identity video. Case range 21.4–23.7 s is a motif reference for the shouldered blade, planted sword and gold discharge, not evidence of a canonical fifth move. Zero source coins; one authored damage beat t36. Range: self-projected melee burst, centred 2.2 blocks forward, radius 3.3; no projectile. Importance 10/10.

| Motif frame | Element | Conversion / authored value | Timeline |
|---|---|---|---|
| 24.03 (DEF motif outside case) | Tower shield raise | source shield 1.05H × 1.8 = 1.89 → 1.9 high | short guard t0–20 |
| 21.80 | Shouldered metal sword | blade 0.97H × 1.8 = 1.746 → 1.75 | raise t20–32 |
| 22.35 | Planted sword + lightning fan | spike lengths 0.67–1.94H × 1.8 = 1.206–3.492 → 1.2–3.5; widths .122H × 1.8 = .2196 → .22 | planted t34; one hit + star t36; spikes end t47 |
| 22.35 | Pillar off top of frame | at least 4.44H × 1.8 = 7.992 → 8 | t36–47 |

Uses the same 58-display burst as S3; short guard is removed 16 ticks before burst. Q/drop binding, ego_cost 100, resource_cost 0. Gold screen flash on the caster only. Deliberately original sequencing; matching it to a nonexistent source move is not a valid acceptance test.

Capture must set `ITEM=bastion-greatsword` because the capture script only strips `-sN` and `-def` from default item ids.
Pairs moments (motif alignment only): `21.45:guard 22.00:raise 23.20:burst 23.55:tail 23.65:cracks`. Expected output `build/pairs/bastion-greatsword-ego.png` (not generated).

Measurement method: hand-read proportions from the supplied video, not a recovered 3D scene. Body height H = 1.8 blocks. Foreshortened widths have about 10–15% uncertainty. Times below are video times; runtime tick = round((video time - case start) × 20). The supplied old analysis mislabels skill boundaries; the actual frames and wiki coin counts take precedence.

No new real-client capture was run: the task preamble prohibits local Docker/Gradle, and `vfx-shot.sh` invokes both internally. No image is claimed as a verified final pair. The previous S3 mismatch sheet was inspected at `/IdeaProjects/Astera/.claude/worktrees/agent-a313427dc7e668405/build/pairs.png`; it predates these edits.
