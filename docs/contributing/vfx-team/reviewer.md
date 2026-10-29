# Reviewer knowledge base

Reference knowledge (2026-10-27): the skill has ONE author who builds every piece; this page is an accumulated knowledge base the author consults, not a role with a territory. Process: [README.md](README.md). Historical Learnings entries may name old roles and numeric targets; self-authored quality numbers are never acceptance evidence, tool-measured facts (trace ticks, caster bbox share, revisions) are required ([retro-2026-10-27.md](retro-2026-10-27.md)).

Optional helper agent: `.claude/agents/limbus-reviewer.md` (fresh every round). Team overview: [README.md](README.md).

## Independence rule

The reviewer is read-only on content, code, briefs and case data. It never fixes; it writes marks and diffs. The people who built the skill do not grade it, and a reviewer is a **fresh AI** each round (not the author, not the agent that wrote the independent inventory). The reviewer judges against the **reference video**, the **approved inventory** and the **approved beat sheet** ([beat-sheet.md](beat-sheet.md)), not against the director's briefs or intent file ("intent is an interpretation, not proof").

## Order of judgement (workflow v4 step 6, revised 2026-10-27)

S3 r5 and r6 passed a per-row review while being plainly worse than r4: per-row crops hide the whole picture and no row says "weaker than last round". Couture-rapier S2 then passed every tick row while the reference's three thrown needle blades were one rapier and a sprite in the build: no row asked how many objects there were. The order is therefore fixed, and **the inventory diff comes before everything**:

0. **Count first: objects, from the reference.** Watch the whole reference 3 times at normal speed and list its objects (count, colour, anchor, 3D object / plane / line / particles) before opening the frozen inventory, the in-game structure captures or the capture. Then diff row by row against the build (stills + capture + YAML) and write `Inventory diff: ok | ng - row N: ref X, build Y` as the first line of the report and `reviewer.inventory` in `review.json`. A ng inventory stops the content judgement: the detail rows are not discussed on the wrong structure. An object the reference shows and the frozen inventory lacks is also ng and reopens gate 1. Camera and edit phenomena (white-out, punch-in, close-up, hit-stop, UI marker) are never missing objects.

1. **Whole impression first, from the page.** Open the published compare sheet `ref | this | previous` (`/api/cases/<id>/compare`, shown in the page's 比較 panel; the author's `round.md` links `compare/sheet.png`) and the capture views on skill-compare. Before reading any row, write one verdict: `overall: ok | ng` with one sentence that names what is wrong even when no sheet row covers it (silhouette, weight, size of the main element, a weapon that follows the pitch, anything). This is the slot for everything the sheet did not foresee.
   **Sibling consistency is part of this verdict.** Put the accepted frames of the sibling skills of the same weapon / character next to `this` (the round note's `sibling/sheet.png`, or the sibling case's own sheet on the page) and answer `sibling: ok | ng`: *same art language and at least as refined*, by the checklist below. S3 of the bastion greatsword went ng this way ("S1,S2にくらべてめっちゃチープ"): S2 is layered thin tapered crescent strokes with glow, S3 had drifted into flat opaque comic stamps (fat plates, a black-outlined filled starburst, white blobs) while every row and every coverage number was fine.
2. **Regression second.** Compare `this` with `previous` at the same ticks and camera: `regression: none` or a list of what is weaker (element, tick, view). Anything weaker is a × of the round regardless of the row marks.
3. **Rows third.** Then every row of the frozen sheet, ○ or ×, as below.
4. **Author's notes last.** Read the author's `round.md` only after 1-3 are written; it may change a row mark (a fact you missed), never the overall or regression verdicts.

Record 1-2 in `review.json` as `reviewer.overall: {v, reason}`, `reviewer.sibling: {v, reason}` and `reviewer.regression: "none" | [..]` (next to the per-row marks); the manager's round-note review on skill-compare reads all three.

**Art language** (the checklist behind `sibling: ok | ng`; the author writes the same six lines in the round note "Sibling" section):

| Line | Question | Cheap looks like | Refined looks like |
|---|---|---|---|
| `stroke/fill` | Is the element a stroke (a drawn path with thickness) or a filled shape? | Fat opaque plates, stamps, filled starbursts, blobs | Thin strokes whose shape is the motion path |
| `bands` | How many layered bands build one stroke, and in what order? | One flat colour, or a flat colour plus a black line | Dark rim, saturated mid, white core, glow accent, each a separate band |
| `taper` | Do strokes thin out at both ends? | Constant width, square or round caps | Crescent / sliver taper to a point at both ends |
| `core line` | Is there one bright, thin core line per stroke? | Whole stroke equally bright | A single core line brighter than the band around it |
| `glow` | Is brightness carried by a soft additive glow outside the core? | Brightness by making the fill white / bigger | Glow wider than the core, soft edge, lower alpha |
| `outline` | Black / dark outlines? | Black outline around a filled shape (comic stamp) | None, or a dark rim band that belongs to the stroke |

"Same" means the sibling and this round answer each line the same way; "at least as refined" means none of the six is worse than the sibling's. **Coverage is not quality**: the compare API's `fx_pct` / brightness are a floor and a regression signal, never a target; a round that gained coverage with opaque flat fills is a regression even when `fx_pct` went up, and "bigger / more opaque" is never a reason for ○.

**Right medium per element** (founder, S3 rebuild, corrected the same day: 「3Dを主体にしろってわけじゃない。2Dを使うのに適している場面(カスタム「パーティクル」と斬撃)だよねって話。斬撃自体を2DTextDisplayで作るのはいい」「TextDisplayにするならプレイヤーに常に向く奴はあんまり作っちゃいけない、大きすぎるとチープ具合がバレる。光の柱が特にひどい」). 2D TextDisplay sprites are the **right** medium for slash arcs on the real swing plane (fixed billboard, oriented to the blade path) and for small custom particles; they are the **wrong** medium for pillars / beams, plates, filled starbursts, big flat fills, screen overlays and large `billboard: center` stamps, which are particles plus thin solids (BlockDisplay) or ItemDisplay models. In the **side view** at every key tick ask "does this read as a sticker?" about those misuses only, never about a slash arc: a pillar or ring that is one billboard, a plate that keeps its shape 90 degrees off its plane, a stamp that turns to face the camera. Any yes is `overall: ng` with that sentence. The round note's "Medium" section carries a per-element table (`| element | medium | reason |`): read the reasons of every `center-billboard sprite` row and every sprite over the 2-block cap, and reject a reason that does not hold on the frames (a "contact flash" that is a 7-block starburst; a "slash arc" that is a plate). The gate counts (center-billboard elements, sizes); you judge whether the chosen medium looks right.

**Plays well, with equal weight** (founder: 「再現に夢中になりすぎて、普通に武器の挙動として弱いとかっていうのは避けたい」). The goal is a respectful remake for Minecraft PvP / PvE, not a replica: the reference sets the identity, play wins. Judge the round note's "Gameplay" rows with the same weight as the visual rows: `startup:` (first hit within ~8-10 ticks for a normal skill, or a reason), `reach:` in blocks (narrow cones and tiny boxes are ×), `projectile:` (Minecraft-sensible 3D flight: speed, arc, 3D hit detection, range; a projectile copying the reference's frame timing or 2D path is ×), `dash:` usefulness, `pvp:` / `pve:`. "Looks right but plays weak" is `overall: ng` with the gameplay sentence. A deviation from the reference that is listed under `deviation from reference:` and makes the skill play better is never a ×.

**No camera-tuned fakes** (couture-rapier-s2 r6, 2026-10-27). A round can match every frame at ticks 9 / 41 / 72 from the compare camera and still be a test rig: the thrown shears rode caster-local keyframes to z 5.0 because the dummy stood at `DISTANCE 5.4`, the "stuck" shears sat at caster-local (0, 1.9, 3.4) for t46-58 whether or not anything was hit, and the hit effects were cast-time `play_vfx` steps at `delay_ticks` 8 / 38 / 72 with a cone targeter, decoupled from the `damage` at 9 / 41 and from `dash_strike.on_hit` (c86dbd5fa even moved hit3 out of `on_hit` into a cast-time step). Judge the Gameplay rows `miss case:` and `moving target:` as a row of their own: anything that appears at an empty spot when nothing was hit, or that lands at the capture distance instead of on the enemy, is `overall: ng` ("no camera-tuned fakes") regardless of how the compare frames look.

**Particles are designed, not copied** (founder: 「パーティクルは多分Limbusのほうに似せずに、LLM側のセンスで…Limbus参考なしでコンセプトから自ら考えたほうが良いものができる。あとはアニメーションや武器の先端等に連動させて行う程度で」). The reference governs pose, timing, camera and the large forms only. Judge particles on two things: **do they look good in Minecraft** (type, count, speed, spread, lifetime, colour chosen for the game, not for resemblance) and **do they read as motion-linked** (they leave the blade tip / edge along the rig path, burst from the contact point, trail the dash vector, kick up at the landing). Never mark a particle row × for not resembling the Limbus frames; mark it × when it floats free of the animation, spawns at a fixed offset while the weapon moves, or contradicts the round note's `particle concept:` lines (intent / anchor / params per beat).

**Native crops for edge quality.** Reference frames are lossless PNG at native size (<= 1080p); the default sheet may be downscaled. Edge quality (stair-stepping, blur, rim colour, trail width, sprite crispness) is judged ONLY on native crops: `GET /api/cases/<id>/sheet.png?ticks=..&views=ref,compare&zoom=native&crop=x0,y0,x1,y1` (fractions of the frame; `crop=auto` frames caster + fx, but is often the whole frame on the ref, so give an explicit crop). Native cells are never resampled; a sheet over 16000 px is refused. Read `sources[].nativeWidth/nativeHeight` first: a capture smaller than the ref (side/top 640x360) cannot support an edge verdict, so request a higher `RES=` capture instead of marking. A downscaled sheet may support shape, count and direction marks, never an edge or sharpness mark.

**Review surface.** Judge on the compare API sheet and the published captures. Hand-built crops (PIL strips, per-row cut-outs) are additional evidence for a ×, never the basis of a ○, and never replace step 1.

## Judging: one mark per beat-sheet row

For every row of the approved beat sheet write **○ or ×** and a one-line reason that cites what you saw (reference time, in-game tick, image path). Rules:

- **No free-form verdicts in rows.** "Looks good", "feels heavy", "roughly OK" are not marks. A row has no half marks and no skipped rows; a row you could not judge is × with the reason "not visible in views X" and a capture request; a row whose effect the capture cannot show (round note "Tool fidelity": `not shown`) is marked `gate 2` and not judged.
- **No self-authored numbers as evidence.** Lint scores, pixel errors, percentages or angle measurements chosen by the author are not reasons. Say what is visible: "cut 1 turns clockwise from above; the sheet says counter-clockwise". Tool facts are different and required: a contact-tick row is judged against the trace's `cast+N` quoted in the round note, never against a frame label or your own count of frames.
- **Paired images.** Judge on ref|ours pairs of the same beat, same moment, equal scale (`make skill-pairs`, `make skill-brief`), then step frames before and after (direction comes from successive frames, never one still). Build pairs for every required view.
- **Required views: side, front, back, top-down, 45 deg high, player eye height.** The **top-down view is mandatory**: it is the only view that shows rotation direction (clockwise vs counter-clockwise); the side camera cannot tell them apart. No top-down capture means no verdict on any rotation, reversal, or particle-travel row: reject the capture and request it. Particles are judged from the top-down and at least one more view, never from the right-side camera alone.
- **Check the sheet columns, row by row:** swing count (count the swings in the capture first and compare), tip path, rotation direction in both views, edge facing, reversal, body twist, feet, hit tick, VFX/particle direction.
- **Pose is a reviewed row.** The rig pose of each beat (body facing / twist, lean, weapon hold, feet) must read in **both** `compare` and `side`; a pose that reads in one view only, or a caster that stands upright through a thrust or slam, is × on that beat's body row with the tick and both image paths.
- A × produces a diff (format below) labelled with its owning role and the row id. All rows ○ is necessary for done; the founder's in-game approval (gate 2) is the finish.

## Acceptance rows (source: [limbus-skill-reproduction.md](../limbus-skill-reproduction.md) section 6)

These stay as the vocabulary for what a sheet row is judged on; the per-row ○/× above is the mark.

| Row | Pass condition |
|---|---|
| Target skill | video segment and binding / implementation id match |
| Motion | move direction, swing direction, contact order, count, return match the beat sheet |
| Shape | main shape, pose, size, placement, colour targets met; no board facing the wrong way, occlusion, floating or missing piece |
| Timing | beats align from a common origin; 20 TPS rounding is up to 25 ms; real-client lag measured separately; if unknown, timing does not pass |
| Hit | display and damage agree; expected count of hits with the weapon equipped; hit effects, stuck objects and the thrown weapon are bound to the real hit / entity (`on_hit:`, `hit_entity:`, target anchors), never to a tick offset |
| No camera-tuned fakes | the effect stays correct in `side` and `top`, in the round note's miss case (nothing hit: no hit flash, no stuck object, no shadow at an empty spot) and with the target off-axis or at another distance; a thrown weapon flies to the enemy, not to where the capture dummy stood (couture-rapier-s2: 「テストのための実装感がぬぐえない」) |
| Playability | caster and opponent views, telegraph, danger range, cleanup |
| Budget | simultaneous displays and lifetimes within limits; exceptions justified |

Add for this team: a sound exists for every beat; only one weapon is **held** during the skill unless the inventory says otherwise, and every other visible object is an inventory row built in its own medium (a stuck needle is a model on the enemy, never a sprite); the model is multi-part, multi-material, not a flat slab; sprites are smooth at close-up.

Status vocabulary for the report: 未分析 / 実装済・未検証 / 検証不合格 / 再現合格 / 独自設計・動作確認済. "Captured" or "iteration limit reached" is never a pass, and a reviewer pass alone is never done: the founder must also approve in game. A skill with no observed reference never gets an original-match verdict.

## How to review (verified commands)

1. `make skill-brief CASE=<id> [VIEW=compare] [AT="t:label"]` -> `build/brief/<case>/sheet-NN.png` + `manifest.json`. Rows: start, each hit, end; columns: reference | intent | in-game, equal scale. **Read `warnings` first** (reference/content version mismatch, FOV not recorded, tick out of range, intent not generated); a warned image is not evidence.
2. `make skill-pairs CASE=<id> AT="19.7:hit1 20.9:hit2"` -> `build/pairs/<id>.png` (times are absolute video seconds).
3. skill-compare page: `http://127.0.0.1:8097` (public: https://skill-compare.astarworks.com, Cloudflare Access). Screenshots: `bun tools/skill-compare/scripts/shot.mjs --ms 1100 --out build/shot.png [--focus video|intent|mc] [--width 600]`. Keys: Shift+Left/Right = 1 tick, Left/Right = 1 video frame, `[`/`]` jump between hits. A local HTTP 200 or an Access login redirect does not prove the tunnel; the README documents the recovery check.
4. Look before and after every contact, at normal speed and frame by frame.
5. Convert to gray (`ffmpeg -vf format=gray`) to test that the main element reads without colour.

## Comparing rounds: use the comparison API, not hand-made crops

Reviewers and directors MUST compare with `GET /api/cases/<id>/compare?ticks=..&views=ref,side,top,preview-side&rounds=current,prev` and
`.../sheet.png` (same params; `http://127.0.0.1:8097`), never with hand-cropped or PIL contact sheets. The API aligns reference and in-game frames by the
case's tick mapping, labels revision/sha/age/failure on every row, and returns fx coverage, brightness and regression flags (and caster travel /
final gap when the capture has `trace.log`). Quote its numbers and sheet URL in the report; a `regression: true` is a finding to route, not to ignore.
See [skill-compare README](../../../tools/skill-compare/README.md) "Comparison API".

## Capture validity

- **Framing parity**: compare API `framing[].ratioToRef` (caster height as % of the frame: analytic from the capture camera + trace vs the annotated ref) must be within 0.8x-1.25x (round note "Framing"). A far wide-angle side view with the caster at 1/40 of the frame, or a preview 2x closer than the client, is not review-valid: reject and request a reframed capture; do not mark rows on it.
- **Scene parity**: the enemy stands at the case intent distance (`DISTANCE=` in the round note "Capture scene"); a tool-default scene changes dash travel and is rejected for travel rows.
- **Tool fidelity**: the round note lists every mechanic type as `shown` / `not shown`; judge only what is shown. Spectator captures render no `send_title` / `screen_flash`; skill-preview is never evidence.
- Provenance: `meta.json` records branch, implementation commit, capturedAt, FOV, fovMode, resolution. Round-2 captures need matching `referenceRevision` and `contentRevision`.
- `captureQuality.maxRepeatedFrames >= 15` needs a visual check; confirmed stalls are marked `captureQuality.invalid: true` and excluded. Machine load causes 2-6 tick (sometimes ~20) lag. **Reject frozen or magenta-cube captures** and ask for a recapture; do not fail the skill for a bad capture.
- A `--force` capture says nothing about cooldown, resources or key bindings.
- HP loss does not count hits (random 10% x2 crits); count contacts on frames or events.
- Headless time is wall-clock, not server ticks; do not accept per-hit offset tuning that hides sync drift.
- Captures run one at a time host-wide (vfx-shot slot lock) and are torn down on exit unless `SHOT_KEEP=1`; never use the e2e flock for them. Do not start captures yourself unless the director asks. Read `meta.json` `stack.workingTreeInSync`, `stack.contentVerifiedInContainer` and `tickStamp` (`skippedTicks`, `firstFxTickAfterCast`) before trusting a frame-to-tick claim.
- Runtime facts that explain "missing" effects: font sprites render only up to 256 px (512 fails); a horizontal `fixed`-billboard TextDisplay does not render in the real client; hit VFX must peak on the damage tick; `hide_held_item` (on main) hides the held item, so two weapons on any tick is a ×.

## Rejected looks (fail on sight, label with owner)

| Look | Owner |
|---|---|
| crescents built from uniform boxes (hoops) | vfx / animator (trail) |
| fire made of squares (cubes) | vfx |
| poles for jets | vfx |
| a static ring standing like a wall | vfx / director (placement) |
| separate floating sprites instead of one continuous sweep | animator (trail samples) / vfx |
| pixel-art staircase at close-up | vfx (sprite resolution) |
| black `#`-like smoke glyphs | vfx |
| blocky single-colour low-poly weapon | modeler |
| blade orientation off in hand | modeler (display transform) / animator (rig rest) |
| weak swing: no anticipation, no apex hold, edge not leading, linear timing | animator |
| block-only effects with almost no particles | vfx |
| no sound or one reused sound | sound |

## Diff format (each ×)

```
Row: <beat-sheet row id>
[owner: model | animation | texture | particles | sound | integration(director)]
Reference fact: <video time / frame, what it shows>
Game now: <tick / image path, what it shows>
Cause: <content | common runtime | capture sync | unsupported expression | missing reference> (with evidence)
Target: <measurable change>
Priority: blocker | major | minor
```

"Engine limit" is acceptable only with evidence of what was tried; insufficient pose / anchor adjustment is not a limit. Distinguish causes: content, common runtime, capture sync, unsupported expression, missing reference.

Further checks from [failure-patterns.md](../../reference/vfx/failure-patterns.md): same `hit_stop` on every hit, single hit covering 12-18%+ of the view (secondary), lingering white `screen_flash`, particles piled on mid-combo hits, danger telegraph colour same as hit colour.

## Craft rows (P-numbers: [pixel-vfx-craft.md](../../reference/vfx/pixel-vfx-craft.md))

Add these ○/× rows to the sheet. Each × names the owner and the P-number.

| × condition | P | Owner |
|---|---|---|
| blurred glow or soft gradient where hard colour bands should be | P1 | vfx / pixel-artist |
| white blob: most of the frame at the top band, no dark/mid layers | P5 | vfx / director |
| shrink-out ending: the last frames are a smaller copy, no break-up or cool-down | P3 | vfx / director |
| impact tick mismatch: first-flash tick differs from `hit_stop` / `camera_shake` / damage tick (server trace) | P4 | director |
| shape does not read at `vfx_intensity: minimal` (core lane only) | P2 | director / vfx |
| texel density mismatch: a pixel-class sprite visibly coarser or finer than skin and block texels (about 8-32 per block is the band) | P6 | pixel-artist / vfx |
| semi-transparent fringe, stray pixels or holes on a pixel-class sprite (zoom 8x) | P10 | pixel-artist |
| ground ring not flat or not on the floor line; loop with a visible jump | P8 / P9 | vfx |

Smooth 128-256 px hero sprites are exempt from the P6 and fringe rows (they are judged by the existing "pixel-art staircase" row).

## Learnings log

(The director appends the reviewer's learnings after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
- 2026-10-24 bastion-greatsword-s1 r22: build ref|compare|side|opponent|caster rows per tick with ffmpeg (fps=20 from case.start_s; compare frame = tick+6). Wind-up fidelity has to be checked against ref t0-5, not only from the hand-over onward; a correct hand-over can hide a missing anticipation pose.
- 2026-10-24 bastion-greatsword-s1 r22: the hand-over between the held item and the rig can change blade luminance (silver -> near-black). Compare brightness across the hand-over frame as well as the count of blades.
- 2026-10-24 bastion-greatsword-s1 r22: ring band stair-stepping shows at compare close-up f16-18 at native resolution. Zoom 2x with point filter on the band edge each round.
- 2026-10-24 bastion-greatsword-s1 r25: edge-on rim material can shift hue, not just luma (compare f31: RGB 83/76/91, violet). Measure per-channel medians on every edge-on frame (coil and hit-2 pull-back), not only at the hand-overs.
- 2026-10-24 bastion-greatsword-s1 r25: after the contact tick moves, check that the impact VFX lands inside the compare close-up on the tint frame. A ring that trails the blade can be correct in the side view and still show nothing at contact in compare.
- 2026-10-24 bastion-greatsword-s1 r25: the band tile seam shows only at 2x, as a 1 px dotted line. Look for it in the same zoom used for the staircase check.
- 2026-10-27 bastion-greatsword-s3 r3-r6 (retro): a per-row review on hand-made crops gave ○ to r5/r6 while r4 was visibly stronger (thrust streak, ring, pillar); the reviewer's eye-count of frames also "confirmed" a 1-tick-early contact that the trace later refuted. Whole impression and regression come before rows; contact ticks come from `cast+N` in the round note.
- 2026-10-27 bastion-greatsword-s3 r4: the farm `side` view (FOV 90, enemy at DISTANCE 4) shows the caster at ~1/40 of the frame against a close cinematic reference; nothing judged on it about size or weight held. Check the framing numbers before any row.
