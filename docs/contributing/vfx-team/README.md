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

## The loop

```
1. Director   observes reference frame by frame
              -> beats in tools/skill-compare/data/<case>/case.json
              -> briefs/{model,animation,texture,particles,sound,integration}.md
2. Specialists (parallel, each in its own worktree/branch, own files only)
              model-artist | animator | vfx-artist (texture / display core) | particle-artist | sound-designer
              each returns files + a report + Learnings log entry
3. Director   merges specialist branches, writes skill YAML + composite VFX,
              captures in the real client, publishes to skill-compare
4. Reviewer   compares reference | intent | in-game, judges every acceptance row,
              outputs diffs each labelled with its owning role
5. Director   routes each diff to its role as a new brief section -> back to 2
              (only the affected roles run again)
6. Founder    after reviewer PASS the director reports to the manager, who asks the
              founder to review the published capture on skill-compare
              (https://skill-compare.astarworks.com)
              approve -> done | reject -> comments become diffs routed by role -> back to 2
Stop when the reviewer passes every row with evidence AND the founder approves on skill-compare. Reviewer PASS alone is not done. An iteration count is never a stop condition.
```

Rules of the loop:

- Specialists never talk to each other; every cross-role need goes through the director.
- The reviewer never edits content and never sees "work done" as evidence; it judges the published capture against the reference.
- A common-runtime fix (planner, mechanic) is not specialist work. The director raises it separately and, once fixed, every affected weapon is re-checked equipped.
- Each role appends to its own Learnings log after every skill. The next skill's brief links the relevant entries.

## Hand-off artifacts

| Artifact | Path | Written by | Read by |
|---|---|---|---|
| Beat sheet | `tools/skill-compare/data/<case>/case.json` field `beats` | director | everyone, reviewer |
| Briefs | `tools/skill-compare/data/<case>/briefs/{model,animation,texture,particles,sound,integration}.md` | director | matching specialist |
| Particle cues | `tools/skill-compare/data/<case>/deliver/particle-cues.yaml` | particle-artist | director |
| Specialist delivery | files in its territory + report (format in the agent file) | specialist | director |
| Brief sheet / pairs | `build/brief/<case>/`, `build/pairs/<case>.png` (`make skill-brief`, `make skill-pairs`) | any | reviewer, director |
| Capture | `build/vfx-shot/<skill>/<view>/` (`meta.json` provenance) | director | reviewer |
| Published capture | `build/skill-compare-published/<skill>/<view>/current.json` | director | reviewer, founder (approval review on skill-compare) |
| Review diffs | reviewer report, each diff `[owner: role]` | reviewer | director |
| Founder approval | weapon REPORT: date + published revision; rejection comments become review diffs | director (records, via manager) | manager, reviewer |
| Learnings | `## Learnings log` of each knowledge doc | each role | next skill's roles |

`beats` is a new field in `case.json`; the page preserves unknown fields on save only if documented in `tools/skill-compare/README.md`, so the first director to use it checks that a Save in the page does not drop it.

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

## Real-client capture (warm stack, 2026-10-26)

`make vfx-shot` used to build an image with gradle inside docker, boot a server and join two clients on every run (3-10+ minutes, plus a 30-60
minute queue behind e2e). It now keeps **one warm stack per checkout** (server + pack host + parked clients) and only syncs what changed.
Reference: [tools/vfx-shot/README.md](../../../tools/vfx-shot/README.md) (sections "Warm stack", "Tick clapperboard").

```
make vfx-shot-up VIEW=compare CASE=<case>                 # optional: create the stack and park the clients (~2 min the first time)
SKILL=<skill> ITEM=<item-id> VIEW=compare CASE=<case> make vfx-shot      # warm: ~30 s (1280x720, gif/mp4/sheet)
SKILL=<skill> VIEW=side TICKS=40 BEAT_TICKS=0,8,20 make vfx-shot          # warm: ~12 s, 640x360, PNGs of the beat ticks only
make vfx-shot-status                                       # fresh/STALE per layer, parked clients, locks, memory
make vfx-shot-down                                         # when the track is done (frees ~7 GB and the parked clients)
```

- **Edit, then capture; nothing else.** Content (YAML, sprites, particles, animations) is pushed with `docker cp` and `/astera admin reload all` (seconds);
  textures / models / sounds / sprites rebuild the pack on the host and the parked clients reload it (10-20 s); a Kotlin plugin change rebuilds the seven jars and
  restarts the server (1-2 min, clients rejoin). The capture log prints `[+Ns]` per phase and `content: pushing` / `pack: pushing` /
  `plugins: restarting` so you can see which layer moved. Do not restart containers or copy files into the stack by hand.
- **Provenance is checked, not assumed.** `meta.json` `stack` carries the deployed content hash (verified inside the container), the pack sha1, the code hash and revision,
  and `workingTreeInSync`; if the tree changed while the capture ran, `captureQuality.invalid` is set and the capture must be redone. `make vfx-shot-status` shows the same
  comparison on demand.
- **Frame <-> tick is measured.** `meta.json` `clapper` has the measured cast lag (`measuredCastLagMs`), tick rate, residual and `fitHolds` (a server tick boss bar is
  filmed in the first and last 1.2 s with the HUD up; the saved frames never show it). Quote it instead of guessing "+1-2 ticks of client lag"; the jitter that remains
  (+-1-2 ticks, bursty software client) is a property of the client, not of the offset. If `clapper.measured` is false the capture fell back to the assumed 60 ms.
- **Lighter output.** caster / opponent / side views film at 640x360 (compare stays 1280x720; `RES=` overrides). `BEAT_TICKS=case` (the case's `beats[].tick`) or an explicit tick
  list saves only those PNGs (no sheet / gif / mp4, `beatFramesOnly: true`, not publishable): use it for pose / VFX iteration, and a full capture for the round you publish.
- **Lock discipline.** Do not use `/tmp/claude-1000/astera-e2e.lock` for captures. A second capture from the same worktree waits (`queued: another capture is using this checkout's stack`); the global
  semaphore (`SHOT_SLOTS`, 2) and the memory guard (`SHOT_MIN_FREE_GB`, 8 GB) hold back captures from other worktrees. Never kill another worktree's stack or the `astera-dev-*` /
  `astera-e2e-*` containers; `make vfx-shot-down` only removes this checkout's own.
- Parked clients are frozen between captures (`parker.sh`), so an idle stack costs about one core and ~7 GB of RAM. `SHOT_EPHEMERAL=1` gives the old tear-down-at-the-end behaviour.

## Shared rules

- No push (the manager pushes), no unit tests, no gradle `check`; commit trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Real-client capture: use the warm stack (section "Real-client capture" below), never wrap it in the e2e flock. Captures of one checkout queue behind each other on the stack lock (different skills, cases and views all share the stack); captures of different worktrees run side by side under the slot semaphore and the memory guard.
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
