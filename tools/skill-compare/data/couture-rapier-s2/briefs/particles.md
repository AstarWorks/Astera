Understanding: tools/skill-compare/data/couture-rapier-s2/beat-sheet/understanding-body.md (section "追加", rows P1-P3)

# Brief: S2 particles (limbus-vfx), worktree /IdeaProjects/Astera/.claude/worktrees/agent-a9b88b02af8128b5d

Goal: add THREE small, purposeful particle emitters on top of the approved structure. Concept: cut and stitch. Few, anchored to a named emitter, vanilla-first. Do NOT copy the reference's scattered white specks. No new sprites. Do not touch `couture-s2-coin1`, `couture-s2-hit1`, `couture-s2-trail`, the rig, the thread block geometry, or any model/skill file. Never `git push`; commit only if told (the director commits).

Read first: `docs/contributing/vfx-team/vfx.md` (if present), `docs/reference/vfx/README.md`, existing `content/particles/couture-s2-p-*.yaml` (format: `vanilla_particle`, `data`, `velocity`, `force: true`).

## Files you may create/edit
- `content/particles/couture-s2-p-tip.yaml` (P1): blade-tip cut glint. White `end_rod`, count 1, lifetime 6, speed ~0, velocity outward 0.01. Optional second pink dust (`dust_color_transition` white to pink 255,170,205, scale 0.8) in the same emitter is NOT allowed (one particle type per file): use end_rod only.
- `content/particles/couture-s2-p-stick.yaml` (P2): contact spark. count 3, lifetime 8, burst spread 0.12, `end_rod` outward speed 0.12. (A separate pink dust emitter `couture-s2-p-stick-pink`, count 2, dust_color_transition 255,230,240 -> 255,150,195, scale 0.9, is welcome.)
- `content/particles/couture-s2-p-stitch.yaml` (P3): stitch dot on the thread. `dust_color_transition` 255,255,255 -> 255,150,195, scale 0.7, count 1, lifetime 4, speed 0.
- `content/vfx/couture-s2-body-flight.yaml`, `couture-s2-blade-a-flight.yaml`, `couture-s2-blade-b-flight.yaml`: add one `particle` element each, `particle_id: couture-s2-p-tip`, `every_ticks: 1`, start_tick 3, end_tick 10, translate = the piece's own t3 keyframe translate plus z +0.45 (tip side, forward). The element rides the projectile, so it follows the piece.
- `content/vfx/couture-s2-body-stuck.yaml`, `couture-s2-blade-a-stuck.yaml`, `couture-s2-blade-b-stuck.yaml`: add one particle element each at the piece's stuck translate (see its keyframes, y 1.48), start_tick 0, end_tick 2, `every_ticks: 1`, P2 (and the pink variant if you made it).
- `content/vfx/couture-s2-thread.yaml`: add three `particle` elements (one per return-* item) with the SAME translate keyframes and `target_track` as the matching `return-body` / `return-blade-a` / `return-blade-b` element, same start_tick 15 / end_tick 21, `every_ticks: 1`, `particle_id: couture-s2-p-stitch`. Do not change any existing element or `target_line`.

## Limits (hard)
- at most 300 particles per tick, Display budget unchanged (particle elements are not Displays), `emit.size_over_life` at most 2 values.
- Total new particles per tick at most about 12.
- Run `./gradlew -q :tools:content-lint:run` and report `runtimeDecode: ... 0 failure(s)`.

## Report
List files changed and per emitter: anchor, count per tick, lifetime, colour. Do not capture in the real client (the director captures; one capture at a time).

## Applicable Rejected looks
No zero particles; no flat block-only effect; no copying reference specks; no jagged sprites (none used here).
