# Inventory and beat sheet (workflow v4, step 0.5-3)

Team overview: [README.md](README.md). The skill author and every reviewer read this before touching a skill. Founder decision 2026-10-26; v3 re-confirmed 2026-10-27 after the S1 retrospectives; **v4 (inventory first) approved 2026-10-27** after the couture-rapier S2 shears retro ([retro-2026-10-27-fable-characteristics.md](retro-2026-10-27-fable-characteristics.md) §4).

## Why it exists

About 30 rounds of bastion-greatsword S1 failed because the reference was read as a **list of poses**, so nobody described the travel between poses, the direction of rotation was never written down, a cut went missing because nobody counted the swings, and a self-graded numeric lint passed 31/31. The beat sheet made the motion the thing that is written, checked and approved.

Then couture-rapier S2 matched the sheet's frames at ticks 9 / 41 / 72 and was still wrong: the reference throws **three separate needle blades** that stick in the enemy, and the build showed one rapier plus a sprite, because the sheet (ticks, coordinates, angles: the measurable surface) never asked **how many objects there are, what colour each is, what each is attached to, and whether it is a 3D thing or a plane**. The inventory (step 0.5) asks exactly that, before any frame is stepped, and it is what gate 1 approves. The beat sheet is now the motion annex of the inventory.

## Workflow v4 in one screen

```
0.5 Inventory      the author watches the WHOLE reference 3 times at normal speed and writes inventory.md (this doc,
                   "Inventory"): the motion in 3-5 sentences, then one row per object: count, colour, shape, motion,
                   anchor, 3D object / plane / line / particles, engine construction. No frame numbers, no ticks,
                   no coordinates. Counts and colours are [O] or a question to the founder, never a guess.
0.6 Inventory diff a fresh agent writes its own inventory from the reference, never seeing the author's; the author
                   diffs them (inventory-check.md). Any count / colour / anchor mismatch blocks gate 1.
1.  Beat sheet     the motion annex (8 columns, below), written from the reference only, every beat pointing at the
                   inventory rows it moves. No skill key exists yet.
3.  Founder gate 1 inventory + inventory diff + beat sheet + stick figures on the skill-compare page; approval FREEZES
                   both files.
4a. Structure      first build commit: one object per inventory row in its native construction (model / projectile /
                   anchored line), zero sprites, zero particles, checked in `make skill-3d` (orbit + top stills).
4b. Visuals        motion, then colour and shape, then detail; every commit live on dev with the reload verified.
5.  Capture        side, front, back, TOP-DOWN (mandatory), 45 deg high, eye height; skippedTicks = 0; enemy at the
                   intent distance, caster framing within 0.8x-1.25x of the reference.
5b. Round gate     round note (round-template.md): `## Inventory` diff first (every row ok, structure sha, skill-3d
                   stills), then compare sheet ref | this | previous, trace cast+N, framing, fidelity, "weaker than
                   previous: none"; `make round-gate` must PASS before any reviewer.
6.  Review         a fresh reviewer (new every round): inventory diff first ("row N: ref 2, build 0"), whole impression,
                   regression vs previous, then every sheet row ○ / × with evidence.
7.  Founder gate 2 in game, on dev. Final.
```

Do not start step 4 until the inventory diff is clean and gate 1 is approved. Lint (`make skill-lint`) is a preflight of invariants (held-weapon count from the inventory, grip from the item data, feet on the floor, display budget, event-bound hits, inventory models exist and are shown, no capture-distance bake); it says nothing about whether the skill matches the reference. **Priority: inventory (count, anchor) > motion > colour and shape > detail. A detail round before the inventory passes is wasted.** The pose library (keypoints, `tools/pose-solve/`) is a tool the author composes from; it is no longer a founder gate.

## Inventory (step 0.5): `tools/skill-compare/data/<case>/inventory.md`

Watch the whole reference 3 times at normal speed first. Frame stepping is for confirming a row, never for discovering one. At most 25 lines; no frame numbers, no ticks, no coordinates, no angles (those are the beat sheet's job and they hide the structure). The file is what gate 1 approves and what every reviewer diffs first.

```markdown
# Inventory: <case>   Reference: <video id> t=<a>..<b>   Watched: 3x normal speed

## 動き
<3-5 sentences: what the character does, in order, as a person would describe it to someone who has not seen it.>

## 物体台帳
| # | 物体 | 数 | 色 | 形 | 動き | 誰に/どこに付く | 3D物体/面/線/粒子 | 構成案 |
|---|---|---|---|---|---|---|---|---|
| 1 | rapier | 1 [O] | silver, gold guard [O] | thin straight blade | held, thrusts | caster right hand (held) | 3D object | item model `astera:couture-rapier` (held item) |
| 2 | needle blade | 3 [O] | pale gold [O] | needle, 1/3 blade length | flies to the enemy, sticks | enemy body (on hit) | 3D object | `projectile` skill x3, `astera:<model>` in visual.vfx, `hit_entity:` sticks it |
| 3 | thread | 3 [O] | white [O] | straight line | taut from hand to each needle | caster hand -> needle | line | two-end anchored line (caster bone -> hit entity) |
| 4 | slash arc | 2 [O] | white core, gold rim [O] | crescent | follows swing 1 and 2 | blade tip plane | plane | fixed-plane sprite on the swing plane |
| 5 | sparks | - | gold [O] | grain | burst from contact | contact point | particles | vanilla / display particles, anchor: contact |

## つながり
<which rows move together: thread 3 follows needle 2; sparks 5 fire on the hit of row 2.>

## 再現しないもの
<camera and edit phenomena: white-out, camera punch-in, close-ups, UI markers, hit-stop. Listed so nobody builds them as geometry.>

## 数えきれなかったもの
<each count or colour that could not be read: what was tried, the candidates; the founder decides at gate 1. Never a guess.>
```

Column rules:

- **数 (count)**: an integer with `[O]`, or a candidate list in 「数えきれなかったもの」. A count is never `[I]`: if the reference does not show it, ask the founder. Particles write `-`.
- **色 (colour)**: named colour with `[O]`; same rule.
- **誰に/どこに付く (anchor)**: who or what the object is attached to and when it changes hands (held in the hand / caster body / enemy body on hit / the ground / the swing plane / free flight). A thrown or split blade is **an object with its own row**, not a second held weapon. Exactly one row is the held weapon unless the reference plainly shows otherwise (the founder decides).
- **3D物体/面/線/粒子 (medium)**: `3D object` (has volume, seen from more than one side: a blade, a hammer head, a stuck needle), `plane` (slash arcs, rings, flashes that are a drawn plane), `line` (a thread, a chain, a beam with two ends), `particles` (grain). This column decides the construction; it is never changed to make the build easier.
- **構成案 (construction)**: the engine construction of that row: `3D object` = ItemDisplay with a model (`kind: {type: item, item_model: astera:<model-id>, display_mode: fixed}`), the held one is the item itself; a flying object = a `projectile` archetype skill (`params.visual.vfx`, phases `hit_entity:` / `hit_block:` / `miss:` / `expire:`); `line` = two-end anchored element; enemy-attached = `on_hit:` / `targets: {type: target}` / `follow`; `plane` = fixed-plane sprite; `particles` = particle elements. Every 3D row names its model id: search `content/models/items/` (and `manifest.json`) for an existing model first; a model that is built must be written back into this column (lint check 6 fails a listed model the skill never shows, and a shown model no row lists).

**Independent inventory (step 0.6)**: start a fresh agent with the reference only (never the author's inventory, sheet or notes) and the same template; it writes its own inventory. The author puts both side by side in `inventory-check.md` (`| 行 | author | fresh | agree / disagree |` per count, colour, anchor, medium) and resolves every disagreement by watching the reference again, not by argument. Anything that stays disputed goes to 「数えきれなかったもの」 for the founder. A count, colour or anchor mismatch blocks gate 1.

### Starting a new weapon under v4 (checklist for the pilot director)

Follow verbatim, in this order; each line names the file or command that proves it was done.

1. `make skill-compare-case ...` exists for the case; watch the whole reference 3 times at normal speed, not frame by frame.
2. Write `tools/skill-compare/data/<case>/inventory.md` with the template above (motion in 3-5 sentences, one row per object with count / colour / shape / motion / anchor / medium / construction, つながり, 再現しないもの, 数えきれなかったもの). No frame numbers, ticks or coordinates. Search `content/models/items/` and write the model id of every existing 3D object into 構成案.
3. Start a fresh agent (`limbus-reviewer`, mode "inventory") with the reference only; save its inventory and your diff as `inventory-check.md`. Fix count / colour / anchor disagreements from the reference; unresolved ones go to the founder in 「数えきれなかったもの」.
4. Write `beat-sheet.md` (8 columns, below) with every beat pointing at the inventory rows it moves; mirror tick ranges into `case.json` `beats`.
5. **Gate 1**: `make skill-round SKILL=<s> FILE=<inventory.md>` (attach the beat sheet and stick figures as further files / images), report to the manager, wait. Approval freezes `inventory.md` and `beat-sheet.md`.
6. **Structure commit**: one object per inventory row in its native construction (held item, `projectile` sub-skill with its model, anchored line, enemy-attached `on_hit:` element), zero sprites, zero particles. Check it with `make skill-3d SKILL=<skill> CASE=<case> SHOTS=<dir> VIEWS=orbit,top TICKS=<key ticks>`; the orbit and top stills go beside the round note. `make skill-lint SKILL=<skill> CASE=<case>` checks 1 (held weapons = inventory), 6 (inventory models exist and are shown) and 7 (no capture-distance bake) must PASS. Commit with `structure:` in the subject.
7. **Visuals**: motion, then colour and shape, then detail; every commit `make skill-lint` then `make dev-content` (or dev-pack / dev-plugins) with the reload verified.
8. **Capture** (side, front, back, top, high, caster; `DISTANCE=<intent enemy x>`; `skippedTicks = 0`), publish.
9. **Round note** (`round.md`): `## Inventory` first (one line per inventory row, ok / ng, `structure: <sha>`, `skill-3d: <orbit.png> <top.png>`), then the v3 sections; `make round-gate SKILL=<skill>` PASS.
10. **Fresh reviewer**, inventory first: its report's first line is `Inventory diff: ok | ng - row N: ref X, build Y`; a ng there returns to step 6 before any detail is discussed. Then gate 2 in game.

**Limit: at most 2 rounds per gate.** If a gate is not passed after 2 rounds, stop and escalate to the founder with ONE question (what must be decided, the evidence for each candidate, the recommendation). Never invent a new answer and start round 3.

## Reference frame quality (founder 2026-10-27)

`tools/skill-compare/scripts/prepare.mjs` now extracts the reference at the source stream's native resolution and fps as lossless PNG from the best yt-dlp stream (YouTube offers 1080p at most for every reference video; no 1440p/4K/60 fps exists). Rotation direction, contact timing and blade detail must be judged from **full-resolution single frames or zoomed crops** of those frames (`tools/skill-compare/cache/<case>/frames/frame_NNNNN.jpg`). Never judge them from downscaled contact sheets or strips alone: a 6-up sheet is about 480 px per frame and hides exactly the detail (blade edge, hand grip, foot contact) the beat sheet is supposed to record. Sheets are fine to find the frame; the cell evidence cites the full-resolution frame number.

## Beat sheet template (step 1): `tools/skill-compare/data/<case>/beat-sheet.md`

One row per beat. A beat is one continuous motion segment (wind-up, cut 1, turn, cut 2, recovery, ...), not one frame. **No blank cell.** If a cell cannot be filled, look at the reference again (frame by frame, successive frames, not stills). "n/a" is allowed only with a reason ("n/a: blade not moving, held"). **Every cell ends with one status mark:** `[O]` observed (visible in the reference frames; cite the frame or time), `[I]` inferred (3D filled in from cues such as body twist or overlap; name the cue), `[U]` unknown (not decidable from the reference; write what was tried and the candidates). `[U]` is the only allowed hole and is decided by the founder at gate 1. A top-down view is a hypothesis drawn from the reference, never extra evidence from it.

Eight columns. Coordinates, angles and camera columns are gone (v4): they described the capture, not the reference, and the one coordinate that mattered (`敵 (5.4, 0, 1.0)`) was the capture distance. The tip path and its rotation direction (camera and top) live in the 動き一文 and 振り数・方向 cells in words, with the vocabulary below.

| Column | What goes in it |
|---|---|
| beat | short name, number and tick range (`B3 cut 2, t14-19`; 20 TPS from the reference time and the case's `start_s`) |
| 動き一文 | one sentence of the travel: where the tip / object goes (`arc over the right shoulder, down to the left hip`), whether the blade slices or slaps (edge-leading / flat), lean and twist of the body |
| 振り数・方向 | which swing of the skill this beat is (`1`, `2`, or `-`; count all swings first, rule 2), **camera:** CW / CCW, **top:** CW / CCW (both, always; `none` with a reason when the tip does not rotate in that view), and `reverses: yes (<what>)` / `no` |
| 接触 | the contact tick (`t=7`), or `-`; what is hit and which inventory row hits it |
| 移動 | how far and in which direction the caster moves (dash, step, lunge): distance in blocks and caster-local direction, or `none` |
| 体 | feet (planted / lifted / sliding, step or pivot) and the body silhouette at the start and end of the beat, in words |
| 台帳行の参照 | the inventory rows this beat moves, appears or attaches (`#2 x3 leave the hand`, `#3 taut`, `#4 follows swing 1`). Every visible object in the beat is a row; a beat that shows something with no row is a gate-1 defect of the inventory |
| 音 | every sound of the beat with its tick and character (`t=6 low whoosh, rising`; `t=7 heavy metal impact + low thump`), or `none` |

## Vocabulary (reference frames)

All positions are **caster-local**: `forward` = where the caster faces at cast time, `right` = caster's right hand, `up` = world up. Angles in degrees. A beat that moves the caster (dash) records the displacement separately in the foot column; the tip coordinates stay caster-local at that tick.

**Camera frame.** The skill-compare camera looks at the caster from the caster's **right side**. On screen, caster-forward is screen-right, up is screen-up, and the caster's right side points at the viewer (toward the camera). Therefore:

- **camera CW** = on screen the tip moves up -> right -> down -> left, i.e. in caster terms *up -> forward -> down -> back*. An overhead chop (raised, then down in front) is camera CW.
- **camera CCW** = on screen the tip moves up -> left -> down -> right, i.e. *up -> back -> down -> forward*, or equally *forward -> up -> back*. A rising uppercut from low-front to overhead is camera CCW.
- Motion toward/away from the camera (left/right in caster terms) is **depth** in this view and has no camera direction. A horizontal sweep that is mostly depth reads as a thin, back-and-forth smear; write the camera direction of the vertical component only and put the real direction in the top-down column.

**Top-down frame (world Y).** Look straight down with caster-forward at the top of the image and caster-right on the right. **top CW** = tip moves forward -> right -> back -> left. **top CCW** = forward -> left -> back -> right. A sweep from the caster's right across the front to the left is top CCW (right -> forward -> left). This is the only view in which a horizontal sweep has an unambiguous direction, so the top-down column is never optional.

**Reference camera is not ours.** The 2D reference is a side view of the character with its own framing. Convert before writing the cell: mirror the reference if the character faces screen-left (so forward is screen-right, matching skill-compare), and state in the notes that you did. A direction is written in skill-compare terms, never "left to right in the video".

**Edge-leading and flat.** The blade has two sharp edges and two broad faces. Let `v` be the tip's velocity direction. **Edge-leading**: `v` lies in the plane of the broad face, i.e. the cutting edge is the front of the moving blade (it slices). **Flat**: `v` is along the broad face's normal (the blade slaps like a paddle). Between 0 and 90 degrees write the angle between `v` and the edge direction. "Visible face" names what the camera sees: the broad face (flat to the camera), the edge (thin line), or the back (spine). A thin dark line is an edge-on blade only after checking the previous frame (it can be a floor crack or a shadow).

**2D smear -> 3D arc.** A smear/after-image in the reference is the projection of the blade's path onto the image.

1. Find the **head** of the smear (brightest/widest, sharp end) and the **tail** (thin, fading). The motion goes tail -> head. Confirm with the previous and next frame: the head position in frame n+1 lies beyond the head in frame n.
2. A wide ellipse/ring means the swing plane faces the camera (vertical swing seen from the side). A thin line or a very flat ellipse means the plane contains the view axis (horizontal or depth sweep): the direction is in depth and must come from other cues.
3. Depth cues, in order of reliability: body twist and which shoulder leads; overlap order (blade in front of or behind the body/limbs); apparent size change (nearer = bigger); the foot that steps. Write the cue you used in notes.
4. One smear can cover several ticks. Split it into successive frames before assigning an angle per beat.

## Rules

1. **No blank cells, every cell marked.** Look again instead of guessing; if the reference truly does not show it, mark it `[U]` with what was tried and flag it for the founder at gate 1. That is the only allowed hole.
2. **Count the swings first.** Before any row, step the whole reference and write `Swings: N` at the top with the tick where each one starts. A sheet whose swing count is not stated is rejected. A missing cut is a gate-1 defect, not a polish issue.
3. **Direction from successive frames.** For every smear frame infer the direction from the frames before and after it, never from one still. Write the evidence (`head at frame 41 is ahead of head at frame 40`).
4. **Both views, every beat.** Camera CW/CCW and top CW/CCW are both written in 振り数・方向 because the side camera alone cannot tell them apart.
5. **Reversals are explicit.** Whenever the body turns back or the rotation sense flips between swings, 振り数・方向 says `reverses: yes (<what>)`.
6. **Trajectory, not poses.** 動き一文 describes the *travel*. A row that only restates the end pose is incomplete.
7. **Every object in a beat is an inventory row.** 台帳行の参照 names the rows; an effect the inventory has no row for is a defect of the inventory (go back to step 0.5), not a new column. Judging particles from the side camera alone is forbidden (see the review rules in [reviewer.md](reviewer.md)).
8. The inventory and the sheet are the build target and the review checklist: the author implements them, the reviewer diffs the inventory and marks the sheet rows. If the build disagrees, fix the build.
9. **Observation comes from the reference only.** Write the inventory and the sheet before any key for the skill exists. Never derive a cell from shipped keys, and never write numbers back after a build (that turns the sheet into a description of what was built). The S1 r3 sheet was derived from shipped keys and was wrong for exactly this reason.
10. **Gate 1 freezes the approved version** of both files. After approval the observation cells are read-only. If the founder, a reviewer or the author finds them wrong, make a new version with a diff that states which part of the reference was watched again and what changed, repeat the inventory diff and gate 1. Never edit the approved version to match the build; never reuse approval of an old version for a new one.
11. **At most 2 rounds per gate**, then escalate with one question (see the workflow section).
12. **Camera and edit phenomena are never built.** White-out, camera punch-in, close-ups, hit-stop, UI markers go to the inventory's 「再現しないもの」, never into a beat as geometry.

Sheet header (above the table):

```
Case: <id>   Reference: <video id> t=<a>..<b>   Mirrored: yes|no   Inventory: inventory.md v<N> (approved <date>)
Swings: N (starts at ticks ...)
Notes: cues used for depth, conversions, unknowns
Diagrams: top-down / side / front stick figures (below or attached)
```

Stick diagrams (gate 1): one per swing, three views (top-down, side, front), body as a stick figure, tip path as an arrow with its start/end ticks and the CW/CCW mark, each object drawn as its inventory row number. ASCII in the sheet is enough; the founder sees the same on skill-compare. Round notes hold text (max 20 kB) and up to 12 images (see [skill-compare-views.md](skill-compare-views.md)), so the inventory and the sheet are published with `make skill-round SKILL=<s> FILE=<inventory.md>` and the stick figures can be attached as images.

## The two-way inventory check replaces the sheet checks

v3 had a reverse read (sheet only) and a fact check (reference + sheet) of the beat sheet. Both only ever confirmed that the sheet's ticks and directions were consistent; neither asked how many objects there were. v4 folds them into the **independent inventory** (step 0.6, "Inventory" above): a fresh agent writes its own inventory from the reference, the author diffs count / colour / anchor / medium per row in `inventory-check.md`, and the beat sheet is checked by the same fresh agent in the same pass (swing count, direction in both views, reversals, contact ticks, 台帳行の参照 consistent with its own inventory). One record, one gate.

## Worked example: bastion-greatsword S1

The sheet for this skill is at `tools/skill-compare/data/<bastion-s1-case>/beat-sheet.md`; below is only the skeleton that shows the shape of a correct reading. Cells marked TBD are filled from the approved S1 beat sheet. The skeleton omits the travel, sound and status columns for width; the real sheet has all of them.

**Wrong reading (what 30 rounds were built on).** A pose list, no travel, no direction:

| beat | reading |
|---|---|
| wind-up | "sword low behind the body" |
| cut 1 | "horizontal full spin" |
| cut 2 | "low sweep" |
| recovery | "sword returns to the shoulder" |

Nothing says which way either cut turns, that cut 2 reverses, or how many swings there are. Two builders can satisfy this and produce opposite motions, which is exactly what happened.

**Correct reading skeleton.**

`Swings: 2 (cut 1 starts tick TBD, cut 2 starts tick TBD)`

| beat | 動き一文 | 振り数・方向 | 接触 | 移動 | 体 | 台帳行の参照 | 音 |
|---|---|---|---|---|---|---|---|
| B1 wind-up, tTBD | TBD | -; camera: TBD; top: TBD; reverses: no | - | TBD | TBD | #1 greatsword | TBD |
| B2 cut 1, tTBD | TBD | 1; **camera: CCW**; top: TBD; reverses: no | TBD | TBD | TBD | #1, #<arc row> | TBD |
| B3 turn, tTBD | TBD | -; camera: TBD; top: TBD; **reverses: yes (body)** | - | TBD | TBD | #1 | TBD |
| B4 cut 2, tTBD | TBD | 2; **camera: CW**; top: TBD; **reverses: yes (body, rotation)** | TBD | TBD | TBD | #1, #<arc row> | TBD |
| B5 recovery, tTBD | TBD | -; TBD | - | TBD | TBD | #1 | TBD |

TBD — filled from the approved S1 beat sheet. The bold cells are the founder-confirmed facts the skeleton must keep: cut 1 rotates counter-clockwise relative to the camera; cut 2 reverses the body and rotates clockwise. The top direction of both cuts is mandatory in the real sheet because the camera direction cannot carry it.

## Craft checklist for the beat sheet (P-numbers: [pixel-vfx-craft.md](../../reference/vfx/pixel-vfx-craft.md))

Add these to the sheet; they are checks on the cells above, not new columns of the reference reading.

- **Timing template (P3).** For every effect that appears in a beat, write its slots in ticks: anticipation (optional), impact (1-2 tick flash, the brightest), expansion (fast, ease-out), dissipation (slower, breaking into chunks and cooling down the colour ramp; shrinking is not an ending). Smoke starts late (about a fifth in).
- **Impact tick (P4).** One `impact tick` per hit, equal to the tick of the biggest coverage jump. The skill's `hit_stop` and `camera_shake` ticks (and the damage tick) are written next to it and must be the same number. Mismatch = fix before building.
- **Shape first (P2).** Name the one dominant shape per effect and say what the `lane: core` elements alone (the `vfx_intensity: minimal` view) show.
- **Heat budget (P5).** Name the hue families (at most two plus smoke) and which layer owns the hottest band.
- **Ground (P8) and loops (P9).** Ground rings: pivot on the floor, flattened; periodic parts: whole cycles per loop.
- **Missing vocabulary (P12).** If the reference needs something Astera has no element for, write it in the cell as `[U]` with the candidate; do not fake it.

## Learnings log

(The author appends dated bullets after each skill: `- YYYY-MM-DD <case>: <fact>`.)

- 2026-10-26 seed: created with workflow v3 after the bastion-greatsword S1 failures.
- 2026-10-27 S1 retrospectives: sheet from the reference only; O/I/U marks; travel and sound columns; two checks (reverse read + fact check); gate 1 freezes; 2 rounds per gate; round notes hold up to 12 images.
- 2026-10-27 bastion-greatsword-s2 r2: a rotation direction that looks undecidable on one frame is usually decided by the erosion order of the effect tail across the whole sequence (compare the same effect in an earlier swing) plus which side the effect rises from; write the frame numbers and a confidence in an evidence block. Contact-sheet labels drift when batches are concatenated: re-derive n = base + index per batch before quoting frame numbers.
- 2026-10-27 bastion-greatsword-s2: a second skill of the same weapon reuses the first skill's blade path (re-timed via `tools/pose-solve/author_bastion_s2.py`, imports `author_bastion_s1`) and its ring fx (copied, ids renamed) instead of regenerating sprites; the skill-lint left-hand/feet checks fail on long linear gaps between two very different poses (coil -> crouch), so add the crouch beat 1 tick earlier.
- 2026-10-27 bastion-greatsword-s2 round 2: stepped captures of S2 showed ring 1 about 9 ticks early and no ring-2 / chop fx and no visible rig motion after ~t13, while the dev server log showed three skill attacks 1 s apart (damage steps on time) and the founder saw the skill live as good. Treat the stepped capture's delayed-step timing as unverified until the runtime agent confirms; do not tune fx art from it. A later `SHOT_KEEP=1 TICKS=40` run overwrites `build/vfx-shot/<skill>/<view>` with a 41-frame set: publish first, and check `meta.json` `frames`. Captures do not take the e2e flock (vfx-shot README). The position trace is not recorded; read `data get entity Caster Pos` on a kept stack for net travel.
- 2026-10-27 workflow v4: inventory (count / colour / anchor / medium per object, watched 3x at normal speed, no frame numbers) before any beat sheet; independent inventory diff replaces Check A/B; beat sheet 15 -> 8 columns (coordinates and camera columns dropped); structure commit in `make skill-3d` before visuals; reviewer's first line is the inventory diff. Source: couture-rapier S2 shears (ref 3 needle blades stuck in the enemy; build 1 rapier + sprite, every frame tick matched).
- 2026-10-27 couture-rapier-s2 gate 1: tick = round((f-start)*2/3) at 29.97 fps; check the tick of every range END against the formula (Check A caught two off-by-one ends). The reference camera pans and punches in, so crop wide or view full frames; narrow crops lose the caster. Facing is read from face/hair/arm over 3+ frames, never one still: my first read of B9 (f893-904) was wrong (she faces screen-left, back to the enemy, same back-first dash as S1) and only the fact-check agent plus a re-look at f896/f900/f903 fixed it. A skill can have 0 body swings and 3 contacts (effect-driven coins): write "Swings: 3 = coins" and say the swing # column is the coin number. Mark a one-frame effect pop (f811) as "no sweep visible" instead of inventing a direction. Audio onsets live in the sibling analysis cache (`tools/skill-compare/cache/<case>/analysis.json`, onset s * 29.97 = f); hit ticks usually have no isolated onset (music floor). make-figures.py: use a collision-avoiding label helper and fixed-position note rows; S1's overview labels overlapped.
