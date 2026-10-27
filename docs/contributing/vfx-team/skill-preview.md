# skill-preview

`make skill-preview SKILL=<skill-id>` renders a skill performance from the skill-compare camera without Minecraft: about 5 s for 54 frames at 1280x720 once the plan exists (plus ~10 s for `make skill-lint-plan` unless `REUSE=1`). It replaces `tools/vfx-preview` (deleted). Tool: `tools/skill-viewer/scripts/skill-preview.mjs` + `src/preview.js` (three.js in headless Chromium, same display math as the trace viewer, see `tools/skill-viewer/DISPLAY-SEMANTICS.md`).

Output in `build/skill-preview/<skill>/`: `frames/tick_NNN.png` (1280x720, tick 0 = cast), `ref/` (reference video frames), `strip.png` (per picked tick, rows: reference / preview / published real-client capture), `meta.json` (timings, warnings).

Options: `CASE=<id>` (default: the case whose `skill.asteraSkillId` is the skill), `TICKS=0-53` (default 0..intent `duration_ticks`), `EVERY=4` (strip column step), `FOV=<deg>` (vertical FOV; keeps the camera positions, no dolly), `REUSE=1`; script-only: `--size 640x360`, `--skin alex|steve|<png>`, `--client <dir>`, `--no-ref`, `--particles <file>`.

## What is drawn

| Part | Source |
|---|---|
| six skin boxes (vanilla player UV layout, base layer, classic/slim arms from `plan.slim`) | `plan.json` `ticks[].bones` (pos/rot/scale), skin Alex from the local 26.3 jar (the compare Caster's offline default; `--skin`) |
| one weapon ItemDisplay | `ticks[].weapon`; item = the item whose binding plays the skill, model `content/models/items/**/<item>.model.json` (cuboid `elements`, element rotation, `astera_tints`, `display.thirdperson_righthand`), textures `content/textures/**`. Items with only a generated icon draw as a flat slab |
| fx Block / Item / TextDisplay (bitmap-font sprites) | `plan.json` `displays` (the sim's real display events, client interpolation replayed) |
| caster travel (dashes) | `plan.json` `caster` |
| enemy | husk boxes at the intent's enemy position (the capture's dummy) |
| particles | `build/skill-preview/<skill>/particles.json`, always generated from the skill's real emissions (plan -> `tools/particle-sim`, the vanilla particle classes; reused while the plan is unchanged; `--no-sim` skips) |
| camera | the case intent camera keys (hard cuts, `transition: linear`, FOV dolly as `tools/vfx-shot/compare_plan.py`); caster at the intent origin facing +x |

`particles.json` = `{ticks: [{tick, particles: [{type, pos[x,y,z], size, sprite, rgba[r,g,b,a], light}]}]}`, caster-local at the cast (+x right, +y up, +z forward). Drawn as camera-facing quads; `size` is the quad HALF extent (`getQuadSize`), `light` 0-15 or a packed lightmap int; sprite resolves to `content/particle-skins/generated/<name>.png`, `content/textures/<name>.png` or the jar's `textures/particle/<name>.png`.

## Fidelity

Exact to the planner: poses, timing (state at integer ticks, display interpolation as the client), sprite placement and scale. Approximate: flat entity lighting (no lightmap/fog/shadow beyond a blob), no outline glow, no skin overlay layer, ground and sky are flat colours sampled from a real frame. Not the client: shader-patched skin UVs, the ADR-0275 item shader, packet lag (0-2 ticks), and the real capture's camera latency (cuts land up to ~100 ms late there). Known calibration gap: the real compare frames show a lower horizon and a larger caster than the intent camera at FOV 45 (effective vertical FOV nearer 60-66 with a closer camera); use the strip for pose/layout, not for pixel overlay, until the golden calibration of roadmap item 3 is done.

## make skill-3d (interactive)

`make skill-3d SKILL=<skill-id>` serves a three.js page (default http://localhost:8765, opens a browser when one exists; `NOOPEN=1` to only print the URL) built from the same scene code as the strip (`tools/skill-viewer/src/preview.js`, inputs shared in `scripts/lib/inputs.mjs`). It generates the plan (game-sim) and the particles (particle-sim) itself when they are missing or older than `content/{skills,vfx,items,animations,poses}`, so a repeat run on an unchanged skill is about 3 s and a cold run about 20 s (`REUSE=1` keeps plan.json, `FORCE=1` regenerates, `PORT=`, `CASE=`).

- Camera presets (keys 1-7): ref (the case intent camera over time), side, top-down, behind caster, front (enemy POV), first person (caster head, follows the rig), free fly (drag to look, WASD/QE, shift = fast). Dragging the mouse in ref / first person hands over to orbit at the current pose; orbit = drag, wheel, right-drag pan; "follow caster" moves the orbit target with the caster travel.
- Transport: play/pause (space), tick step (arrows), scrubber, speed 0.1x-2x, loop. Rig and fx displays are interpolated between ticks; particles are the 20 Hz states.
- Layers: particles / displays / weapon / caster / enemy.
- Hit events: damage / dash-hit actions read from the skill YAML (nested `delay_ticks` summed), shown as markers on the scrubber, a clickable list, and a red ring + HUD text for 3 ticks. Their position is the case dummy, not a simulated target.
- Headless screenshots: `make skill-3d SKILL=<id> SHOTS=build/skill-3d VIEWS=side,top,behind,front,fp,ref TICKS=10,45` writes `<skill>_<view>_tNNN.png` and exits (`bun scripts/skill-3d.mjs ... --ui` keeps the panels in the image).

Approximate (same as above, plus): flat lighting, no lightmap/fog shadows, no outline glow, no skin overlay layer; particles are camera-facing quads with the sim's size (half extent), rgba, packed light (brightness only) and roll (sign not verified against the client), no per-particle sub-tick motion; the fixed presets other than ref are our own framings, not client cameras.

## First-person check (caster's own view)

A skill is judged from the caster's own eyes as well as from the camera that films the reference. For anything the caster sees up close
(a weapon rig, a screen veil, a windup telegraph), run this before the round goes to review:

1. Run `make skill-lint SKILL=<id>` first. Check 1 (`visibleWeapons`) must PASS: the first-person view is where a doubled or missing held weapon is most obvious. Check 8 must PASS if the skill has a `windup`.
2. Film the two views of the same tick range, back to back, with the same `TICKS`:
   - `make vfx-shot SKILL=<id> VIEW=caster TICKS=<range>` (the caster, first person; 640x360)
   - `make vfx-shot SKILL=<id> VIEW=compare CASE=<case> TICKS=<range>` (the case camera; 1280x720)
3. Cross-check the two against each other and against lint check 1: the held weapon count in the `caster` frames equals the number of held rows of the inventory, and it equals what `compare` shows on the same tick. A disagreement means the first-person item or a rig part is double-drawn or hidden: fix the content, re-lint, re-film.
4. Screen veils: the `caster` frames are the only place the veil's real size shows. It must cover no more than 15 percent of the frame (hard maximum 18 percent) and must be visible in `caster` only, never in `compare` or `opponent` (default `viewers: caster`).
5. Windup telegraphs: in `opponent` (the target's view) the rim is at full size on the first windup tick and the fill reaches the rim on the impact tick; `caster` shows the same ground marker unless `viewers` says otherwise.

`make skill-3d ... VIEWS=fp` is the planner's first-person still; it does not replace the real-client `VIEW=caster` capture.

