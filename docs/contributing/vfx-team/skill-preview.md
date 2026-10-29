# skill-preview

`make skill-preview SKILL=<skill-id>` renders a skill performance from the skill-compare camera without Minecraft: about 5 s for 54 frames at 1280x720 once the plan exists. It replaces `tools/vfx-preview` (deleted). Tool: `tools/skill-viewer/scripts/skill-preview.mjs` + `src/preview.js` (three.js in headless Chromium, same display math as the trace viewer, see `tools/skill-viewer/DISPLAY-SEMANTICS.md`).

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
| particles | drawn from the trace's `particles` per tick |
| camera | the case intent camera keys (hard cuts, `transition: linear`, FOV dolly as `tools/vfx-shot/compare_plan.py`); caster at the intent origin facing +x |

`particles.json` = `{ticks: [{tick, particles: [{type, pos[x,y,z], size, sprite, rgba[r,g,b,a], light}]}]}`, caster-local at the cast (+x right, +y up, +z forward). Drawn as camera-facing quads; `size` is the quad HALF extent (`getQuadSize`), `light` 0-15 or a packed lightmap int; sprite resolves to `content/particle-skins/generated/<name>.png`, `content/textures/<name>.png` or the jar's `textures/particle/<name>.png`.

## Fidelity

Exact to the planner: poses, timing (state at integer ticks, display interpolation as the client), sprite placement and scale. Approximate: flat entity lighting (no lightmap/fog/shadow beyond a blob), no outline glow, no skin overlay layer, ground and sky are flat colours sampled from a real frame. Not the client: shader-patched skin UVs, the ADR-0275 item shader, packet lag (0-2 ticks), and the real capture's camera latency (cuts land up to ~100 ms late there). Known calibration gap: the real compare frames show a lower horizon and a larger caster than the intent camera at FOV 45 (effective vertical FOV nearer 60-66 with a closer camera); use the strip for pose/layout, not for pixel overlay, until the golden calibration of roadmap item 3 is done.

## make skill-trace / OVERLAY=1 (v2, SKL-08)

`make skill-trace SKILL=<id>` casts a v2 skill once in game-sim and writes `tools/game-sim/build/sim-traces/v2/<id>.json` (`astera-sim-trace/2`). `make skill-preview SKILL=<id>` runs it first, then renders and publishes `preview` and `preview-side`; `OVERLAY=1` also renders hit volumes (cyan), victim hitboxes (yellow) and contacts (red, with normal) and publishes them as the `lint` view. The interactive `structure stills`, `particle-sim` and the plan.json feed were removed.

## First-person check (caster's own view)

A skill is judged from the caster's own eyes as well as from the camera that films the reference. For anything the caster sees up close
(a weapon rig, a screen veil, a windup telegraph), run this before the round goes to review:

1. Check the held weapon count in the first-person view: a doubled or missing held weapon is most obvious there.
2. Film the two views of the same tick range, back to back, with the same `TICKS`:
   - `make vfx-shot SKILL=<id> VIEW=caster TICKS=<range>` (the caster, first person; 640x360)
   - `make vfx-shot SKILL=<id> VIEW=compare CASE=<case> TICKS=<range>` (the case camera; 1280x720)
3. Cross-check the two against each other and against lint check 1: the held weapon count in the `caster` frames equals the number of held rows of the inventory, and it equals what `compare` shows on the same tick. A disagreement means the first-person item or a rig part is double-drawn or hidden: fix the content, re-lint, re-film.
4. Screen veils: the `caster` frames are the only place the veil's real size shows. It must cover no more than 15 percent of the frame (hard maximum 18 percent) and must be visible in `caster` only, never in `compare` or `opponent` (default `viewers: caster`).
5. Windup telegraphs: in `opponent` (the target's view) the rim is at full size on the first windup tick and the fill reaches the rim on the impact tick; `caster` shows the same ground marker unless `viewers` says otherwise.

The real-client `VIEW=caster` capture is the only first-person authority.

