# Reviewer knowledge base

Reference knowledge (2026-10-27): the skill has ONE author who builds every piece; this page is an accumulated knowledge base the author consults, not a role with a territory. Process: [README.md](README.md). Historical Learnings entries may name old roles and numeric targets; numbers are never acceptance evidence.

Optional helper agent: `.claude/agents/limbus-reviewer.md` (fresh every round). Team overview: [README.md](README.md).

## Independence rule

The reviewer is read-only on content, code, briefs and case data. It never fixes; it writes marks and diffs. The people who built the skill do not grade it, and a reviewer is a **fresh AI** each round (not the author, not the reverse-check agent). The reviewer judges against the **reference video** and the **approved beat sheet** ([beat-sheet.md](beat-sheet.md)), not against the director's briefs or intent file ("intent is an interpretation, not proof").

## Judging: one mark per beat-sheet row (workflow v3 step 6)

For every row of the approved beat sheet write **○ or ×** and a one-line reason that cites what you saw (reference time, in-game tick, image path). Rules:

- **No free-form verdicts.** "Looks good", "feels heavy", "roughly OK" are not marks. A row has no half marks and no skipped rows; a row you could not judge is × with the reason "not visible in views X" and a capture request.
- **No numbers as evidence.** Do not report lint scores, pixel errors, percentages or angle measurements as the reason. Say what is visible: "cut 1 turns clockwise from above; the sheet says counter-clockwise". Numeric lint is the author's preflight, never a reason to mark ○.
- **Paired images.** Judge on ref|ours pairs of the same beat, same moment, equal scale (`make skill-pairs`, `make skill-brief`), then step frames before and after (direction comes from successive frames, never one still). Build pairs for every required view.
- **Required views: side, front, back, top-down, 45 deg high, player eye height.** The **top-down view is mandatory**: it is the only view that shows rotation direction (clockwise vs counter-clockwise); the side camera cannot tell them apart. No top-down capture means no verdict on any rotation, reversal, or particle-travel row: reject the capture and request it. Particles are judged from the top-down and at least one more view, never from the right-side camera alone.
- **Check the sheet columns, row by row:** swing count (count the swings in the capture first and compare), tip path, rotation direction in both views, edge facing, reversal, body twist, feet, hit tick, VFX/particle direction.
- A × produces a diff (format below) labelled with its owning role and the row id. All rows ○ is necessary for done; the founder's in-game approval (gate 2) is the finish.

## Acceptance rows (source: [limbus-skill-reproduction.md](../limbus-skill-reproduction.md) section 6)

These stay as the vocabulary for what a sheet row is judged on; the per-row ○/× above is the mark.

| Row | Pass condition |
|---|---|
| Target skill | video segment and binding / implementation id match |
| Motion | move direction, swing direction, contact order, count, return match the beat sheet |
| Shape | main shape, pose, size, placement, colour targets met; no board facing the wrong way, occlusion, floating or missing piece |
| Timing | beats align from a common origin; 20 TPS rounding is up to 25 ms; real-client lag measured separately; if unknown, timing does not pass |
| Hit | display and damage agree; expected count of hits with the weapon equipped |
| Playability | caster and opponent views, telegraph, danger range, cleanup |
| Budget | simultaneous displays and lifetimes within limits; exceptions justified |

Add for this team: a sound exists for every beat; only one weapon is visible during the skill; the model is multi-part, multi-material, not a flat slab; sprites are smooth at close-up.

Status vocabulary for the report: 未分析 / 実装済・未検証 / 検証不合格 / 再現合格 / 独自設計・動作確認済. "Captured" or "iteration limit reached" is never a pass, and a reviewer pass alone is never done: the founder must also approve in game. A skill with no observed reference never gets an original-match verdict.

## How to review (verified commands)

1. `make skill-brief CASE=<id> [VIEW=compare] [AT="t:label"]` -> `build/brief/<case>/sheet-NN.png` + `manifest.json`. Rows: start, each hit, end; columns: reference | intent | in-game, equal scale. **Read `warnings` first** (reference/content version mismatch, FOV not recorded, tick out of range, intent not generated); a warned image is not evidence.
2. `make skill-pairs CASE=<id> AT="19.7:hit1 20.9:hit2"` -> `build/pairs/<id>.png` (times are absolute video seconds).
3. skill-compare page: `http://127.0.0.1:8097` (public: https://skill-compare.astarworks.com, Cloudflare Access). Screenshots: `bun tools/skill-compare/scripts/shot.mjs --ms 1100 --out build/shot.png [--focus video|intent|mc] [--width 600]`. Keys: Shift+Left/Right = 1 tick, Left/Right = 1 video frame, `[`/`]` jump between hits. A local HTTP 200 or an Access login redirect does not prove the tunnel; the README documents the recovery check.
4. Look before and after every contact, at normal speed and frame by frame.
5. Convert to gray (`ffmpeg -vf format=gray`) to test that the main element reads without colour.

## Capture validity

- Provenance: `meta.json` records branch, implementation commit, capturedAt, FOV, fovMode, resolution. Round-2 captures need matching `referenceRevision` and `contentRevision`.
- `captureQuality.maxRepeatedFrames >= 15` needs a visual check; confirmed stalls are marked `captureQuality.invalid: true` and excluded. Machine load causes 2-6 tick (sometimes ~20) lag. **Reject frozen or magenta-cube captures** and ask for a recapture; do not fail the skill for a bad capture.
- A `--force` capture says nothing about cooldown, resources or key bindings.
- HP loss does not count hits (random 10% x2 crits); count contacts on frames or events.
- Headless time is wall-clock, not server ticks; do not accept per-hit offset tuning that hides sync drift.
- Captures run one at a time host-wide (vfx-shot slot lock) and are torn down on exit unless `SHOT_KEEP=1`; never use the e2e flock for them. Do not start captures yourself unless the director asks. Read `meta.json` `stack.workingTreeInSync`, `stack.contentVerifiedInContainer` and `tickStamp` (`skippedTicks`, `firstFxTickAfterCast`) before trusting a frame-to-tick claim.
- Runtime facts that explain "missing" effects: font sprites render only up to 256 px (512 fails); a horizontal `fixed`-billboard TextDisplay does not render in the real client; hit VFX must peak on the damage tick; `hide_held_item` is only on `wp/pilot-bastion-s1`/ee976100, not main (two weapons visible on other branches is expected until merged).

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

## Learnings log

(The director appends the reviewer's learnings after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
- 2026-10-24 bastion-greatsword-s1 r22: build ref|compare|side|opponent|caster rows per tick with ffmpeg (fps=20 from case.start_s; compare frame = tick+6). Wind-up fidelity has to be checked against ref t0-5, not only from the hand-over onward; a correct hand-over can hide a missing anticipation pose.
- 2026-10-24 bastion-greatsword-s1 r22: the hand-over between the held item and the rig can change blade luminance (silver -> near-black). Compare brightness across the hand-over frame as well as the count of blades.
- 2026-10-24 bastion-greatsword-s1 r22: ring band stair-stepping shows at compare close-up f16-18 at native resolution. Zoom 2x with point filter on the band edge each round.
- 2026-10-24 bastion-greatsword-s1 r25: edge-on rim material can shift hue, not just luma (compare f31: RGB 83/76/91, violet). Measure per-channel medians on every edge-on frame (coil and hit-2 pull-back), not only at the hand-overs.
- 2026-10-24 bastion-greatsword-s1 r25: after the contact tick moves, check that the impact VFX lands inside the compare close-up on the tint frame. A ring that trails the blade can be correct in the side view and still show nothing at contact in compare.
- 2026-10-24 bastion-greatsword-s1 r25: the band tile seam shows only at 2x, as a 1 px dotted line. Look for it in the same zoom used for the staircase check.
