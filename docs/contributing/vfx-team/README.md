# Skill team (one author + fresh reviewer + manager)

Process for any Astera skill, weapon effect or ambient VFX (founder 2026-10-24: not Limbus-specific). Limbus skill reproduction from reference videos is one use case; its procedure stays in [limbus-skill-reproduction.md](../limbus-skill-reproduction.md). Process revised 2026-10-27 after two independent retrospectives of bastion-greatsword S1 (about 30 rounds, founder: "if anything worse"), and again the same day to **workflow v4 (inventory first)** after couture-rapier S2 matched every sheet tick and still missed the three thrown needle blades ([retro-2026-10-27-fable-characteristics.md](retro-2026-10-27-fable-characteristics.md) §4). The directory keeps the name `vfx-team` for link stability.

**Starting a new weapon under v4**: follow the checklist in [beat-sheet.md](beat-sheet.md) "Starting a new weapon under v4" verbatim (inventory -> independent inventory diff -> gate 1 -> structure commit in `make skill-3d` -> visuals -> capture -> reviewer inventory-first). The verdict hammer (`tools/skill-compare/data/verdict-hammer-s1..s3`) is the v4 pilot.

Why the old 7-role split is gone: sword, VFX, particles, sound and body were owned by different agents, so a rig change left stale rings and particles behind, sound was never in sync with the blade, regen scripts went stale and nobody owned the whole skill. Specialist **knowledge** stays as reference docs; ownership does not.

**Goal: a respectful remake that plays well in Minecraft PvP / PvE, not a replica** (founder 2026-10-27: 「あくまでLimbus完全再現ではなく、Limbusをリスペクトして、このゲーム(PvPやPvEのマイクラ3Dゲーム)に合う形でリメイクする」). The reference sets the identity: pose silhouettes, key beats, big forms, mood. Timing may be compressed (a normal skill hits within ~8-10 ticks) and hit areas / ranges enlarged for play; projectiles fly in a Minecraft-sensible 3D way, never on the reference's frame timing or 2D path. Where fidelity and gameplay conflict, gameplay wins and the deviation is noted in the round note's "Gameplay" section (gate-checked; the reviewer weighs it like the visual rows).

Art rules: [vfx-art-direction.md](../vfx-art-direction.md). Acceptance rows: [limbus-skill-reproduction.md](../limbus-skill-reproduction.md). Round note template + gate: [round-template.md](round-template.md) / `make round-gate`. Why the round gate exists (S3 r1-r6): [retro-2026-10-27.md](retro-2026-10-27.md).

## Roles

| Role | Agent | What it does |
|---|---|---|
| Skill author | `limbus-director` | The single end-to-end author of one skill in its own worktree: object inventory and beat sheet from the reference, structure commit (one object per inventory row), weapon rig and body motion, composite VFX, particles, sound, skill YAML, capture, publish. Builds every piece itself. Puts every commit live on dev and verifies the reload. |
| Reviewer | `limbus-reviewer` | A **fresh** agent every round (never reused, never given the author's reasoning). Independent inventory from the reference, inventory diff first, then per-row ○/× with evidence. Read-only on content. |
| Manager | main session | Starts and merges work, deploys, inspects the page with its own eyes, reports to the founder in Japanese (10 lines, one question, page link). Does not implement. |
| Infra / runtime workers | ordinary worker agents, one per task | Fix `tools/vfx-shot`, skill-compare, dev deploy, skill-lint and runtime plugins. Separate from the author; never mixed into a content round. |

Optional knowledge helpers: `limbus-animator`, `limbus-vfx` (display core and particles), `limbus-modeler`, `limbus-sound`. The author may consult one for advice or a draft; a helper is not an owner, has no territory, and the author integrates and answers for the result. Their knowledge docs are reference, not roles: [director.md](director.md), [animator.md](animator.md), [vfx.md](vfx.md), [particles.md](particles.md), [modeler.md](modeler.md), [sound.md](sound.md), [reviewer.md](reviewer.md).

The main session stays a manager (`.claude/rules/manager-mode.md`). There is no territory table: the author edits every file of its skill (case data, skill YAML, `content/vfx`, particles, sprites, its weapon's models, sounds, animations) inside its own worktree.

## The process for S2 and later (founder-approved 2026-10-27)

A round means "through dev": a change that is not live on dev does not count as a round and is not reported. One skill = one worktree = one author. The reviewer is fresh each time.

| # | Step | Owner | Input -> output | Done when | Founder sees |
|---|---|---|---|---|---|
| 0 | Intake | manager | skill + persona + video section -> case skeleton, reference frames at native fps (no flicker), worktree, dev slot | reference GIF on the page; tick-to-video-time formula written | page (reference only) |
| 0.5 | Inventory | author | the whole reference watched 3 times at normal speed -> [inventory](beat-sheet.md) `inventory.md`: motion in 3-5 sentences, one row per object (count, colour, shape, motion, anchor, 3D object / plane / line / particles, engine construction with existing model ids), 再現しないもの (camera / edit phenomena) | no frame numbers, ticks or coordinates; every count and colour `[O]` or a question for the founder; **no skill keys exist yet** | - |
| 0.6 | Inventory diff | fresh reviewer | reference only (never the author's inventory) -> its own inventory; author diffs both -> `inventory-check.md` | count, colour, anchor and medium agree per row; disputes settled from the reference or sent to the founder; max 2 rounds | - |
| 1 | Beat sheet | author | reference frames only -> [beat sheet](beat-sheet.md) (8 columns, O/I/U marks, every beat pointing at its inventory rows), top / side / front stick figures, sound events | no blank cell; swing count, direction (camera and top), reversals, contact ticks, travel and sound in every beat | - |
| 3 | **Gate 1** | founder | inventory + inventory diff + sheet + figures + reference GIF on the page | approved; the approved versions are frozen; an x is recorded in inventory / sheet vocabulary and returns to 0.5 (max 2 rounds, then one question) | page |
| 4a | Structure commit | author | inventory -> one object per row in its native construction (held item, `projectile` sub-skill + model, anchored line, `on_hit:` element), zero sprites, zero particles; `make skill-3d` orbit + top stills; `make skill-lint` checks 1, 6, 7 | every inventory row has its object in the stills; stills beside the round note; commit subject `structure:` | page (stills) |
| 4b | Visuals | author | motion -> colour and shape -> detail (weapon keys, VFX, particles, sound, body). After every commit: `make skill-lint` (preflight), then `make dev-content` (or dev-pack / dev-plugins) | lint green; the dev command printed reload success and the skill id is live; the author self-checks sheet rows (self-scores are not submitted) | dev (shootable at any time) |
| 5 | Capture | author (tools) | same sha as dev; side, top, front, back (+ eye), tick-stepped; enemy at the case intent distance | `skippedTicks = 0`, every frame stamp read, opponent alive, sha equals dev, framing ratioToRef within 0.8x-1.25x; otherwise invalid and not published | - |
| 5b | **Round gate** | author | `## Inventory` diff (row by row: ref count / colour / anchor vs build construction / count / anchor, structure sha, skill-3d stills) + compare sheet `ref | this | previous` from the compare API + `[vfx-trace]` numbers -> [round note](round-template.md); the author looks at its own sheet before anyone else | `make round-gate SKILL=<s>` PASS: every inventory row ok, trace quoted for every vfx id, framing in tolerance, every mechanic type has a fidelity line, `weaker than previous: none` (luma / fx_pct are advisory, never a gate) | page (round notes) |
| 6 | Judge | fresh reviewer | reference + frozen inventory + frozen sheet + compare sheet -> (1) its own inventory from the reference and the **inventory diff** (`Inventory diff: ok | ng - row N: ref X, build Y`), (2) whole-impression verdict, (3) regression verdict vs previous round, (4) ○/× per row with evidence and a top-down arrow figure; sound is "not listened"; `review.json` `reviewer.inventory` / `reviewer.overall` / `reviewer.regression` | inventory ok, overall ok, regression none, all rows ○, or 2 rounds elapsed; an inventory ng returns to 4a before any detail is discussed | - |
| 7 | Inspect | manager | checklist: opened the compare sheet on the page, compared with the previous round, `make round-gate STAGE=manager` PASS, dev sha = page sha -> 10-line Japanese report quoting "one point to look at" | every checklist item true; a round that fails one is not reported | - |
| 8 | **Gate 2** | founder | shoots it on dev in game, listens to the sound | approved; an x becomes a sheet-vocabulary row and returns to 4; after 2 rounds one question. **In game is final** | in game (+ the page) |

Rules of the process:

- **Gate 1 on the page, gate 2 in game.** The skill-compare page is the only place a table, arrow figure, capture or A/B exists; if it is not on the page, it does not exist. A reviewer all-○ is never done; the founder in game decides.
- **Every fix is live on dev with the reload verified** in the same round. A fix on an unmerged branch is not reported. A failing `make dev-content` stops all other work until fixed. An aborted reload (for example a negative particle speed) is an error reported to the manager at once. The manager notes when the dev sha and the page sha differ ("not applied").
- **Inventory before motion, motion before detail.** The reference is first read as a list of objects (count, colour, anchor, medium), then as motion, never as a list of ticks. Priority: inventory (count, anchor) > motion > colour and shape > detail; a detail round before the inventory passes is wasted. The first build commit is the structure (one object per row, zero sprites, checked in `make skill-3d`), and the reviewer's first line every round is the inventory diff.
- **Lint is a preflight of invariants only** (held-weapon count from the inventory, grip from the item data, feet on the floor, display budget, event-bound hits, inventory models exist and are shown, no capture-distance bake; [skill-lint.md](skill-lint.md)). The author never defines or scores quality targets; luma, ink %, coverage and particle counts are not acceptance evidence and never a gate. Quality is the inventory diff and the sheet's ○/× only.
- **Facts come from tools, quality from eyes** ([retro-2026-10-27.md](retro-2026-10-27.md)). Timing (`cast+N` of every play and spawn), travel (caster position per tick) and framing (caster height % from the compare API `framing[]`) are read from the `[vfx-trace]` server log and the compare API, never estimated from frame labels or pixels. A timing or distance change in content is allowed only when the trace disagrees with the plan. Self-authored quality numbers stay forbidden; tool-measured facts are mandatory.
- **Never weaker than the previous round.** Every round shows `ref | this | previous` at the key ticks from one camera and states `weaker than previous: none`. Anything weaker is a × of the round even when its sheet row is ○; the fix is to restore, not to argue. The reviewer's first two outputs are the whole-impression verdict and the regression verdict; rows come third. "Weaker" is judged by eye and by the art-language checklist, never by coverage: `fx_pct` is a floor and a regression signal, not a target, and coverage gained with opaque flat fills is a regression.
- **Same weapon, same art language.** Every round sheet carries a row of the best accepted frames of the sibling skills of the same weapon / character (round note "Sibling", gate-checked), and the reviewer's whole-impression verdict includes `sibling: ok | ng` = same art language (stroke vs fill, band layering, taper, core line, glow, outline; [reviewer.md](reviewer.md) "Art language") and at least as refined. An S3 that looks cheaper than its S1/S2 is ng whatever its rows say.
- **Right medium per element.** 2D TextDisplay sprites are the right medium for slash arcs on the real swing plane and for small custom particles; the wrong medium for pillars / beams, plates, filled starbursts, big flat fills, screen overlays and large `billboard: center` stamps (those are particles plus thin solids). The round note's "Medium" section carries a per-element table (element, medium, reason); `billboard: center` sprites need a reason and are capped by the gate (10 / 30 % of display elements); sprites over ~2 blocks need their size and reason; a pillar / beam as one sprite fails. The reviewer checks `side` for misuses that "read as a sticker" (never a slash arc); the rig pose is a reviewed row that must read in both `compare` and `side`; camera framing `ratioToRef` is 0.8x-1.25x (gate).
- **Hits are events, not ticks; no camera-tuned fakes.** Thrown weapons are projectiles, stuck objects and hit effects spawn from `on_hit:` / `hit_entity:` on the hit entity, never at a tick offset that only works at the capture `DISTANCE` (couture-rapier-s2: 「テストのための実装感がぬぐえない」). `make skill-lint` check 5 fails the fakes; every hit skill's round note shows a miss case and an off-axis / moving-target case; the reviewer has a "no camera-tuned fakes" row.
- **Plays well first.** Every round note carries a "Gameplay" section (startup ticks to the first hit, reach in blocks, projectile flight, dash usefulness, PvP / PvE notes, deviations from the reference); the reviewer fails "looks right but plays weak" with the same weight as a visual ×.
- **Particles are designed, not copied.** The reference governs pose, timing, camera and the large forms only. Particles are free design from the skill concept (type, emission point, count, speed, spread, lifetime, colour chosen for Minecraft), anchored to the animation and the weapon (tip / edge trail, contact point, dash vector, landing). Round note "Medium" `particle concept:` lines per beat (intent, anchor, params), gate-checked; the reviewer judges looks and motion linkage, not resemblance to the reference.
- **A capture is review-valid only with reference framing.** The compare API `framing[].ratioToRef` (caster height as % of the frame vs the reference) must be within 0.8x-1.25x; a far wide-angle side view (caster at 1/40 of the frame) or a 2x-too-close preview is not evidence. The enemy stands at the case intent distance (`DISTANCE=`), never the tool default.
- **Tool fidelity before judgement.** Every round lists each mechanic type of the skill (`screen_flash`, `send_title`, `camera_shake`, particles, sprites, sound) as `shown (view tick)` or `not shown -> gate 2`. Nobody judges an effect the capture cannot show (spectator sees no titles; the preview draws world-space fx full-screen); it is checked by the founder in game.
- **Sound passes only by a listener**, never by feature numbers: in-client recording plus A/B listening ([sound.md](sound.md)). Sound events are sheet columns from step 1 and go on dev in the same round as the sword.
- **A capture with skipped ticks is invalid.** It is not judged and not published; it is an infra issue for the runtime worker, not a content round. A 60 fps container full of duplicated frames is not coverage. Preview renderers are for work in progress only; pass/fail is the real client.
- **One held weapon; every inventory row is one object.** Exactly one weapon is held (continuous keys from spawn to despawn) unless the inventory says otherwise; every other inventory row is built in its native construction (model / projectile / anchored line) and may be visible at the same time. A thrown or split blade is an object, not a second weapon. Rings, trails and tangent particles derive from the blade-tip plane; a mismatch is fixed by fixing the weapon, never by offsetting the VFX. Display is the core, particles polish it; zero particles or a flat single-colour block-only effect fails.
- **Camera and edit phenomena are never built.** White-out, camera punch-in, close-ups, hit-stop and UI markers of the reference go to the inventory's 「再現しないもの」; nobody builds them as geometry or beats.
- **Max 2 rounds per gate**, then ONE question to the founder with evidence and a recommendation. Escalate at once (without waiting for round 2) when: a cell cannot be decided from the reference, Sakurai principles conflict with Limbus fidelity, capture infra has been broken over 30 minutes, a reload failure is not fixed within one round, or the sword and VFX planes cannot be made to agree.
- A founder x becomes sheet vocabulary or a reviewer row the same day, not a "be careful" note.
- Reports to the founder: Japanese, 10 lines, one decision, page link.

## Hand-off artifacts

| Artifact | Path | Written by | Read by |
|---|---|---|---|
| Inventory (frozen after gate 1) | `tools/skill-compare/data/<case>/inventory.md` (template: [beat-sheet.md](beat-sheet.md) "Inventory") | author | reviewers, founder, `make skill-lint`, `make round-gate` |
| Inventory diff record | `tools/skill-compare/data/<case>/inventory-check.md` (fresh agent's inventory + the per-row diff) | author (diff) + fresh reviewer (its inventory) | founder (gate 1) |
| Beat sheet (frozen after gate 1) | `tools/skill-compare/data/<case>/beat-sheet.md` (template: [beat-sheet.md](beat-sheet.md), 8 columns); `case.json` `beats` mirrors its ticks | author | reviewers, founder |
| Structure stills | `tools/skill-compare/data/<case>/skill-3d/{orbit,top}.png` (`make skill-3d`) | author | reviewer, founder, `make round-gate` |
| Brief sheet / pairs | `build/brief/<case>/`, `build/pairs/<case>.png` (`make skill-brief`, `make skill-pairs`) | any | reviewer, author |
| Capture | `build/vfx-shot/<skill>/<view>/` (`meta.json` provenance, `skippedTicks`) | author | reviewer |
| Published capture | `build/skill-compare-published/<skill>/<view>/current.json` | author | reviewer, founder |
| Round note + compare sheet | `tools/skill-compare/data/<case>/round.md` (+ `compare/sheet.png`, `compare/compare.json` from `/api/cases/<id>/compare`); template [round-template.md](round-template.md); checked by `make round-gate` | author | reviewer, manager, founder |
| Review marks | reviewer report (first line `Inventory diff: ok | ng - row N: ref X, build Y`) + `review.json` `reviewer.inventory` / `reviewer.overall` / `reviewer.regression` / per-row marks | fresh reviewer | author, manager |
| Founder approval | weapon REPORT: date + dev sha; rejection comments become sheet rows | manager | author, reviewer |
| Learnings | `## Learnings log` of each knowledge doc | author | next skill |

`beats` in `case.json` only mirrors the sheet's tick ranges and hit ticks (for `BEAT_TICKS=case`); the page preserves unknown fields on save only if documented in `tools/skill-compare/README.md`, so the first author to use it checks that a Save in the page does not drop it.

## Real-client capture (2026-10-26)

Process step 5 needs the views `side`, `front`, `back`, `top`, `high`, `caster`. `top` (camera straight above the caster, forward at the top of the image) is mandatory because it is the only view that shows rotation direction; a review without it is invalid. Side / top views place the enemy at `DISTANCE` (tool default 4): pass the case intent distance explicitly (S3: `DISTANCE=7.2`) or the dash stops short and every travel reading is wrong. Review views must also match the reference framing (`framing[].ratioToRef` within 0.8x-1.25x; `FOV_DOLLY`, see the vfx-shot README): a capture outside that is not review-valid.

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
- **Frame <-> tick is read from every frame.** A server-tick stamp is rendered into each recorded frame (masked out of the saved PNGs), so each frame's tick is read, not assumed. The default (STEPPED=1, 20 fps) freezes the server and `/tick step`s one tick at a time, waiting until the client drew it, so every tick has a frame and `skippedTicks` is 0 whatever the load. `FPS=60` stays realtime and may skip ticks (listed in `tickStamp.gaps`; any skip invalidates the capture, publish refuses it); `meta.json` `tickStamp` has `firstFxTickAfterCast` (real client display lag of the first effect) and `skippedTicks`. Quote it instead of guessing "client lag". A publishable capture needs `skippedTicks = 0`, all stamps read, opponent alive, content sha equal to dev. Anything else is invalid (infra work, not a content round). Never count interpolated or duplicated frames as observation.
- **Lighter output.** caster / opponent / side views film at 640x360 (compare stays 1280x720; `RES=` overrides). `BEAT_TICKS=case` (the case's `beats[].tick`) or an explicit tick
  list saves only those PNGs (no sheet / gif / mp4, `beatFramesOnly: true`, not publishable): use it for pose / VFX iteration, and a full capture for the round you publish.
- **Lock and memory discipline.** One capture at a time on the whole host: the slot lock `/tmp/claude-1000/astera-vfx-shot.lock` (`SHOT_SLOTS`, default 1) queues every other
  run (the capture farm has its own lock, see above) (`queued: all 1 vfx-shot slot(s) busy`). Do not use `/tmp/claude-1000/astera-e2e.lock` for captures. A kept stack (`SHOT_KEEP=1`) is bounded: it is removed
  `SHOT_KEEP_TTL` seconds (default = `SHOT_TIMEOUT`, 45 min) after its last run, or earlier when a run from another checkout needs the slot. Each run has a hard
  `SHOT_TIMEOUT` (45 min). Run captures in the foreground or with `run_in_background`; never `nohup ... &` and walk away (a run whose caller is gone is stopped, unless
  `SHOT_DETACHED=1`). Never kill another worktree's processes by hand or the `astera-dev-*` / `astera-e2e-*` containers; `make vfx-shot-reap` only removes stacks whose run is dead.

### Capture farm: skills appear on skill-compare by themselves (2026-10-27)

You do not need to run `make vfx-shot` just to get a skill onto skill-compare. A daemon (`make vfx-auto-up`, one per host) watches the dev
autodeploy log (`.claude/state/dev-deploy/deploy.log`). For every `deployed <sha>` it diffs `content/{skills,vfx,animations,sounds,poses}`
against the previous sha, maps the changed files to skill ids (`tools/vfx-shot/changed-skills.py`: a changed vfx/animation/sound file
re-films every skill that references it), films each skill from `top` and `side` on a warm pinned stack and publishes it. On the page the chips read
`auto <sha7>`; a first realtime pass shows `PREVIEW (realtime)` within about a minute and is replaced by the exact stepped capture
(slow-motion take, one frame per tick). A stepped capture with skipped ticks is retried once, never published, and the preview stays.

```
make vfx-auto-status                       # daemon, last run (sha, seconds, per-skill ok/FAILED), warm stack, log tail
make vfx-auto-once SHA=<sha> [BASE=<sha>]  # process one commit now (what the daemon does for a deployed line)
make vfx-auto-down                         # stop the daemon and drop its stack
tail -f .claude/state/capture-farm/farm.log   # what it did, with seconds per pass
```

The farm uses its own worktree (`.claude/worktrees/capture-farm`, detached on the deployed sha), its own slot lock
(`/tmp/claude-1000/astera-vfx-shot-farm.lock`) and a pinned stack that `reap.py` never evicts, so your `make vfx-shot` runs are not queued behind it.
Compare views (reference video next to the capture) still need `VIEW=compare CASE=<case>` from the author; the farm only does `top` + `side`.
Knobs: `FARM_VIEWS` (`side,top`; filmed and published one view at a time, side first), `FARM_TICKS` (80, or the frame count already published for the skill), `FARM_MAX_SKILLS` (8),
`FARM_PREVIEW=0` (skip the realtime pass), `FARM_RATE` (slow-motion tick rate of the first attempt, 2.5; the retry runs at half: 1.25), `FARM_RETRIES` (1), `FARM_TTL` (idle stack lifetime, 24 h).

## Shared rules

- No push (the manager pushes), no unit tests, no gradle `check`; commit trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Real-client capture: see "Real-client capture" above; never wrap it in the e2e flock. All captures on the host queue on the vfx-shot slot lock; each run tears its stack down on exit unless `SHOT_KEEP=1` (bounded by `SHOT_KEEP_TTL`).
- No paid services (founder). The `generate-sounds` skill uses paid fal.ai and is not used here; CC0 recorded sources with provenance are allowed, game audio is never shipped or derived; see [sound.md](sound.md).
- Only one weapon may be **held** during a skill (mechanic `hide_held_item`, on main; `content/skills/bastion-greatsword-s2.yaml` is an example) unless the inventory says otherwise; objects listed in the inventory (thrown blades, stuck needles, split parts) may each be visible, built as their own objects.

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

The bastion-greatsword S1 pilot (branch `wp/pilot-bastion-s1`) was the first run and the source of the 2026-10-27 retrospectives. Its sheet predates this process (it was derived from shipped keys) and is rewritten from the reference first; S1 is not complete until the founder approves it in game.
