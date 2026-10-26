# VFX team (director + specialists + reviewer)

Generic roles for any Astera skill, weapon effect or ambient VFX (founder 2026-10-24: not Limbus-specific). Limbus skill reproduction from reference videos is one use case; its procedure stays in [limbus-skill-reproduction.md](../limbus-skill-reproduction.md).

Origin, founder decision 2026-10-23. A previous pass gave each of the 10 persona weapons (ADR-0230) to one generalist agent. The result was cheap-looking:
blocky single-colour low-poly models, a slightly wrong sword orientation in hand, weak swing animation, effects made only of block displays with almost no particles,
low-res pixel-jagged sprites, and agents grading their own work. The fix is a **team per skill or effect**: one director and specialists, each with an accumulating knowledge base, and an independent reviewer.

The procedure and acceptance rows stay in [limbus-skill-reproduction.md](../limbus-skill-reproduction.md); art rules in [vfx-art-direction.md](../vfx-art-direction.md).
This page defines who does what and how work moves.

## Roles

| Role | Agent | Knowledge doc | Owns |
|---|---|---|---|
| Director | `vfx-director` | [director.md](director.md) | beat sheet, briefs, skill YAML, composite VFX, integration, capture, publish, routing diffs |
| Modeler | `model-artist` | [modeler.md](modeler.md) | weapon 3D models (`tools/model-forge`, `content/models/`) |
| Animator | `animator` | [animator.md](animator.md) | rig swing keyframes, caster movement vectors |
| VFX | `vfx-artist` | [vfx.md](vfx.md) | display core: 2D sprites, display elements, layering of the core |
| Particles | `particle-artist` | [particles.md](particles.md) | particle layer: vanilla particles, particle retextures, custom-particle foundation |
| Sound | `sound-designer` | [sound.md](sound.md) | per-beat SFX (free route only) |
| Reviewer | `vfx-reviewer` | [reviewer.md](reviewer.md) | independent judgement and diffs; read-only on content |

All are `model: sonnet` agents defined in `.claude/agents/{vfx-director,model-artist,animator,vfx-artist,particle-artist,sound-designer,vfx-reviewer}.md`. The main session stays a manager (`.claude/rules/manager-mode.md`): it launches the director (or the specialists for the director) and merges branches.

## The loop (workflow v3, founder 2026-10-26)

Details of steps 1-3 and the template: [beat-sheet.md](beat-sheet.md). Every role reads it.

```
1. Observe        author writes the beat sheet from the reference (one row per beat, no blank cell,
                  swings counted first, rotation direction from the camera AND from above)
2. Reverse check  a different AI sees only the sheet (not the reference) and re-describes the motion;
                  diff it with the author's reading; a gap = re-observe. No build before this passes.
3. Founder gate 1 the beat sheet (text + top-down / side / front stick diagram) published on skill-compare
4. Build          ONE author end to end, composing from the pose library (tools/pose-solve, keypoints) and
                  the specialists' parts; the sword is judged on matching the sheet's trajectory,
                  not on "moves big". Priority: sword > VFX > particles > posture.
                  Preflight: make skill-lint (one sword, both hands on the grip, feet on the floor, display budget)
5. Capture        real player path, views side, front, back, `top` (TOP-DOWN, mandatory: the only view that shows
                  rotation direction), `high` (45 deg), `caster` (player eye height)
6. Visual review  a fresh AI reviewer marks every beat-sheet row ○ or × with a reason on paired
                  ref|ours images. No free-form "looks good", no numbers. Each × becomes a diff [owner: role].
                  × -> back to 4 (only the affected roles run again)
7. Founder gate 2 in game. Approve -> done | reject -> comments become sheet-row diffs -> back to 4
                  (if the comment shows the sheet was wrong, back to 1)
Stop when every row is ○ with evidence AND the founder approves in game. Reviewer ○ alone is not done. An iteration count is never a stop condition.
```

Rules of the loop:

- Specialists never talk to each other; every cross-role need goes through the director (the author in step 4).
- The sheet is the contract: the author builds to it, the reviewer judges against it, the founder approved it. A change to the motion is a change to the sheet and repeats steps 2-3.
- The reviewer never edits content and never sees "work done" as evidence; it judges the published capture against the reference.
- A common-runtime fix (planner, mechanic) is not specialist work. The director raises it separately and, once fixed, every affected weapon is re-checked equipped.
- Each role appends to its own Learnings log after every skill. The next skill's brief links the relevant entries.

## Hand-off artifacts

| Artifact | Path | Written by | Read by |
|---|---|---|---|
| Beat sheet | `tools/skill-compare/data/<case>/beat-sheet.md` (template: [beat-sheet.md](beat-sheet.md)); `case.json` `beats` mirrors its ticks | director (author) | everyone, reviewer, founder (gate 1) |
| Reverse-check record | `tools/skill-compare/data/<case>/beat-sheet-check.md` (checker output + verdict) | director | founder (gate 1) |
| Briefs | `tools/skill-compare/data/<case>/briefs/{model,animation,texture,particles,sound,integration}.md` | director | matching specialist |
| Particle cues | `tools/skill-compare/data/<case>/deliver/particle-cues.yaml` | particle-artist | director |
| Specialist delivery | files in its territory + report (format in the agent file) | specialist | director |
| Brief sheet / pairs | `build/brief/<case>/`, `build/pairs/<case>.png` (`make skill-brief`, `make skill-pairs`) | any | reviewer, director |
| Capture | `build/vfx-shot/<skill>/<view>/` (`meta.json` provenance); views side, front, back, top-down, 45 deg high, eye | director | reviewer |
| Published capture | `build/skill-compare-published/<skill>/<view>/current.json` | director | reviewer, founder (approval review on skill-compare) |
| Review marks | reviewer report: every sheet row ○/× with reason, each × `[owner: role]` | reviewer | director |
| Founder approval | weapon REPORT: date + published revision; rejection comments become review diffs | director (records, via manager) | manager, reviewer |
| Learnings | `## Learnings log` of each knowledge doc | each role | next skill's roles |

`beats` in `case.json` only mirrors the sheet's tick ranges and hit ticks (for `BEAT_TICKS=case`); the page preserves unknown fields on save only if documented in `tools/skill-compare/README.md`, so the first director to use it checks that a Save in the page does not drop it.

## File territories

| Role | May create/edit | Must not touch |
|---|---|---|
| Director | `tools/skill-compare/data/<case>/**`, `content/skills/<skill>.yaml`, composite `content/vfx/<id>.yaml`, weapon REPORT | model, sprite, particle, sound source files |
| Modeler | `content/models/**` for its weapon, its model-forge project, named item model/texture files | skill YAML, `content/vfx`, sprites, particles, sounds, case data |
| Animator | `rigs:` swing/pivot/rest/tilt in the VFX file the brief names; movement params on the skill YAML lines the brief names | models, sprites, particles, sounds, trail styling, other skills |
| VFX | `content/textures/sources/sprites.yaml` entries, generated sprite outputs (`content/sprites`, `content/fonts/vfx`, `content/textures/generated/sprites`), `tools/item-art/sheets/` | skill YAML, models, sounds, rig swing keys, particles |
| Particles | `content/particles/<skill>-*.yaml`, particle (`emit`) elements inside fx files the brief names, particle texture sources/outputs for retextures, `deliver/particle-cues.yaml` | display-core sprites, rig, skill YAML, models, sounds |
| Sound | `content/sounds/**` for its skill, scripts/specs it adds under `tools/sound-gen/` | skill YAML, VFX, models, sprites |
| Reviewer | throw-away images under `build/` only | everything else (read-only) |

Two roles never edit the same file in one round. Where one VFX YAML mixes pieces (rig swing vs trail style), the director splits the assignment by section in the brief.

## Real-client capture (2026-10-26)

Workflow v3 step 5 needs the views `side`, `front`, `back`, `top`, `high`, `caster`. `top` (camera straight above the caster, forward at the top of the image) is mandatory because it is the only view that shows rotation direction; a review without it is invalid. Skill captures do not have a `top` view in `tools/vfx-shot` yet (only the animation mode does): until it exists the director raises it as a runtime request to the manager and does not substitute another view.

`make vfx-shot` boots an `astera-shot-<id>` stack (server + pack host) and headless clients (~2.5 GB each, server ~2.5 GB), films, and
**tears everything down when it exits** (success, error, Ctrl-C, TERM/HUP, its caller dying). Runs are serialized host-wide; with `SHOT_KEEP=1`
the stack and parked clients stay warm for the next capture from the same checkout and only what changed is synced.
Reference: [tools/vfx-shot/README.md](../../../tools/vfx-shot/README.md) (sections "Lifecycle", "Tick stamp").

```
SKILL=<skill> ITEM=<item-id> VIEW=compare CASE=<case> make vfx-shot      # one-shot: boot, film (1280x720, gif/mp4/sheet), tear down
SHOT_KEEP=1 SKILL=<skill> ITEM=<item-id> VIEW=compare CASE=<case> make vfx-shot   # keep it warm: the next capture takes ~30 s
SHOT_KEEP=1 SKILL=<skill> VIEW=side TICKS=40 BEAT_TICKS=0,8,20 make vfx-shot      # warm: ~12 s, 640x360, PNGs of the beat ticks only
make vfx-shot-up VIEW=compare CASE=<case>                 # create a kept stack and park the clients (~2 min the first time)
make vfx-shot-status                                       # fresh/STALE per layer, parked clients, kept-until, slots, memory
make vfx-shot-down                                         # drop this checkout's kept stack as soon as the round is done
make vfx-shot-reap                                         # kill stragglers of dead runs host-wide (SHOT_REAP_DRY=1 lists only)
```

- **Edit, then capture; nothing else.** On a kept stack, content (YAML, sprites, particles, animations) is pushed with `docker cp` and `/astera admin reload all` (seconds);
  textures / models / sounds / sprites rebuild the pack on the host and the parked clients reload it (10-20 s); a Kotlin plugin change rebuilds the seven jars and
  restarts the server (1-2 min, clients rejoin). The capture log prints `[+Ns]` per phase and `content: pushing` / `pack: pushing` /
  `plugins: restarting` so you can see which layer moved. Do not restart containers or copy files into the stack by hand.
- **Provenance is checked, not assumed.** `meta.json` `stack` carries the deployed content hash (verified inside the container), the pack sha1, the code hash and revision,
  and `workingTreeInSync`; if the tree changed while the capture ran, `captureQuality.invalid` is set and the capture must be redone. `make vfx-shot-status` shows the same
  comparison on demand.
- **Frame <-> tick is read from every frame.** A server-tick stamp is rendered into each recorded frame (masked out of the saved PNGs), so each frame's tick is read, not assumed. The default (STEPPED=1, 20 fps) freezes the server and `/tick step`s one tick at a time, waiting until the client drew it, so every tick has a frame and `skippedTicks` is 0 whatever the load. `FPS=60` stays realtime and may skip ticks (listed in `tickStamp.gaps`; any skip invalidates the capture, publish refuses it); `meta.json` `tickStamp` has `firstFxTickAfterCast` (real client display lag of the first effect) and `skippedTicks`. Quote it instead of guessing "client lag".
- **Lighter output.** caster / opponent / side views film at 640x360 (compare stays 1280x720; `RES=` overrides). `BEAT_TICKS=case` (the case's `beats[].tick`) or an explicit tick
  list saves only those PNGs (no sheet / gif / mp4, `beatFramesOnly: true`, not publishable): use it for pose / VFX iteration, and a full capture for the round you publish.
- **Lock and memory discipline.** One capture at a time on the whole host: the slot lock `/tmp/claude-1000/astera-vfx-shot.lock` (`SHOT_SLOTS`, default 1) queues every other
  run (`queued: all 1 vfx-shot slot(s) busy`). Do not use `/tmp/claude-1000/astera-e2e.lock` for captures. A kept stack (`SHOT_KEEP=1`) is bounded: it is removed
  `SHOT_KEEP_TTL` seconds (default = `SHOT_TIMEOUT`, 45 min) after its last run, or earlier when a run from another checkout needs the slot. Each run has a hard
  `SHOT_TIMEOUT` (45 min). Run captures in the foreground or with `run_in_background`; never `nohup ... &` and walk away (a run whose caller is gone is stopped, unless
  `SHOT_DETACHED=1`). Never kill another worktree's processes by hand or the `astera-dev-*` / `astera-e2e-*` containers; `make vfx-shot-reap` only removes stacks whose run is dead.

## Shared rules

- No push (the manager pushes), no unit tests, no gradle `check`; commit trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Real-client capture: see "Real-client capture" above; never wrap it in the e2e flock. All captures on the host queue on the vfx-shot slot lock; each run tears its stack down on exit unless `SHOT_KEEP=1` (bounded by `SHOT_KEEP_TTL`).
- No paid services (founder). The `generate-sounds` skill uses paid fal.ai; see [sound.md](sound.md).
- Only one weapon may be visible during a skill (mechanic `hide_held_item`; see [director.md](director.md) for where it lives).

## Confirmed runtime facts and rejected looks

Kept here once; each knowledge doc repeats the ones it needs.

Runtime facts (confirmed in the real client by earlier passes):

- Font sprites render only up to 256 px; 512 px fails.
- A TextDisplay with `fixed` billboard laid horizontal does not render in the real client (it does in vfx-preview).
- Hit VFX must peak on the damage tick.
- Machine load causes 2-6 tick (sometimes ~20) capture lag. Reject frozen or magenta-cube captures (`captureQuality.invalid`).

Rejected looks (founder / reviewer rejected these):

- crescents built from uniform boxes (hoops)
- fire made of squares (cubes)
- poles for jets
- a static ring standing like a wall
- separate floating sprites instead of one continuous sweep
- a pixel-art staircase at close-up
- black `#`-like smoke glyphs

## First use

The bastion-greatsword S1 pilot (branch `wp/pilot-bastion-s1`) is the first run of this team. Its director writes the briefs on that branch.
