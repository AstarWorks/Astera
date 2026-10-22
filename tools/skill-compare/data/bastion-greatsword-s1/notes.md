# Moon Sweep — S1

Source: Svaaq9F7Ty8, 11.7–15.0 s (includes the anticipation before the brief's approximate 11.9 s boundary). Two coins = two damage beats, t4/t28. Range: stationary melee sweep; no dash. Importance 4/10.

| Frame | Element / hand measurement | Conversion / authored value | Timeline |
|---|---|---|---|
| 11.90, 12.07 | Sword about one body long, broad metal face | 0.97H × 1.8 = 1.746 → blade 1.75; width 0.18H × 1.8 = 0.324 → 0.32 | rig t0–19 |
| 11.90–12.23 | First gold fan at torso/waist, mostly horizontal | radius 1.72H × 1.8 = 3.096 → 3.1; hand 0.58H × 1.8 = 1.044 → 1.05 | sweep t0–6; hit t4; tail ≤ t10 |
| 13.07–13.40 | Second horizontal sweep, not an uppercut | same 3.1 radius; swept yaw 180 → -120 = 300° over 6t | VFX t24; hit t28; tail ≤ t34 |
| 13.73–14.90 | Blade low behind, recovery | angle 200–205°; no invented movement | second rig until t47; case through t66 |

Palette: natural iron, white edge, opaque gold fan, dark brown rim, small orange sparks. Natural blade lighting; trails emissive. Dark rim supplies contrast. Estimated fan maximum: ≤ 40 displays including six sword parts, under the 60 live cap. Runtime target radius 3.3 versus fan radius 3.1 (0.2 contact allowance).

Mismatch corrected in authoring: old immediate automatic damage preceded the visible slash, second beat was a rising crescent, sword was a glowing item projection. Actual silhouette, sampling, and hit-stop still require the client.

Capture: `SHOT_CACHE=<brief cache> SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=compare CASE=bastion-greatsword-s1 bash tools/vfx-shot/vfx-shot.sh`
Pairs moments: `11.75:windup 11.90:hit1 12.40:tail 13.10:hit2 13.40:fan 14.60:recovery`. Expected output `build/pairs/bastion-greatsword-s1.png` (not generated).

Measurement method: hand-read proportions from the supplied video, not a recovered 3D scene. Body height H = 1.8 blocks. Foreshortened widths have about 10–15% uncertainty. Times below are video times; runtime tick = round((video time - case start) × 20). The supplied old analysis mislabels skill boundaries; the actual frames and wiki coin counts take precedence.

No new real-client capture was run: the task preamble prohibits local Docker/Gradle, and `vfx-shot.sh` invokes both internally. No image is claimed as a verified final pair. The previous S3 mismatch sheet was inspected at `/IdeaProjects/Astera/.claude/worktrees/agent-a313427dc7e668405/build/pairs.png`; it predates these edits.
