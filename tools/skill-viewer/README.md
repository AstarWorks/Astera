# :tools:skill-viewer

Static three.js replay of `:tools:game-sim` traces (`astera-sim-trace/1`), plus a headless renderer that turns a trace
into PNG frames, a contact sheet and a GIF so an AI agent can look at a skill. See ADR-0211. Not shipped, not in `check`.

Needs [bun](https://bun.sh) and a Chromium that `playwright-core` can launch (a Playwright browser cache or
`PLAYWRIGHT_BROWSERS_PATH`; `bunx playwright-core install chromium` installs one). `ffmpeg` is used for the GIF.

## 1. Make traces

```
./gradlew :tools:game-sim:showcase     # build/sim-traces/showcase-<skill>.json, one per file in content/skills
./gradlew :tools:game-sim:test         # all scenarios (also writes their traces)
```

A showcase puts the caster at the origin, a dummy mob in front (12 blocks for ranged skills, 3 for melee / AoE / buffs),
waits 5 ticks, casts once, and runs 100 ticks. Add a skill YAML and it gets a showcase automatically.

## 2. Look at a trace in a browser (humans)

```
cd tools/skill-viewer && bun install && bun run build
xdg-open dist/index.html        # or just open the file: no server needed
```

"Open trace" (or drag and drop) a `tools/game-sim/build/sim-traces/*.json`. Controls: play / pause (Space), scrub,
arrow keys step one tick, speed 0.1x-4x, camera presets (side, behind caster, top, front) and free orbit with the mouse.
The top-left overlay shows tick, sounds, status changes, projectile events and the live particle count.
Drawn: voxel ground, caster / target capsules with HP, projectiles, particles per vanilla key, damage numbers, explosions,
lightning (+ flash), glow outline, temporary blocks, display entities, camera shake.

Particle models live in `src/particles.js` (`TYPES`); add an entry when content uses a new key.

## 3. Render frames (AI review)

```
./gradlew :tools:skill-viewer:render -Pscenario=simple-shoot     # one skill
make skill-frames SKILL=simple-shoot                             # same
make skill-frames-all                                            # every skill
```

(or without Gradle: `cd tools/skill-viewer && bun run build && bun scripts/render.mjs --scenario simple-shoot`;
`--traces <dir> --out <dir> --cell 512x288` override defaults).

Output in `tools/skill-viewer/build/skill-frames/<skill>/`:

| File | Content |
|---|---|
| `sheet.png` | 4x4 contact sheet, side camera, 16 key ticks (before cast, cast, flight, impact, aftermath), each labelled with tick and offset from the cast |
| `sheet-behind.png` | same ticks from behind the caster |
| `anim.gif` | side camera, real time, from just before the cast until the effect dies down |
| `frames/key-<tick>.png` | the individual side-camera frames |

Programmatic API in the page: `window.viewer.load(trace)`, `.seek(tick)`, `.applyCamera(name)`, `.setCapture(true)`.
