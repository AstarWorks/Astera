# Judgement Rush — S2

Source: 14.9–18.5 s. Three wiki coins = three damage beats, t8/t26/t50. Range: stationary melee; the video shows two torso sweeps and an overhead cleave. The old 7-block dash and detached falling sword were removed. Importance 6/10.

| Frame | Element / hand measurement | Conversion / authored value | Timeline |
|---|---|---|---|
| 15.23–15.57 | First waist fan | radius 1.72H × 1.8 = 3.096 → 3.1, hand 0.58H × 1.8 = 1.044 → 1.05 | windup 0–4; hit t8; tail to t14 |
| 16.07–16.40 | Second waist fan | same radius, 300° yaw sweep | VFX t22; hit t26 |
| 16.73–17.07 | Sword shouldered high | blade 0.97H × 1.8 = 1.746 → 1.75, angle 215° | third rig starts t43, holds 4t |
| 17.40–17.57 | Vertical crescent + short ground discharge | cleave reach 1.72H × 1.8 = 3.096 → 3.1; spikes 0.36–0.64H × 1.8 = 0.648–1.152 → 0.65–1.15 | hit t50, six spikes t50–58 |
| 17.90–18.23 | Low recovery, residual ground light | effects disappear before next skill | rig until t66, case 72t |

Gold/white faces, brown rim and orange sparks. Six-part natural sword rig per beat. Estimated peak ≤ 46 displays including the six small finisher spikes; target radius 3.3. All three damage steps ignore invulnerability. Charge scales each beat and is spent by the last. No projectile or target-centred remote explosion.

Pairs moments: `14.95:windup 15.30:hit1 16.20:hit2 17.00:raise 17.40:cleave 18.10:recovery`. Expected output `build/pairs/bastion-greatsword-s2.png` (not generated).

Measurement method: hand-read proportions from the supplied video, not a recovered 3D scene. Body height H = 1.8 blocks. Foreshortened widths have about 10–15% uncertainty. Times below are video times; runtime tick = round((video time - case start) × 20). The supplied old analysis mislabels skill boundaries; the actual frames and wiki coin counts take precedence.

No new real-client capture was run: the task preamble prohibits local Docker/Gradle, and `vfx-shot.sh` invokes both internally. No image is claimed as a verified final pair. The previous S3 mismatch sheet was inspected at `/IdeaProjects/Astera/.claude/worktrees/agent-a313427dc7e668405/build/pairs.png`; it predates these edits.
