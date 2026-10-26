# bastion-greatsword-s1 pilot: common rules (read first, every specialist)

Skill: `bastion-greatsword-s1` 月輪の薙ぎ (Zwei West Ishmael, 動画 `Svaaq9F7Ty8`, 11.633-14.233 s, 30 fps). Item: `bastion-greatsword`.
Branch `wp/pilot-bastion-s1` is the start point (commit `ee976100` + director commit). Work in your OWN worktree branched from it, commit there, never push.
Role names: model-artist (model.md), animator (animation.md), vfx-artist (texture.md, then the display-core part of particles.md; textures first), particle-artist (vanilla-particle part of particles.md, from the 2026-10-24 split), sound-designer (sound.md), vfx-reviewer (independent, judges on the compare site; not briefed here). The director owns `content/skills/bastion-greatsword-s1.yaml`, `case.json` and `briefs/`.

## Why a pilot
The founder judged the previous one-generalist weapons as cheap: blocky single-colour low-poly model, slightly wrong sword orientation, poor swing animation, VFX made of block displays with almost no particles, low-res 2D textures, and self-grading. This pilot perfects ONE skill with specialists, then the process is copied to the other weapons. **You never grade yourself as passed.** You deliver, attach evidence, list what is still wrong, and stop. The reviewer decides.

## Reference
- Frames (640x360, native 30 fps): `/tmp/claude-1000/-IdeaProjects-Astera/de20caa0-f14b-4c18-b441-07a23b79f448/scratchpad/pilot/frames/` (named `<group><n>_t<tick>_<what>_f<frame>.jpg`) and `contact-sheet-0..5.jpg` (12 frames each, labelled with frame number and tick; sheet k covers frames 12k..12k+11 counted from f349). Full set: `/IdeaProjects/Astera/tools/skill-compare/cache/bastion-greatsword-s1/frames/frame_NNNNN.jpg` (NNNNN = frame - 349).
- Beat sheet: `tools/skill-compare/data/bastion-greatsword-s1/case.json` -> `beats[]` (24 rows, Japanese; weapon orientation, caster move, every VFX element with hex colour and size in H). Read it fully. `notes.md` is the previous generalist's reading: treat as a hypothesis.
- Docs: `docs/contributing/vfx-art-direction.md` (Sakurai principles + part table + rig/trail), `limbus-skill-reproduction.md`, `add-a-skill-yaml.md`.

## Units and frames (all specialists use these)
- **H = 1.8 block** (caster height). In the long shots 1 block ~ 58 px of the 640x360 frame. Ranges in the brief are in blocks unless "H" is written.
- **Tick**: 20/s. `tick = floor((video_s - 11.633) * 20 + 0.5)`. Video tick = skill tick. **Contacts: hit 1 at tick 7, hit 2 at tick 31** (damage + hit_stop 2 ticks, caster included). Step-in dash 1: ticks 0-3 (1.6 block); dash 2: ticks 24-26 (0.7 block).
- **Effect clock**: the dash moves the caster (ticks 0-3 and 24-26) and `follow: false` effects stay at the anchor where they were spawned, so the swing effects are spawned AFTER the dash: `*-high` swing effects (`bastion-s1-rig-high`, `bastion-s1-fx-high`) at **skill tick 3**, `*-low` at **skill tick 27**. **Contact = effect tick 4 in both** (7-3, 31-27). The held sword is hidden from tick 0, so a static REST sword (`bastion-s1-rig-hold`, `follow: true`, played at skill tick 0) covers ticks 0-3, 21-27 and 42-46. Rig keyframes start one tick after spawn: verify the real blade position with `vfx-preview --freeze` before trusting a keyframe number.
- **VFX anchor frame** (`orient: yaw`, `follow: false`): origin = caster feet at the moment of the effect, +Z = forward (toward the enemy), +X = caster's LEFT, +Y = up. Rig pivot = the grip point in anchor coordinates. Euler = `(yaw, pitch, roll)` degrees as in vfx-art-direction.md §7; verify direction in the preview, do not assume.
- **Sword local frame** (rig and model): +Y = from the grip toward the tip, the flat face normal = rig-local Z, the edge directions = rig-local +-X. Origin = the grip point (where the two hands are). Total length grip-end to tip 2.7 block (grip 0.45 + guard 0.12 + blade 2.2), blade width 0.24, blade thickness 0.06 (derived from f412: guard-to-tip ~140 px / 58).
- **World pose "REST"** (used at ticks 0-5 and 20-27 and 41+): blade tip points BACKWARD (-Z), 20 degrees below horizontal (tip height 0.1-0.2 block above the floor), the broad face is on the left/right side (blade edges up/down), guard in front of the hip at y ~ 0.9, z ~ +0.25.

## Hard runtime facts (from the previous attempt; do not rediscover)
- Font sprites render up to **256 px**; 512 px fails. Sprite strips are square frames in one row.
- A TextDisplay with `fixed` billboard laid horizontal did NOT render in the real client (the offline preview shows it). Horizontal 3D shapes must be built from block displays or rig trail quads. Billboard-center sprites always face the camera.
- `emit.size_over_life` is a multi-key curve `[{ t, v, ease? }]` (any number of keys).
- Client interpolation: one update per tick; a rotation chunk above 60 degrees or a 1-tick ease above 170 degrees is rejected/misbehaves (see art-direction §7). Rig parts are **block displays only** (`VfxRigPart.block`), budget <= 60 displays alive per cast (sword rig <= 20, trail <= 24, fx <= 16), <= 256 slots per effect.
- `hide_held_item` already hides the player's own held sword for 46 ticks; the rig sword is the only sword visible.
- The old files `content/vfx/bastion-sweep-high.yaml`, `bastion-sweep-low.yaml`, `bastion-arc/ink/smoke/rim/crest` sprites and `bastion-s3-*` particles are **also used by S2/S3/EGO. Do not edit or delete them.** New work uses new ids `bastion-s1-*`.

## Rules for everyone
1. Own territory only (table in each brief; no overlap between specialists). If you need a change outside it, write it in your handback "requests" list.
2. No gradle `check`, no unit tests. Allowed: `./gradlew :tools:vfx-preview:run --args=...`, python tools, `make vfx-shot ...` for real-client captures (**one at a time**, slots are shared: `tools/vfx-shot/README.md`).
3. Evidence goes to `tools/skill-compare/data/bastion-greatsword-s1/evidence/<your role>/` (PNG/JPG, <= 1.5 MB each, <= 12 files). **Open every image yourself** before you describe it; a rendered but unopened image is not evidence.
4. Handback: commit; final message = sha, files changed, measured numbers vs targets (table), evidence paths, what is still wrong, requests. No "passes".
5. Pre-release posture: no deprecated aliases, no compatibility shims.

## File-territory overview (no overlaps)
| Role | May create / edit | Delivers to the director |
|---|---|---|
| model-artist | `content/models/items/bastion-greatsword.model.json`, `content/textures/blocks/bastion-greatsword-*.png`, `tools/model-forge/work/bastion-greatsword/**`, `tools/model-forge/batches/bastion-greatsword/**`, evidence/modeler | `deliver/rig-parts.yaml` |
| animator | `content/vfx/bastion-s1-rig-high.yaml`, `content/vfx/bastion-s1-rig-low.yaml`, `content/vfx/bastion-s1-rig-hold.yaml`, evidence/animator | `deliver/animation-notes.md` |
| vfx-artist | `content/textures/sources/sprites.yaml` (only `bastion-s1-*` lines), `content/sprites/bastion-s1-*.yaml`, `content/fonts/vfx/bastion-s1-*.json`, `content/textures/generated/sprites/bastion-s1-*.png`, `tools/item-art/scripts/bastion_s1_*.py`, `content/vfx/bastion-s1-fx-high.yaml`, `content/vfx/bastion-s1-fx-low.yaml`, `content/particles/bastion-s1-*.yaml`, evidence/vfx | `deliver/trail-bands.yaml`, `deliver/particle-cues.yaml` |
| sound-designer | `content/sounds/weapons/bastion-s1-*.ogg` + `.yaml`, `tools/sound-gen/scripts/bastion_s1_*.py`, `tools/sound-gen/specs/bastion-s1.yaml`, evidence/sound | `deliver/sound-cues.yaml` |
| director | `content/skills/bastion-greatsword-s1.yaml`, `case.json`, `briefs/**`, `deliver/**` merges, evidence/integration | |

`deliver/` = `tools/skill-compare/data/bastion-greatsword-s1/deliver/`. A specialist writes only its own deliver file. The director never edits a specialist's content file except the two integration edits named in integration.md (pasting trail bands, setting play_vfx ids).

## Effect ids (the interface; names are fixed)
| id | file | owner | content |
|---|---|---|---|
| `bastion-s1-rig-high` | `content/vfx/bastion-s1-rig-high.yaml` | animator | `rigs:` only (sword parts, swing, trail geometry). Played at skill tick 3. duration 20, `follow: false` |
| `bastion-s1-rig-low` | `content/vfx/bastion-s1-rig-low.yaml` | animator | same for hit 2. Played at skill tick 27. duration 18 |
| `bastion-s1-rig-hold` | `content/vfx/bastion-s1-rig-hold.yaml` | animator | static REST sword rigs, `follow: true`, played at skill tick 0, duration 48: three rigs (`hold_a` ticks 0-3, `hold_b` 21-27, `hold_c` 42-47) |
| `bastion-s1-fx-high` | `content/vfx/bastion-s1-fx-high.yaml` | vfx-artist | `elements:` only: sprites, streaks, flashes, display particles. Played at skill tick 3 (same anchor, same orient). duration 20 |
| `bastion-s1-fx-low` | `content/vfx/bastion-s1-fx-low.yaml` | vfx-artist | same for hit 2. Played at skill tick 27. duration 18 |
| particles `bastion-s1-*` | `content/particles/bastion-s1-*.yaml` | particle-artist | vanilla-particle emitters referenced by the fx files and by skill `spawn_particle` |
| sound events `astera:weapons/bastion-s1-*` | `content/sounds/weapons/` | sound | see sound.md |

Both rig and fx effects use the same anchor and the same `orient: yaw`, `follow: false`; they are layered by playing them at the same tick, so keep the effect ticks of the two files aligned (contact at effect tick 4).
