# Round note template (mandatory sections, checked by `make round-gate`)

Every build round (process step 4-7 in [README.md](README.md)) ends with `tools/skill-compare/data/<case>/round.md` (or the file passed as `FILE=`) published by `make skill-round`. The sections below are **required**; `make round-gate SKILL=<skill> [FILE=<round.md>]` fails the round when one is missing or empty, and the manager does not report a round that fails. Keep each section short: facts, paths, numbers from tools; no reasoning diary. Images go beside the notes file (max 12).

```markdown
# <case> round rN  (sha <dev sha7>, previous round rM sha <sha7>)

## Compare
![ref | this | previous](compare/sheet.png)
source: /api/cases/<case>/compare?ticks=<t1,t2,t3>&views=ref,<view>&rounds=current,prev   (compare.json = this JSON, sheet.png = /sheet.png with the same params)
this: <sources[].revision of current>  previous: <sources[].revision of prev>  view: <view>  regression: false

## Trace
play <vfx_id_a> cast+6 | spawn ... cast+10
play <vfx_id_b> cast+36 | first spawn cast+40
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

## Medium (right medium per element)
main body: slash arcs = fixed-plane sprites on the real swing plane (t10 chop, t40 thrust); slam = BlockDisplay ground plates + dust; pillar = particle columns + 4 thin BlockDisplay bars
sprites: slash arcs on the swing plane (2 per hit, 3-5 blocks along the arc) + custom particles (sparks, 0.3 blocks); no plates, no pillar, no stamps
overlay: none            # or: screen_flash t40 - the ref whites out the frame for 2 frames (ref 0:12.4)
sticker check: no        # side view at the key ticks: no pillar / beam / plate / starburst / fill / center stamp reads as a pasted sprite
pose: reads in compare and side (t10 wind-up, t40 thrust, t63 slam)
particle concept:        # per beat, designed from the skill concept, not copied from the reference
- chop (t10-14): sparks fly off the tip opposite the swing, fast, short-lived | anchor: tip | params: crit x12/tick, speed 0.9, spread 15 deg, life 6, orange
- dash (t38-40): dust kicked back along the dash vector, low, lingering | anchor: feet / dash | params: dust #C8B08A x20/tick, speed 0.3, spread 20 deg, life 14
- slam (t63): debris up from the contact point, rings of dust outward | anchor: contact | params: block dust x40, speed 0.6 up, spread 360 deg flat, life 18

| element | medium | reason |
|---|---|---|
| `bastion-s3-p-*` | vanilla particle | volt sparks on the pillar volume, dust on the dash line, anchored to tip / feet |
| `s3c-cres-*`, `s3c-streak-*` | fixed-plane sprite | chop arc laid in the swing plane, 3-5 blocks along the arc (sibling S2 recipe) |
| `s3p-bar-*` | BlockDisplay | pillar = 4 thin bars 0.15 x 4 blocks + particle columns, never one sprite |
| `s3sl-flash` | center-billboard sprite | contact flash, 2 ticks, 1.5 blocks: a point light has no plane to lie in (ref 0:12.4) |

## Gameplay (respectful remake, not a replica)
startup: 8 ticks to the first hit (trace cast+8)            # > 10 ticks needs `| justified: <why this is a slow heavy>`
reach: chop cone 3.5 blocks x 90 deg; slam box 3 x 3 blocks (skill YAML)
projectile: none                                             # or: speed 1.6 blocks/tick, straight (no gravity), 3D hitbox 0.6, max range 24 blocks
dash: 5.4 blocks in 3 ticks, stops 1.2 blocks short; closes the gap in PvP, carries through a PvE crowd
pvp: hits a strafing player (ring 360 deg); recovery 20 ticks is punishable
pve: slam clears a 3-block crowd; knockback 0.8
deviation from reference: slam radius 3 (ref ~2) for play; startup compressed 14 -> 8 ticks

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
- **Medium, right medium per element** (founder, S3 rebuild; corrected the same day: 「3Dを主体にしろってわけじゃない。2Dを使うのに適している場面(カスタム「パーティクル」と斬撃)だよねって話。斬撃自体を2DTextDisplayで作るのはいい」「TextDisplayにするならプレイヤーに常に向く奴はあんまり作っちゃいけない、大きすぎるとチープ具合がバレる。光の柱が特にひどい」): every element uses the medium that suits it. **2D TextDisplay sprites are the right medium** for slash arcs placed on the real swing plane (`billboard: fixed`, oriented to the blade path; they may be larger than the cap when they sit on that plane) and for small custom particles. **They are the wrong medium** for pillars / beams, plates, filled starbursts, big flat fills, screen overlays and large `billboard: center` stamps: those are particles plus thin solids (BlockDisplay) or ItemDisplay models. `main body:` says what each beat's main body is made of (a sprite slash body is fine); `sprites:` says where 2D is used; `overlay:` is `none` or a one-line reason tied to the reference; `sticker check: no` means none of the misuses above reads as a flat pasted sprite in `side` at the key ticks (slash arcs are not stickers); `pose:` states the ticks at which the rig pose reads in both `compare` and `side` (pose is a reviewed row). **Per-element table** `| element | medium | reason |` (element id, sprite id or `glob*` patterns, comma-separated; media: `vanilla particle`, `display particle`, `BlockDisplay`, `ItemDisplay model`, `fixed-plane sprite`, `center-billboard sprite`, `screen overlay`): the gate reads the skill's vfx YAML and requires a row for every display element; every `billboard: center` sprite needs a `center-billboard sprite` row with its reason (disallowed by default: it always faces the player and its size shows); at most 10 center-billboard elements and at most 30 % of the display elements, whatever the reasons (S3 r7: 32 of 98); a sprite whose estimated long side exceeds **2 blocks** (max keyframe scale x sprite px x 0.025, an estimate) needs its size in blocks in the reason; a pillar / beam / column sprite over the cap fails outright. **Particle concept**: particles are designed from the skill concept for Minecraft, not copied from Limbus frames (the reference governs pose, timing, camera and the large forms only); one line per beat: intent, `anchor:` (tip / edge / contact / feet / dash / landing on the rig path) and `params:` (type, count, speed, spread, lifetime, colour). A skill whose vfx files spawn no particles fails the gate.
- **Framing tolerance** is `ratioToRef` 0.8x-1.25x (was 0.5x-2x; the founder rejected S3 as 「近すぎる」 inside the old band). The gate owns this number; the skill-compare README should quote it from here.
- **Gameplay** (founder: 「再現に夢中になりすぎて、普通に武器の挙動として弱いとかっていうのは避けたい。飛び道具が上手く飛ばない、発生フレームが遅すぎる、範囲が狭すぎるetc… あくまでLimbus完全再現ではなく、Limbusをリスペクトして、このゲーム(PvPやPvEのマイクラ3Dゲーム)に合う形でリメイクする」): the goal is a remake that plays well in Minecraft PvP / PvE. The reference sets the identity (pose silhouettes, key beats, big forms, mood); timing may be compressed and hit areas / ranges enlarged for play. Lines: `startup:` ticks from cast to the first hit from the trace (a normal skill hits within ~8-10 ticks; above 10 the line needs `justified: <why>`); `reach:` hit area / range in blocks from the skill YAML; `projectile:` `none`, or speed, gravity / arc, 3D hit detection and max range (projectiles fly in a Minecraft-sensible 3D way, never the reference's frame timing or 2D path); `dash:` (required when the skill moves the caster) distance, ticks, stop gap and what it is for; `pvp:` and `pve:` one line each on how it plays; `deviation from reference:` `none` or the list. Where fidelity and gameplay conflict, gameplay wins and the deviation is written here. The reviewer judges these rows with the same weight as the visual rows: "looks right but plays weak" is `overall: ng`.
- **One point to look at**: one line; the manager's report quotes it.
