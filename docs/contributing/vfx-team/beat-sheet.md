# Beat sheet (workflow v3, step 1-3)

Team overview: [README.md](README.md). Every author (director, animator, vfx, particles) and the reviewer reads this before touching a skill. Founder decision 2026-10-26.

## Why it exists

About 30 rounds of bastion-greatsword S1 failed for the same reason: the reference was read as a **list of poses** ("pose A, pose B"), so nobody described the travel between poses. The direction of rotation was never written down (cut 1 spins counter-clockwise relative to the camera; cut 2 reverses the body and spins clockwise; the founder had to say so), a cut went missing because nobody counted the swings, particles were judged from the right-side camera only (a side camera cannot tell clockwise from counter-clockwise), and a self-graded numeric lint passed 31/31. The beat sheet makes the motion, not the poses, the thing that is written, checked and approved.

## Workflow v3 in one screen

```
1. Observe       author writes the beat sheet from the reference (this template, no blank cells)
2. Reverse check a different AI sees ONLY the sheet and re-describes the motion; diff it; a gap = re-observe
3. Founder gate 1  the beat sheet: text + top-down / side / front stick diagram on skill-compare
4. Build         one author end to end, composing from the pose library; judged against the sheet's trajectory
5. Capture       side, front, back, TOP-DOWN (mandatory), 45 deg high, player eye height via the real player path
6. Visual review a fresh AI reviewer marks every sheet row ○ / × with a reason on paired ref|ours images
7. Founder gate 2  in game
```

Do not start step 4 until step 2 passes. Lint (`make skill-lint`) is only a preflight: one sword, both hands on the grip, feet on the floor, display budget. Priority when things conflict: sword > VFX > particles > posture. The pose library (keypoints, `tools/pose-solve/`) is a tool the author composes from; it is no longer a founder gate.

## Template

One row per beat. A beat is one continuous motion segment (wind-up, cut 1, turn, cut 2, recovery, ...), not one frame. **No blank cell.** If a cell cannot be filled, look at the reference again (frame by frame, successive frames, not stills). "n/a" is allowed only with a reason ("n/a: blade not moving, held").

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
| hit tick | the tick the blade contacts (`t=7`), or `-` |
| VFX & particles | what the beat shows: smear/ring shape and which way it travels, particle emitters with their direction of travel, flash, sound cue. Each effect is tied to the swing it follows |

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

1. **No blank cells.** Look again instead of guessing; if the reference truly does not show it, write `unknown: <what was tried>` and flag it for the founder at gate 1. That is the only allowed hole.
2. **Count the swings first.** Before any row, step the whole reference and write `Swings: N` at the top with the tick where each one starts. A sheet whose swing count is not stated is rejected. A missing cut is a gate-1 defect, not a polish issue.
3. **Direction from successive frames.** For every smear frame infer the direction from the frames before and after it, never from one still. Write the evidence (`head at frame 41 is ahead of head at frame 40`).
4. **Both views, every beat.** Camera CW/CCW and top CW/CCW are separate cells because the side camera alone cannot tell them apart.
5. **Reversals are explicit.** Whenever the body turns back or the rotation sense flips between swings, `reverses previous beat?` says `yes` and the body column says by how many degrees.
6. **Trajectory, not poses.** The tip start -> mid -> end and the rotation cells describe the *travel*. A row that only restates the end pose is incomplete.
7. **Particles and VFX belong to a swing.** Write which way each one travels; judging particles from the side camera alone is forbidden (see the review rules in [reviewer.md](reviewer.md)).
8. The sheet is the build target and the review checklist: authors implement it, the reviewer marks its rows. If the build disagrees with the sheet, fix the build; if the sheet was wrong, fix the sheet and repeat the reverse check.

Sheet header (above the table):

```
Case: <id>   Reference: <video id> t=<a>..<b>   Mirrored: yes|no
Swings: N (starts at ticks ...)
Notes: cues used for depth, conversions, unknowns
Diagrams: top-down / side / front stick figures (below or attached)
```

Stick diagrams (gate 1): one per swing, three views (top-down, side, front), body as a stick figure, tip path as an arrow with its start/end ticks and the CW/CCW mark. ASCII in the sheet is enough for the reverse check; the founder sees the same on skill-compare. Round notes are plain text today (max 20 kB, no images, see [skill-compare-views.md](skill-compare-views.md)), so a skill's sheet is published with `make skill-round SKILL=<s> FILE=<beat-sheet.md>` and the diagrams stay ASCII until image support lands.

## Reverse-check protocol (step 2)

Purpose: prove the sheet carries the motion without the author's private context. The checker is a **different AI** (fresh subagent) that never sees the reference video, frames, case data, the author's notes or briefs. It receives only the sheet text (header, table, diagrams).

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

Diff procedure (the author does it, or the director does it for a specialist's sheet):

| Compare | Mismatch means |
|---|---|
| swing count and start ticks | a swing missing or merged: re-count the reference |
| per beat camera CW/CCW and top CW/CCW | the sheet's vocabulary or the author's reading is wrong: re-observe that beat's successive frames |
| reversal list vs the `reverses previous beat?` column | a reversal is not stated clearly enough |
| top-down ASCII path vs the author's own diagram | the path is under-specified |
| checker's "cannot tell" / ambiguity list | each item is a blank in disguise: fill it from the reference |
| edge-leading / flat, visible face | the face convention is not clear |
| the checker's prose vs the author's intent | any difference in meaning (not wording) is a gap |

Pass: swing count, every direction cell (both views), every reversal and every hit tick agree, and the ambiguity list is empty or contains only items the author re-checked in the reference and resolved in the sheet. Otherwise re-observe, edit the sheet, and run a new checker (a fresh agent each time, never the same one with memory). Record the checker's output and the verdict in the case directory (`beat-sheet-check.md`) so the founder sees it at gate 1. Do not start building before this passes.

## Worked example: bastion-greatsword S1

The sheet for this skill is at `tools/skill-compare/data/<bastion-s1-case>/beat-sheet.md`; below is only the skeleton that shows the shape of a correct reading. Cells marked TBD are filled from the approved S1 beat sheet.

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

(The director appends dated bullets after each skill: `- YYYY-MM-DD <case>: <fact>`.)

- 2026-10-26 seed: created with workflow v3 after the bastion-greatsword S1 failures.
