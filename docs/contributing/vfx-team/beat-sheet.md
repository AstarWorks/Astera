# Beat sheet (workflow v3, step 1-3)

Team overview: [README.md](README.md). The skill author and every reviewer read this before touching a skill. Founder decision 2026-10-26; process re-confirmed 2026-10-27 after the S1 retrospectives (two independent ones, merged here).

## Why it exists

About 30 rounds of bastion-greatsword S1 failed for the same reason: the reference was read as a **list of poses** ("pose A, pose B"), so nobody described the travel between poses. The direction of rotation was never written down (cut 1 spins counter-clockwise relative to the camera; cut 2 reverses the body and spins clockwise; the founder had to say so), a cut went missing because nobody counted the swings, particles were judged from the right-side camera only (a side camera cannot tell clockwise from counter-clockwise), and a self-graded numeric lint passed 31/31. The beat sheet makes the motion, not the poses, the thing that is written, checked and approved.

## Workflow v3 in one screen

```
1. Observe        the author writes the sheet from the REFERENCE ONLY (this template, no blank cell, every cell
                  marked observed / inferred / unknown). No skill key exists yet.
2. Two checks     (a) reverse read: a fresh reviewer sees ONLY the sheet and re-describes the motion
                  (b) fact check: a second fresh reviewer sees reference + sheet and lists every disagreement
3. Founder gate 1 the sheet + top-down / side / front stick figures on the skill-compare page; approval FREEZES this version
4. Build          one author end to end; each commit goes live on dev (make dev-content / dev-pack / dev-plugins) with the reload verified
5. Capture        side, front, back, TOP-DOWN (mandatory), 45 deg high, eye height, via the real player path; skippedTicks = 0;
                  enemy at the intent distance, caster framing within 0.5x-2x of the reference
5b. Round gate    round note (round-template.md): compare sheet ref | this | previous from the API, trace cast+N, framing,
                  tool fidelity, "weaker than previous: none"; `make round-gate` must PASS before any reviewer
6. Visual review  a fresh reviewer (new every round): whole impression, regression vs previous, then every sheet row ○ / × with evidence
7. Founder gate 2 in game, on dev. Final.
```

Do not start step 4 until step 2 passes and gate 1 is approved. Lint (`make skill-lint`) is only a preflight of 4 invariants (one sword, both hands on the grip, feet on the floor, display budget); it says nothing about whether the skill matches the reference. Priority when things conflict: sword > VFX > particles > posture. The pose library (keypoints, `tools/pose-solve/`) is a tool the author composes from; it is no longer a founder gate.

**Limit: at most 2 rounds per gate.** If a gate is not passed after 2 rounds, stop and escalate to the founder with ONE question (what must be decided, the evidence for each candidate, the recommendation). Never invent a new answer and start round 3.

## Reference frame quality (founder 2026-10-27)

`tools/skill-compare/scripts/prepare.mjs` now extracts the reference at the source's native resolution (capped at 1080p, jpg q2) from a yt-dlp download of up to 1080p. Rotation direction, contact timing and blade detail must be judged from **full-resolution single frames or zoomed crops** of those frames (`tools/skill-compare/cache/<case>/frames/frame_NNNNN.jpg`). Never judge them from downscaled contact sheets or strips alone: a 6-up sheet is about 480 px per frame and hides exactly the detail (blade edge, hand grip, foot contact) the beat sheet is supposed to record. Sheets are fine to find the frame; the cell evidence cites the full-resolution frame number.

## Template

One row per beat. A beat is one continuous motion segment (wind-up, cut 1, turn, cut 2, recovery, ...), not one frame. **No blank cell.** If a cell cannot be filled, look at the reference again (frame by frame, successive frames, not stills). "n/a" is allowed only with a reason ("n/a: blade not moving, held"). **Every cell ends with one status mark:** `[O]` observed (visible in the reference frames; cite the frame or time), `[I]` inferred (3D filled in from cues such as body twist or overlap; name the cue), `[U]` unknown (not decidable from the reference; write what was tried and the candidates). `[U]` is the only allowed hole and is decided by the founder at gate 1. A top-down view is a hypothesis drawn from the reference, never extra evidence from it.

| Column | What goes in it |
|---|---|
| beat | short name and number (`B3 cut 2`) |
| tick range | `a-b` skill ticks (20 TPS) the beat spans, from the reference time and the case's `start_s` |
| swing # | which swing of the skill this beat belongs to (`1`, `2`), or `-` for non-swing beats. Count all swings first (rule 2) |
| blade-tip start -> end (3D) | caster-local `(forward, right, up)` in blocks or in units of the blade length, for start, one or more mid points on the path, and end. Example `(0.3, 1.2, 0.8) -> (1.5, 0.0, 0.2) -> (0.6, -1.1, 0.7)`. Also name the path in words (`arc over the right shoulder, down to the left hip`) |
| rotation direction | **camera:** CW / CCW as seen in the skill-compare camera. **top:** CW / CCW seen from above (world +Y looking down). Both, always; write `none` when the tip does not rotate about the body in that view, and why |
| rotation angle | total angle the tip sweeps about the body/pivot in this beat (degrees), plus the blade's own roll about its long axis if any |
| edge facing | `edge-leading` or `flat`; and which face is visible to the camera (`flat face toward camera`, `edge toward camera`, `back of blade`). Mid-beat changes are written as `edge -> flat` |
| reverses previous beat? | `yes` / `no`. `yes` means the rotation direction (either view) or the body turn is opposite to the previous beat; say which |
| body facing and twist | torso yaw from the caster's forward at the beat start and end (degrees, + = toward caster's left), hip/shoulder twist, lean |
| foot positions | each foot relative to the caster origin `(forward, right)`, planted / lifted / sliding; step or pivot |
| hit (contact) tick | the tick the blade contacts (`t=7`), or `-` |
| travel | how far and in which direction the caster moves in this beat (dash, step, lunge): distance in blocks and caster-local direction, or `none` |
| VFX & particles | what the beat shows: smear/ring shape and which way it travels, particle emitters with their direction of travel, flash. Each effect is tied to the swing it follows |
| sound events | every sound of the beat with its tick and character (`t=6 low whoosh, rising`; `t=7 heavy metal impact + low thump`), or `none` |

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
4. **Both views, every beat.** Camera CW/CCW and top CW/CCW are separate cells because the side camera alone cannot tell them apart.
5. **Reversals are explicit.** Whenever the body turns back or the rotation sense flips between swings, `reverses previous beat?` says `yes` and the body column says by how many degrees.
6. **Trajectory, not poses.** The tip start -> mid -> end and the rotation cells describe the *travel*. A row that only restates the end pose is incomplete.
7. **Particles and VFX belong to a swing.** Write which way each one travels; judging particles from the side camera alone is forbidden (see the review rules in [reviewer.md](reviewer.md)).
8. The sheet is the build target and the review checklist: the author implements it, the reviewer marks its rows. If the build disagrees with the sheet, fix the build.
9. **Observation comes from the reference only.** Write the sheet before any key for the skill exists. Never derive a cell from shipped keys, and never write numbers back into the sheet after a build (that turns the sheet into a description of what was built, and a reverse read of it only proves it is self-consistent). The S1 r3 sheet was derived from shipped keys and was wrong for exactly this reason.
10. **Gate 1 freezes the approved version.** After approval the observation cells are read-only. If the founder, a reviewer or the author finds the sheet wrong, make a new version with a diff that states which reference frame was read again and what changed, repeat both checks and gate 1. Never edit the approved version to match the build; never reuse approval of an old version for a new one.
11. **At most 2 rounds per gate**, then escalate with one question (see the workflow section).

The required columns are: rotation direction from the camera and from above, swing count, edge facing, reversals, contact tick, travel, sound events (plus tip path, angle, body and feet). All of them in every row.

Sheet header (above the table):

```
Case: <id>   Reference: <video id> t=<a>..<b>   Mirrored: yes|no
Swings: N (starts at ticks ...)
Priority: sword > VFX > particles > posture
Notes: cues used for depth, conversions, unknowns
Diagrams: top-down / side / front stick figures (below or attached)
```

Stick diagrams (gate 1): one per swing, three views (top-down, side, front), body as a stick figure, tip path as an arrow with its start/end ticks and the CW/CCW mark. ASCII in the sheet is enough for the reverse check; the founder sees the same on skill-compare. Round notes hold text (max 20 kB) and up to 12 images (see [skill-compare-views.md](skill-compare-views.md)), so a skill's sheet is published with `make skill-round SKILL=<s> FILE=<beat-sheet.md>` and the stick figures can be attached as images.

## Check A: reverse read (step 2a)

Purpose: prove the sheet carries the motion without the author's private context. It does not prove the sheet is true to the reference (Check B does). The checker is a **different AI** (fresh reviewer) that never sees the reference video, frames, case data, the author's notes or briefs. It receives only the sheet text (header, table, diagrams).

Prompt to give the checker, verbatim:

```
You are given a beat sheet for a sword skill, written in caster-local coordinates
(forward, right, up). You have not seen the original animation and must not guess
from names. Using ONLY the sheet:
1. State how many swings there are and at which ticks they start.
2. For every beat, in plain words (2-3 sentences): where the blade tip goes, which
   way it rotates seen from the right side camera (clockwise / counter-clockwise)
   and seen from above, whether the blade slices or slaps, which way the body turns,
   what the feet do, and what the particles do.
3. List every place where the body or the rotation reverses.
4. List every cell that was ambiguous, contradictory, or that you had to assume,
   and which beat boundary has a jump you could not connect.
5. Draw a top-down ASCII path of the tip for each swing.
Do not invent anything the sheet does not say. If the sheet does not let you decide,
say "cannot tell".
```

Diff procedure (the author does it):

| Compare | Mismatch means |
|---|---|
| swing count and start ticks | a swing missing or merged: re-count the reference |
| per beat camera CW/CCW and top CW/CCW | the sheet's vocabulary or the author's reading is wrong: re-observe that beat's successive frames |
| reversal list vs the `reverses previous beat?` column | a reversal is not stated clearly enough |
| top-down ASCII path vs the author's own diagram | the path is under-specified |
| checker's "cannot tell" / ambiguity list | each item is a blank in disguise: fill it from the reference |
| edge-leading / flat, visible face | the face convention is not clear |
| the checker's prose vs the author's intent | any difference in meaning (not wording) is a gap |

Pass: swing count, every direction cell (both views), every reversal and every hit tick agree, and the ambiguity list is empty or contains only items the author re-checked in the reference and resolved in the sheet. Otherwise re-observe, edit the sheet, and run a new checker (a fresh agent each time, never the same one with memory). Record the checker's output and the verdict in the case directory (`beat-sheet-check.md`) so the founder sees it at gate 1.

## Check B: independent reference-vs-sheet fact check (step 2b)

Purpose: prove the sheet is **true**. A second fresh reviewer (not the Check A reader, not the author) receives the reference frames at native fps and the sheet. It first counts the swings and reads each beat's direction from the reference itself, writes that down, and only then compares with the sheet. Output `beat-sheet-factcheck.md`: one line per row and column, agree / disagree with the reference frame or time as evidence, and a status check (is every `[I]` justified, is every `[U]` really undecidable). It also flags any cell that looks derived from implementation rather than from the reference.

Pass: swing count, every direction cell (both views), every reversal, every contact tick, travel and sound event agree, and every disagreement is resolved by re-reading the reference (not by the checker's say-so). Anything that stays disputed becomes `[U]` and goes to the founder.

Both checks must pass before gate 1. Show both records on the page. Each check counts as one of the 2 rounds of that gate when it sends the sheet back.

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

| beat | tick range | swing # | tip start -> end | rotation direction | angle | edge facing | reverses prev? | body | feet | hit | VFX & particles |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B1 wind-up | TBD | - | TBD | camera: TBD; top: TBD | TBD | TBD | no | TBD | TBD | - | TBD |
| B2 cut 1 | TBD | 1 | TBD | **camera: CCW**; top: TBD | TBD | TBD | no | TBD | TBD | TBD | TBD |
| B3 turn | TBD | - | TBD | camera: TBD; top: TBD | TBD | TBD | **yes: body reverses** | TBD | TBD | - | TBD |
| B4 cut 2 | TBD | 2 | TBD | **camera: CW**; top: TBD | TBD | TBD | **yes: body reverses, rotation reverses** | TBD | TBD | TBD | TBD |
| B5 recovery | TBD | - | TBD | TBD | TBD | TBD | TBD | TBD | TBD | - | TBD |

TBD — filled from the approved S1 beat sheet. The two bold cells are the founder-confirmed facts the skeleton must keep: cut 1 rotates counter-clockwise relative to the camera; cut 2 reverses the body and rotates clockwise. The top-down cells for both cuts are mandatory in the real sheet because the camera column cannot carry them.

## Learnings log

(The author appends dated bullets after each skill: `- YYYY-MM-DD <case>: <fact>`.)

- 2026-10-26 seed: created with workflow v3 after the bastion-greatsword S1 failures.
- 2026-10-27 S1 retrospectives: sheet from the reference only; O/I/U marks; travel and sound columns; two checks (reverse read + fact check); gate 1 freezes; 2 rounds per gate; round notes hold up to 12 images.
- 2026-10-27 bastion-greatsword-s2 r2: a rotation direction that looks undecidable on one frame is usually decided by the erosion order of the effect tail across the whole sequence (compare the same effect in an earlier swing) plus which side the effect rises from; write the frame numbers and a confidence in an evidence block. Contact-sheet labels drift when batches are concatenated: re-derive n = base + index per batch before quoting frame numbers.
- 2026-10-27 bastion-greatsword-s2: a second skill of the same weapon reuses the first skill's blade path (re-timed via `tools/pose-solve/author_bastion_s2.py`, imports `author_bastion_s1`) and its ring fx (copied, ids renamed) instead of regenerating sprites; the skill-lint left-hand/feet checks fail on long linear gaps between two very different poses (coil -> crouch), so add the crouch beat 1 tick earlier.
- 2026-10-27 bastion-greatsword-s2 round 2: stepped captures of S2 showed ring 1 about 9 ticks early and no ring-2 / chop fx and no visible rig motion after ~t13, while the dev server log showed three skill attacks 1 s apart (damage steps on time) and the founder saw the skill live as good. Treat the stepped capture's delayed-step timing as unverified until the runtime agent confirms; do not tune fx art from it. A later `SHOT_KEEP=1 TICKS=40` run overwrites `build/vfx-shot/<skill>/<view>` with a 41-frame set: publish first, and check `meta.json` `frames`. Captures do not take the e2e flock (vfx-shot README). The position trace is not recorded; read `data get entity Caster Pos` on a kept stack for net travel.
- 2026-10-27 couture-rapier-s2 gate 1: tick = round((f-start)*2/3) at 29.97 fps; check the tick of every range END against the formula (Check A caught two off-by-one ends). The reference camera pans and punches in, so crop wide or view full frames; narrow crops lose the caster. Facing is read from face/hair/arm over 3+ frames, never one still: my first read of B9 (f893-904) was wrong (she faces screen-left, back to the enemy, same back-first dash as S1) and only the fact-check agent plus a re-look at f896/f900/f903 fixed it. A skill can have 0 body swings and 3 contacts (effect-driven coins): write "Swings: 3 = coins" and say the swing # column is the coin number. Mark a one-frame effect pop (f811) as "no sweep visible" instead of inventing a direction. Audio onsets live in the sibling analysis cache (`tools/skill-compare/cache/<case>/analysis.json`, onset s * 29.97 = f); hit ticks usually have no isolated onset (music floor). make-figures.py: use a collision-avoiding label helper and fixed-position note rows; S1's overview labels overlapped.
