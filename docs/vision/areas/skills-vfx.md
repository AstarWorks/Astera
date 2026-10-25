# Area: skills and VFX (Limbus track)

Owner track `wp/skills-vfx`. Items: Board prefix `SKL-`. Sources: handoff `limbus-skill-team` (2026-10-25), `docs/contributing/limbus-skill-reproduction.md`, `docs/reference/vfx/`.

## 構想 (source)
- Reproduce Limbus Company skills in Minecraft: motion first, then VFX (Display core + particles), then sound.
- Founder 2026-10-25: motion roughly the same as the reference FIRST; Minecraft-look design later; first person out of scope; match the third-person side view first.
- Work split by part: modeler, weapon motion, character body motion (skin rig, ADR-0250), BlockDisplay VFX core, particles, sound.
- **Done = reviewer PASS + founder approval.** The reviewer judges motion match per beat against the reference and the founder's words, never particle counts.

## 現状
- 85 skills, 211 vfx YAMLs in 10 families (bastion, couture, crimson, edict, ember, gossamer, orrery, sigil, vendetta, verdict). Tools: `make vfx-shot`, `make anim-shot`, skill-lint, skill-compare page.
- bastion S1 content r1-r26 rejected by the founder ("S1 got worse": near-zero particles, wrong swing/roll, 26 rounds of technical-only checks).
- ADR-0254 one-sword performance runtime (IPlayerPresentation lease, body planner, weapon on main hand) and skill-lint are merged on the Limbus branch but NOT verified in a real client; side effect: pose pinned STANDING during a skill.
- The other 9 families have never been reviewed against this standard (推測). Sound for these skills was never verified (captures are silent). 25 of 84 skills have no sound.
- Pipeline speed-ups proposed (warm vfx-shot stack, keypoint annotation -> IK, skill-viewer preview, local client particle sim) wait for a founder go.

## 理想形
Each skill is a short performance: caster body motion, one real weapon, Display-core VFX, finishing particles and sound, all driven from the reference beats. Reviewer rows are tied to per-beat reference frames; captures are client-faithful (never an approximation).

## 未決 (founder, unanswered)
- First-person empty hand and the global instant hotbar swap.
- ADR-0252 (server-wide particle reskin) and ADR-0253 (held-sword shading) landed without approval: keep or revert?
- When and by whom are the other 9 families re-reviewed?

## Governing ADRs
0231, 0238, 0239, 0241, 0250, 0252, 0253, 0254.
