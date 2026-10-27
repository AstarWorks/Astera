# Round note template (mandatory sections, checked by `make round-gate`)

Every build round (process step 4-7 in [README.md](README.md)) ends with `tools/skill-compare/data/<case>/round.md` (or the file passed as `FILE=`) published by `make skill-round`. The sections below are **required**; `make round-gate SKILL=<skill> [FILE=<round.md>]` fails the round when one is missing or empty, and the manager does not report a round that fails. Keep each section short: facts, paths, numbers from tools; no reasoning diary. Images go beside the notes file (max 12).

```markdown
# <case> round rN  (sha <dev sha7>, previous round rM sha <sha7>)

## Compare
![ref | this | previous](compare/sheet.png)
source: /api/cases/<case>/compare?view=<view>&ticks=<t1,t2,t3>&previous=<revision>
this: <published revision>  previous: <published revision>  view: <view>

## Trace
play bastion-s3-fx-ring cast+6 | spawn ... cast+10
play bastion-s3-fx-thrust cast+36 | first spawn cast+40
... (one line per vfx_id of the skill, from `[vfx-trace]`)
travel: caster z 300.5 -> 305.9 over cast+38..40 (5.4 blocks, from trace)   # when the skill moves the caster

## Capture scene
DISTANCE=7.2 (case intent enemy x)  FOV=45 (compare: intent camera)  skippedTicks=0  opponent alive  sha = dev

## Framing
caster bbox: ref 11% | this 9% | previous 9%   (key ticks t10/t40/t63; from compare.json)

## Tool fidelity
screen_flash: not shown by the spectator capture (title overlay) -> judged in game at gate 2
camera_shake: not shown -> gate 2
particles: shown (side t40, top t40)
sprites: shown (side t10)
sound: not listened

## Regression (vs previous round rM)
weaker than previous: none
stronger / changed: thrust streak longer (t40), pillar top opened (t66)

## Changed
- <file>: <one line>

## One point to look at
<the single thing the founder should check first, with tick and view>
```

Rules for the sections:

- **Compare**: the sheet comes from the compare API (`/api/cases/<id>/compare`, `sheet.png` + `compare.json` saved beside the notes under `compare/`). Hand-built crops do not satisfy this section. Default key ticks = the sheet's contact ticks.
- **Trace**: numbers are copied from the `[vfx-trace]` server log (`docker logs` of the capture stack, or `meta.json` `trace` once vfx-shot records it). Every `vfx_id` the skill plays must appear with `cast+N`. If the plan tick and the trace disagree, say so here; a content change to timing is allowed only then.
- **Capture scene**: the enemy distance equals the case intent (`DISTANCE=`), FOV is stated, provenance conditions from the README "Real-client capture" are met.
- **Framing**: caster bbox share of the frame at the key ticks, `ref` vs `this`; `this` must be within 0.5x-2x of `ref` or the capture is not review-valid (recapture with `FOV_DOLLY` / distance, see [tools/vfx-shot/README.md](../../../tools/vfx-shot/README.md)).
- **Tool fidelity**: one line per mechanic type found in the skill YAML (`screen_flash`, `send_title`, `camera_shake`, `spawn_particle` / particle elements, sprites, `play_sound`): `shown (view tick)` or `not shown -> gate 2`. A row that the capture cannot show is not judged by the reviewer.
- **Regression**: `weaker than previous: none` is required to finish the round. Anything weaker is a × of this round even when its sheet row is ○. The first round of a skill writes `previous: none`.
- **One point to look at**: one line; the manager's report quotes it.
