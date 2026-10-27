# Round note template (mandatory sections, checked by `make round-gate`)

Every build round (process step 4-7 in [README.md](README.md)) ends with `tools/skill-compare/data/<case>/round.md` (or the file passed as `FILE=`) published by `make skill-round`. The sections below are **required**; `make round-gate SKILL=<skill> [FILE=<round.md>]` fails the round when one is missing or empty, and the manager does not report a round that fails. Keep each section short: facts, paths, numbers from tools; no reasoning diary. Images go beside the notes file (max 12).

```markdown
# <case> round rN  (sha <dev sha7>, previous round rM sha <sha7>)

## Compare
![ref | this | previous](compare/sheet.png)
source: /api/cases/<case>/compare?ticks=<t1,t2,t3>&views=ref,<view>&rounds=current,prev   (compare.json = this JSON, sheet.png = /sheet.png with the same params)
this: <sources[].revision of current>  previous: <sources[].revision of prev>  view: <view>  regression: false

## Trace
play bastion-s3-fx-ring cast+6 | spawn ... cast+10
play bastion-s3-fx-thrust cast+36 | first spawn cast+40
... (one line per vfx_id of the skill, from `[vfx-trace]` or compare.json `sources[].trace.fxSpawnTicks`)
travel: caster z 300.5 -> 305.9 over cast+38..40 (5.4 blocks, from trace / `casterTravelBlocks`)   # when the skill moves the caster

## Capture scene
DISTANCE=7.2 (case intent enemy x)  FOV=45 (compare: intent camera)  skippedTicks=0  opponent alive  sha = dev

## Framing
caster height: ref 26% | this 25% | previous 24%   (compare API framing[].casterHeightPct; ratioToRef 0.96)

## Tool fidelity
screen_flash: not shown by the spectator capture (title overlay) -> judged in game at gate 2
camera_shake: not shown -> gate 2
particles: shown (side t40, top t40)
sprites: shown (side t10)
sound: not listened

## Regression (vs previous round rM)
weaker than previous: none
stronger / changed: thrust streak longer (t40), pillar top opened (t66)      # never "fx_pct up": coverage is not quality

## Sibling (same weapon / character, best accepted frames)
sibling: bastion-greatsword-s2, bastion-greatsword-s1
![s2 accepted t22/t30/t38 | s1 accepted t12/t20](sibling/sheet.png)
source: /api/cases/bastion-greatsword-s2/compare?ticks=22,30,38&views=side&rounds=<accepted revision>   (sheet.png = /sheet.png, same params)
stroke/fill: thin tapered crescent strokes, like s2 (no filled plates)
bands: 3 layered bands (dark rim, white core, glow), like s2
taper: both ends taper to a point, like s2
core line: one bright core line per stroke, like s2
glow: soft additive glow outside the core, like s2
outline: no black outline (s2 has none)
art language: same

## Medium (3D / particle first)
main body: particles (sweep_attack + dust along the arc, 3 columns) + Display entities (blade trail quads along the real swing path)
sprites: accent only (hit flash t40, 1 quad, 4 ticks)
overlay: none            # or: screen_flash t40 - the ref whites out the frame for 2 frames (ref 0:12.4)
sticker check: no        # side view at the key ticks: no element reads as a flat pasted sprite
pose: reads in compare and side (t10 wind-up, t40 thrust, t63 slam)

## Changed
- <file>: <one line>

## One point to look at
<the single thing the founder should check first, with tick and view>
```

Rules for the sections:

- **Compare**: the sheet comes from the compare API (`GET /api/cases/<id>/compare?ticks=..&views=ref,<view>&rounds=current,prev` saved as `compare/compare.json`, `GET .../sheet.png?<same>` saved as `compare/sheet.png`, both beside the notes; see [tools/skill-compare/README.md](../../../tools/skill-compare/README.md) "Comparison API"). `round-gate` fails when `compare.json` says `regression: true` (fx coverage / brightness dropped more than 20% vs the previous round at a key tick). Hand-built crops do not satisfy this section. Default key ticks = the sheet's contact ticks.
- **Trace**: numbers are copied from the `[vfx-trace]` server log (`docker logs` of the capture stack kept with `SHOT_KEEP=1`, or compare.json `sources[].trace` = `fxSpawnTicks`, `casterTravelBlocks`, `finalGapBlocks` when the capture published `trace.log`). Every `vfx_id` the skill plays must appear with `cast+N`. If the plan tick and the trace disagree, say so here; a content change to timing is allowed only then.
- **Capture scene**: the enemy distance equals the case intent (`DISTANCE=`), FOV is stated, provenance conditions from the README "Real-client capture" are met.
- **Framing**: caster height as % of the frame (compare API `framing[].casterHeightPct`: analytic for the capture, annotated `ref_caster_height_pct` for the ref), `ref` vs `this`; `ratioToRef` must be within 0.8x-1.25x or the capture is not review-valid (recapture with `FOV_DOLLY` / distance, see [tools/vfx-shot/README.md](../../../tools/vfx-shot/README.md)).
- **Tool fidelity**: one line per mechanic type found in the skill YAML (`screen_flash`, `send_title`, `camera_shake`, `spawn_particle` / particle elements, sprites, `play_sound`): `shown (view tick)` or `not shown -> gate 2`. A row that the capture cannot show is not judged by the reviewer.
- **Regression**: `weaker than previous: none` is required to finish the round. Anything weaker is a × of this round even when its sheet row is ○. The first round of a skill writes `previous: none`. **Coverage is not quality**: the compare API's `fx_pct` / brightness are a floor and a regression signal (a drop flags), never a target; a coverage rise obtained with opaque flat fills is itself a regression, and `fx_pct` / "coverage" may not appear in this section as an improvement (gate FAIL).
- **Sibling**: a row of the best *accepted* frames (founder gate 2 passed, or the latest founder-approved revision) of a sibling skill of the same weapon or character, from skill-compare (`/api/cases/<sibling>/...`, saved as `sibling/sheet.png`; until the API has `views=sibling:<case>` take the sibling's `/sheet.png` with its accepted revision in `rounds=`). Then the six art-language lines (`stroke/fill`, `bands`, `taper`, `core line`, `glow`, `outline`; definitions in [reviewer.md](reviewer.md) "Art language") each stating what this round does *and* what the sibling does, and `art language: same`. `differs` means the round is not finished. `sibling: none - <reason>` only when the weapon has no accepted skill yet.
- **Medium** (founder, S3 rebuild: 「VFXをカスタム貼り付けで表現しすぎ、2Dじゃなくて3Dなので猶更ひどい」): the primary VFX language is **particles and Display entities placed in 3D along the real swing / dash / impact geometry**. `main body:` must name them; flat custom sprites are accents (`sprites:` line, count them); `overlay:` is `none` or a one-line reason tied to the reference; `sticker check: no` means no element reads as a flat pasted sprite in the side view at the key ticks (the reviewer checks this in `side`); `pose:` states the ticks at which the rig pose reads in both `compare` and `side` (pose is a reviewed row). A skill whose vfx files spawn no particles fails the gate.
- **Framing tolerance** is `ratioToRef` 0.8x-1.25x (was 0.5x-2x; the founder rejected S3 as 「近すぎる」 inside the old band). The gate owns this number; the skill-compare README should quote it from here.
- **One point to look at**: one line; the manager's report quotes it.
