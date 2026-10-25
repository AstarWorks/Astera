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
| particles | `build/skill-preview/<skill>/particles.json` when it exists, else skipped and the run says so |
| camera | the case intent camera keys (hard cuts, `transition: linear`, FOV dolly as `tools/vfx-shot/compare_plan.py`); caster at the intent origin facing +x |

`particles.json` = `{ticks: [{tick, particles: [{type, pos[x,y,z], size, sprite, rgba[r,g,b,a], light}]}]}`, caster-local at the cast (+x right, +y up, +z forward). Drawn as camera-facing quads; `size` is the quad HALF extent (`getQuadSize`), `light` 0-15 or a packed lightmap int; sprite resolves to `content/particle-skins/generated/<name>.png`, `content/textures/<name>.png` or the jar's `textures/particle/<name>.png`.

## Fidelity

Exact to the planner: poses, timing (state at integer ticks, display interpolation as the client), sprite placement and scale. Approximate: flat entity lighting (no lightmap/fog/shadow beyond a blob), no outline glow, no skin overlay layer, ground and sky are flat colours sampled from a real frame. Not the client: shader-patched skin UVs, the ADR-0253 item shader, packet lag (0-2 ticks), and the real capture's camera latency (cuts land up to ~100 ms late there). Known calibration gap: the real compare frames show a lower horizon and a larger caster than the intent camera at FOV 45 (effective vertical FOV nearer 60-66 with a closer camera); use the strip for pose/layout, not for pixel overlay, until the golden calibration of roadmap item 3 is done.
