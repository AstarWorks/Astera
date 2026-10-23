# Brief: limbus-vfx, part 2 — particles, effect elements and the trail look

Read `common.md` and `texture.md` first (the sprite ids come from there). Role: everything that is not the sword and not its motion: the light/ring/streak/ink layers, display-particles and vanilla particles, and the colour of the sword's wind trail. The previous attempt was blocks + almost no particles.

## Goal
Reproduce the two hits' effect read, in Sakurai's few-and-smart way: a **crisp 2D painted core** (not a haze), a **flash**, **staggered sparks that mix dark and bright**, **dark smoke hand-off**, shown at the right tick relative to the damage tick; mix **vanilla particles** (volume, cheap) with **display-particles** (`kind: quad` / sprite: colour, alpha, size).

## Look at
Frames dir (see common.md): `b00..b05` (hit 1), `e00..e07` (hit 2), `f00`. Beats rows 3-9 and 12-20 (colours, sizes in H, layer, life). `docs/contributing/vfx-art-direction.md` §0 (principles), §0.5 checklist, §2 parts table (rows for arcs, impact, ring, speed lines), §5 display particles, `content/vfx/demo-quad-sparks.yaml` (a working `emit` + glyph example), `content/vfx/bastion-sweep-high.yaml` (how the previous elements were written; sprite elements use `billboard: center`, `see_through: true`), `content/particles/bastion-s3-*.yaml` (vanilla particle files format), `content/vfx/couture-rapier-*.yaml` (more `emit` users).

## Time grid (effect tick e; contact = e4; video ticks in brackets)
Hit 1 effect = `bastion-s1-fx-high`, spawned at skill tick 3, duration 20 (video: ring appears at skill tick 6 = e3, contact tick 7 = e4). Hit 2 effect = `bastion-s1-fx-low`, spawned at skill tick 27, duration 18 (ink flash at video tick 30 = e3, contact 31 = e4). Anchor frame: origin at the caster's feet, +Z forward, +X left, +Y up. The enemy is about 2.4 block ahead (hit 1) / 1.7 ahead (hit 2) after the step-in; the contact point (chest) is about `[0, 1.2, 2.4]` / `[0, 1.2, 1.7]`.

## Required layers
Sizes: H = 1.8 block. Counts are maximums; "stagger" means different start ticks, never all on one tick.

### Hit 1 (importance 8/10)
| layer | element (ids you choose, prefix `s1h1-`) | geometry | e-ticks | colours | notes |
|---|---|---|---|---|---|
| core | 2 `bastion-s1-crescent` sprites, billboard center | upper arc at about `[0, 2.0, 0.6]`, 5.0 wide; second crescent offset (left, lower) `[0.4, 1.3, 0.8]`, 3.8 wide, `roll` +-20 | e3 (frame0) -> e4 peak (frame2) -> thin e6-e9 -> gone e10 | sprite colours | these are camera-facing accents; the real 3D ring is the rig trail |
| core | `bastion-s1-streak` x2, billboard center | main: `[0, 1.1, 0.3]`..z 5.0, scale x 5.0, y 0.12, 2 blocks per tick growth out_expo; second thinner: y 1.28, x 3.2, y 0.07 | main e4 -> hold -> thin e5-e9, gone e12; second e5-e9 | `#FFFFF0` | white-hot; both tilted -6 deg so it is not edge-on from the first person |
| flash | `bastion-s1-glint` at the contact point + a black star behind it (same sprite tinted `#000000`, alpha 0.55, 1.25x) | `[0, 1.2, 2.4]` | e4 scale 0 -> 1.2 in 1 tick (out_back), fade by e7 | `#FFFFFF` -> `#FF9A30` | the dark star makes it readable on bright backgrounds |
| spark | display particles `kind: quad` `emit`: orange sparks | from `[0, 1.0, 2.2]`, direction forward, spread 28 deg | count <= 8, e4-e6 stagger, life 6-10, speed 0.35-0.6, size 0.03-0.08, gravity 0.02 | `#FFC040` -> `#FF7A12` -> `#B03000`, alpha 1 -> 0 | 2-element `size_over_life` (3 elements are dropped) |
| spark | 3 `bastion-s1-sliver` sprites flicked off the left crescent tip | around `[0.6, 1.5, -1.2]`..`[1.0, 1.8, -0.6]` | e3, e4, e5 | `#FFD23A` | each lives 3 ticks |
| smoke | 2 `bastion-s1-smoke` sprites, billboard center, flattened (scale y 0.2) at floor | `[0.5, 0.18, 2.2]`, `[0.2, 0.18, 1.2]` | e4-e12 (second +1 tick) | `#B8B8C0` -> `#4A4A52`, alpha 0.6 -> 0 | floor swirl; billboard sprites cannot lie flat, accept side-view only |
| vanilla | particles (files below) | see table below | | | |

### Hit 2 (importance 9/10; larger and heavier than hit 1 on every layer)
| layer | element (prefix `s1h2-`) | geometry | e-ticks | colours |
|---|---|---|---|---|
| flash/core | `bastion-s1-ink` sprite x2 (variants), billboard center, **behind the target** so it is a backdrop, not a screen blocker | `[0, 1.1, 3.4]`, 8.0 wide x 2.6 tall; second `[0.3, 0.8, 3.0]` 6 x 2, 1 tick later | e3 -> e4 (the 2 video frames), alpha 1 -> 0 by e5 (no longer than 3 ticks) | `#FFE45A` |
| core | 2 `bastion-s1-crescent`, low and big | `[0, 0.8, 0.5]` 5.5 wide `roll` +20; `[0.5, 0.5, 0.9]` 4.5 wide, `roll` +14 | e4 -> peak e5-e6 -> gone e11 | |
| core | `bastion-s1-streak` main at chest `[0, 1.3, 0.3]` x 5.5, y 0.16, plus **5 speed lines** (x 1.5-2.5, y 0.07) at heights 0.3, 0.55, 0.8, 1.05, 1.5 and sides +-1.0, forward z 0.5-2.5, staggered one per tick from e3 | e4 main..e10; lines live 4-6 ticks each | white / `#FFE070` |
| flash | glint + black star at `[0, 1.2, 1.7]` | e4, bigger (1.6), fade by e8 | |
| spark | orange display particles | count <= 10, e4-e7, same style as hit 1 + 4 from the hip `[0, 0.9, 0.3]` | |
| dark | 6 black specks: display particles `kind: quad` `#1A1206` alpha 0.9, size 0.04-0.09, from the feet outward, life 8-12, gravity 0.03 | e4-e8 | dark against bright |
| ground | thin flat block ring (`white_concrete`, scale y 0.02) from radius 0.5 to 3.2 over 3 ticks at y 0.1, faded by shrinking; 12 blocks max | e4-e7 | optional if the display budget is tight |

### Vanilla particles (files `content/particles/bastion-s1-*.yaml`, format of `bastion-s3-*.yaml`; wired by the director with `spawn_particle` delays, plus the two tip emitters wired by the animator)
| id | vanilla particle | count | where | skill tick (hit 1 / hit 2) | notes |
|---|---|---|---|---|---|
| `bastion-s1-hit-crit` | `crit` white | 10 / 16 | target chest, burst 0.3 | 7 / 31 | |
| `bastion-s1-hit-spark` | `electric_spark` or `end_rod`, speed along the streak | 6 / 10 | from `[0,1.1,0.5]` forward | 7 / 31 | thin, bright |
| `bastion-s1-ember` | `small_flame` orange `#FF8A1E` | 3 / 5 per tick, 4 ticks (stagger 1 tick) | along the sweep tangent from the blade tip | 5-8 / 29-32 | cheap volume |
| `bastion-s1-smoke-dark` | `smoke` dark | 6 / 10 | floor around the caster | 8 / 32 | the smoke "baton" after the flash |
| `bastion-s1-dust-gold` | `dust` `#FFD23A` size 1.0 | 10 / 16 | along the ring arc | 6 / 30 | |
| `bastion-s1-dust-black` | `dust` `#1A1206` size 1.2 | 6 / 8 | same arc, offset | 6 / 30 | black mixed in on purpose |
| `bastion-s1-ground-poof` | `poof` / `cloud` | 6 / 10 | feet ring | 6 / 30 | |
| `bastion-s1-tip-ember` | `small_flame` orange | 1 per tick | blade tip (animator rig emitter) | e2-e8 | tip point `[0, 2.55, 0]` sword-local |
| `bastion-s1-tip-glint` | `end_rod` | 1 per tick | blade tip (rig emitter) | e2-e7 | |
Vanilla total <= 120 particles in any one tick (limit 300).

## Display budget (alive at the same tick, for the whole cast)
The cast limit is **60 displays** (art-direction §5), shared: swing rig parts <= 20 + trail <= 24 alive + **your fx file: <= 16 alive** (sprites + display particles count 1 each). If your layout needs more, drop the ground ring and the second crescent first; report real numbers measured by counting the preview, not guessed.

## Trail bands (your delivery to the animator's rig, pasted by the director)
Write `deliver/trail-bands.yaml`: for `high` and `low`, a `bands:` list (2-3 entries: inner transparent `#00FFC040`, body `#C0FFC83A` -> `#30FFA818`, tip `#FFFFFBE0` -> `#80FFE070`), `edge` (white_concrete width 0.04), `dark_rim` (`#B0180C04` width 0.16), `fade_power`, `life_ticks` 8-10. Hit 2: body 25% denser and rim width 0.2. Format as in `vfx-art-direction.md` §7. Ring in the video: gold body, white-hot inner edge, dark outer rim; the trail must read as one fan face, not stripes (open top and side previews).

## Cue table (your delivery to the director)
`deliver/particle-cues.yaml`: one row per vanilla particle: id, skill tick for hit 1 and hit 2, target (`caster` / cone target), notes, and the exact display-particle counts you used. The director wires the `spawn_particle` steps; you do not edit the skill.

## File territory
`content/vfx/bastion-s1-fx-high.yaml`, `content/vfx/bastion-s1-fx-low.yaml` (`elements:` only, ids exactly these, `orient: yaw`, `follow: false`, `sample_ticks: 1`), `content/particles/bastion-s1-*.yaml`, the sprite files of `texture.md`, `deliver/trail-bands.yaml`, `deliver/particle-cues.yaml`, evidence/vfx. No edits to the old `bastion-*` files, rigs, skills.

## Self-check
1. `vfx-preview` of `bastion-s1-fx-high` / `-low` (side, top, eye, `--freeze 4:2`), contact sheets opened; also layered with the animator's rig if it exists (copy both into a scratchpad combo file, do not commit it).
2. Count displays alive per tick (script), table in handback.
3. Real client: `make vfx-shot SKILL=bastion-greatsword-s1 ITEM=bastion-greatsword VIEW=compare CASE=bastion-greatsword-s1` after the director wired the ids (ask via requests); at least the side and caster views at ticks around contact. One capture at a time (slots shared).
4. Pair each effect tick with the reference frame (`e03`, `e05`, `b01`, `b03`) in a labelled image and list differences honestly.

## Done means
- Both fx files and all particle files load; budgets met (numbers shown); every layer in the tables exists at the stated ticks +-1.
- Dark and bright are mixed, nothing starts on one tick as a block of identical particles, the flash precedes the smoke, the core reads in the real client frame (or "real client unverified" is stated).
- Trail bands delivered and previewed on the animator's rig (or placeholder rig).
- Evidence images opened; handback lists what is still wrong. No self-grading.
