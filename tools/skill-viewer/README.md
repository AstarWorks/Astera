# :tools:skill-viewer

Static three.js replay of `:tools:game-sim` traces (`astera-sim-trace/1`), plus a headless renderer that turns a trace
into PNG frames, a contact sheet and a GIF so an AI agent can look at a skill. See ADR-0225. Not shipped, not in `check`.

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
./gradlew :tools:skill-viewer:render -Pscenario=bastion-greatsword-s3     # one skill, all three views
make skill-frames SKILL=bastion-greatsword-s3                              # same
make skill-frames-all                                                      # every skill
```

Without Gradle (traces from `./gradlew :tools:game-sim:showcase`):

```
cd tools/skill-viewer && bun install && bun run build
bun scripts/render.mjs --scenario crimson-chain-axe-s3                        # side + first + opponent, sheets + GIFs
bun scripts/render.mjs --scenario couture-rapier-s2 --views first --no-gif    # one view, sheet only
```

`--traces <dir> --out <dir> --cell 512x288 --views side,first,opponent --no-gif --all` are the other options.

Views (the same trace, three cameras):

| View | Camera |
|---|---|
| `first` | caster first-person: eyes at +1.62, looking along the cast direction (caster model hidden), FOV 70 |
| `opponent` | a spectator/opponent at min(4, target distance) blocks in front of the caster, eye height 1.62, looking back at the caster (FOV 70); the target model is hidden when the camera stands inside it |
| `side` | wide side camera, FOV 55 (the old default) |

Caster and target are Steve-sized boxes (0.6 x 1.8, eyes 1.62); damage ticks draw a red/white hit burst plus the damage number.

Output in `tools/skill-viewer/build/skill-frames/<skill>/`:

| File | Content |
|---|---|
| `sheet-<view>.png` | 4x4 contact sheet of 16 key ticks (before cast, cast, flight, impact, aftermath), labelled with tick and offset from the cast |
| `anim-<view>.gif` | real time, from just before the cast until the effect dies down |
| `sheet.png`, `anim.gif`, `frames/key-<tick>.png` | the side view (kept for old links) |

Custom particle sprites (ADR-0239: `type: sprite` VFX = a text display showing a glyph of font `astera:vfx/<id>`, one
text swap per flipbook frame) are drawn from `content/fonts/**` + their strip PNGs with the vanilla bitmap-font rules
(`DISPLAY-SEMANTICS.md` section 3a); `bun run build` embeds them.

Block / item / text display entities are drawn with the exact client semantics documented in `DISPLAY-SEMANTICS.md`
(transformation order, pivots, billboards, interpolation, brightness, glow outline). The trace carries them as a per-tick
`displays` array (`spawn` / `update` / `swap` / `teleport` / `remove`, additive to `astera-sim-trace/1`).

Programmatic API in the page: `window.viewer.load(trace)` (async), `.seek(tick)`, `.applyCamera('side'|'first'|'opponent'|'behind'|'top'|'front')`,
`.setCamera({pos,target,fov})`, `.measureDisplays()`, `.pixelBounds()`, `.setCapture(true)`.

## 4. Tests and calibration

```
bun scripts/calibrate.mjs       # known displays rendered in Chromium, drawn bounds asserted against the projection;
                                # PNGs in build/calibration/ (block identity, beam roll 45, sword fixed/none, text centre billboard)
./gradlew :tools:skill-viewer:calibrate    # same, via Gradle
```

## Interactive 3D page and headless strip

`make skill-3d SKILL=<id>` (`scripts/skill-3d.mjs` + `src/viewer3d.js`) and `make skill-preview` (`scripts/skill-preview.mjs`) share `src/preview.js` (scene) and `scripts/lib/inputs.mjs` (plan/particle generation, case, skin, weapon, sprites, hit events). See `docs/contributing/vfx-team/skill-preview.md`.
