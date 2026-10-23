# Director knowledge base

Agent: `.claude/agents/limbus-director.md`. Team overview: [README.md](README.md).

## What the director produces

`beats` in `tools/skill-compare/data/<case>/case.json`, six briefs in `.../briefs/`, the skill YAML, the composite VFX, the capture and its publication. Then it routes reviewer diffs.

## Verified facts and where they live

### Procedure and acceptance
- Procedure, acceptance table, status vocabulary (未分析 / 実装済・未検証 / 検証不合格 / 再現合格 / 独自設計・動作確認済): [limbus-skill-reproduction.md](../limbus-skill-reproduction.md) sections 1-7. The 2026-10-23 founder instruction removed the "max 4 iterations" stop: iterate until the match is shown with evidence. A skill is done only when the reviewer passes every row AND the founder approves the published capture on skill-compare; the director records the approval (date, published revision) in the weapon REPORT.
- Do not change the target to fit the implementation; `intent` is an interpretation of the video, not proof.
- Observation: measure sizes against character height; separate camera change from object scaling.

### Tools
- `make skill-compare-case CASE=<id> VIDEO=<youtubeId> START=<s> END=<s> SKILL=<skill> [WEAPON=<item-id>]` creates `case.json`, an `intent.json` template and native-fps frames. Schema example: `tools/skill-compare/data/zwei-s3/intent.json`.
- `make skill-brief CASE=<id> [VIEW=compare] [AT="t:label"]` writes `build/brief/<case>/sheet-NN.png` + `manifest.json` (reference | intent | in-game per row). Non-empty `warnings` in the manifest means do not use the image as evidence. See [skill-compare README](../../../tools/skill-compare/README.md).
- `make skill-pairs CASE=<id> AT="19.7:hit1 20.9:hit2"` (absolute video seconds).
- Capture: `SKILL=<skill> ITEM=<item-id> VIEW=compare CASE=<case> bash tools/vfx-shot/vfx-shot.sh`. Always give `ITEM` explicitly (especially `-ego`). Details, views, FOV rules, slots: [vfx-shot README](../../../tools/vfx-shot/README.md).
- Publish after quality review: `node tools/skill-compare/scripts/publish-capture.mjs build/vfx-shot/<skill>/compare <published-root> <skill> compare`. Requires matching skill/view, a contiguous PNG sequence, and no `captureQuality.invalid`. Once published, the server never falls back to the mutable working directory.
- Round-2 captures need matching `referenceRevision` and `contentRevision` in case and `meta.json`; changed content requires recapture.
- `--force` capture does not verify cooldown, resources, or key bindings. Check S1/S2/S3/DEF/Q normal input separately.
- Reference weapons are the ADR-0230 roster (10 weapons): [0230-persona-weapon-roster](../../adr/0230-persona-weapon-roster.md). Weapon-level REPORT files live in `tools/skill-compare/data/<weapon>-REPORT.md`.

### Skill YAML
- Reference: [add-a-skill-yaml.md](../add-a-skill-yaml.md). Dash/combo mechanics (`dash_strike`, `hit_stop`, `propel`, `snapshot_pose`, `archetype: sequence`) in its last section.
- Pitfalls from section 4 of the procedure: separate `random` per damage/spark/stun picks different enemies; single-target melee uses `cone.max_count: 1`; `nearby_entities.max_count` excludes the caster; particle `drag` is the fraction lost (0.95 loses 95%); `billboard: center` boards must not also get the caster yaw.
- Display budget: <= 60 simultaneous Displays per cast (E2E measured), <= 300 particles per tick, effect <= 60 ticks (finishers <= 100). Count delayed instances, rig trails, freeze extension.
- Hit VFX and damage on the same tick and same point, also after knockback/movement.

### Composite VFX
- Choreography file `content/vfx/<id>.yaml` (ADR-0220). Rig with `follow: false` for weapon swings. `play_vfx` `freeze: [{at, ticks}]` stops rigs that set `respect_hit_stop: true`.
- Layering order from [vfx-art-direction.md](../vfx-art-direction.md) section 4: telegraph -> motion -> hit -> afterglow. Core opaque (Sakurai), glow as accent, dark mixed with bright.

## Runtime facts and pitfalls (confirmed)

- Font sprites render only up to 256 px; 512 px fails. (Commit `ee976100` on `wp/pilot-bastion-s1`: "512px sprites do not render in the real client (font page limit); 256px with 2x2 supersampled edges is the working maximum.")
- A TextDisplay with `fixed` billboard laid horizontal does not render in the real client (it does in vfx-preview). Never trust vfx-preview alone for horizontal text boards.
- `emit.size_over_life` with more than 2 values silently drops the whole VFX at runtime, while content-lint passes it. Grep every VFX for it before capture.
- Hit VFX must peak on the damage tick. Verify in the capture frame at the hit tick.
- Only one weapon may be visible during a skill: mechanic `hide_held_item` exists only on branch `wp/pilot-bastion-s1` (it is in `content/schemas/skill.schema.json` and `content/skills/bastion-greatsword-{s1,s2,ego}.yaml` there) and is **not on main**. Until merged, the pilot branch is the only place it works; bring that mechanic with the merge before using it elsewhere.
- Rejected looks: crescents built from uniform boxes (hoops); fire made of squares (cubes); poles for jets; a static ring standing like a wall; separate floating sprites instead of one continuous sweep; a pixel-art staircase at close-up; black `#`-like smoke glyphs.
- Machine load causes 2-6 tick (sometimes ~20) capture lag. Reject frozen or magenta-cube captures (`captureQuality.invalid`). Repeated idle frames alone do not prove a stall; `maxRepeatedFrames >= 15` needs visual confirmation.
- Software GL renders ~35 fps at 1280x720, so +-1 tick is normal; do not tune per-hit offsets to hide sync drift.
- Runtime applies 10% / x2 random crits (`CombatWiring`, procedure section 5), so total HP loss does not count hits; count hits from frames or events.
- Machine/slot discipline: one real-client capture at a time. A compare capture and a 3-view capture of the same skill share a `SHOT_ID`; never run them concurrently.

## Brief template (copy per specialist)

```
# <role> brief - <case>
Reference: video <id> t=<a>..<b>s; frames: <paths>
Beat(s) owned: <ticks and what must be visible>
Targets (numbers): sizes, angles, colours (hex), durations (ticks)
Files you may touch: <paths>
Must not: <Rejected looks that apply, other roles' files>
Acceptance rows you answer: <rows from the procedure>
Hand back: <report format from your agent file>
Relevant Learnings: <links>
```

## Learnings log

(Append dated bullets after each skill, newest last. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: created from repo docs; no skill run yet.
- 2026-10-24 bastion-greatsword-s1 (integration round 1, single rig): custom OGGs are referenced as `sound_id: 'astera:weapons/<name>'` (bare OGG = its own event). `hit_stop` holds entities only; skill `delay_ticks` run in real ticks, so a rig `freeze` of the same ticks shifts only the rig clock (hit 2 keys already include the +2); no drift to fix in YAML. `hide_held_item` must cover rig duration + freeze windows (52 + 4 = 56).
- 2026-10-24 bastion-s1: the vfx trail bands pasted with the transparent inner band dropped (alpha 00 band is pure budget) and `dark_rim` dropped, step_deg kept 40: 2 bands + edge = 21 trail displays; 20 rig + 21 trail + <=16 fx = 57 <= 60. Do not use step_deg 20 (doubles it).
- 2026-10-24 bastion-s1 capture facts: compare frame N = skill tick N-6 (PRE_TICKS 6). Held item still shows at t+0/t+1, nothing at t+2/t+3, rig sword from t+4 (a 2-tick bare-hand gap; held item returns ~t+56). First-person caster view: stray `small_flame` tip embers and the hit-2 ink sprite fill the camera (t+16 big flame quad, t+32 full-screen gold). Particles DO render (crit, flames, dust, white poof squares).
- 2026-10-24 tooling: a worktree-isolated agent cannot run compound shell (`;`, `&&`, `$(..)` with git) or `bash -c`; run `make vfx-shot ...` with env args in background and wait with `timeout N tail --pid=PID -f /dev/null`.
- 2026-10-24 bastion-s1 (integration round 2): the rig trail wake (`trails:` on the rig) is removed when a smooth ring sprite carries the cut; frames showed no polyline hoop and budget fell to rig 20 + fx <= 11 = 31. `hide_held_item` with `delay_ticks: 1`, `ticks: 57` (rig `duration_ticks` 58): in the capture the held item hides at compare t3 and the rig is the only sword t3..t58, held item back t59 (client shows both 1-2 ticks later than the server); no tick with two weapons or none. First-person caster view renders no hand at all (empty before and after), so "hand empty" cannot be proven there.
- 2026-10-24 whoosh placement: peak tick = start tick + peak_ms * pitch^-1 / 50 (whoosh-heavy 121 ms: hit 1 start 4 at pitch 1.0 peaks 6.4, contact 7; hit 2 start 27 at pitch 0.8 peaks 30.0, contact 31).
- 2026-10-24 tooling: under load (avg 10-24) the opponent view stalled 3 of 4 times (maxRepeatedFrames 17/57/28 = frozen early frames, even without `invalid`); only maxRepeatedFrames <= 3 with the sheet visibly progressing is usable. Compare capture needs a committed tree: `implementationCommit` records HEAD, so commit content before capturing. In opponent view fx sprites at z 2.5-3.1 sit around the camera (target position) and are not visible; rig sword and particles are.
- 2026-10-24 timing: in the captures sprites and the hurt tint both appear ~3 ticks after the nominal contact frame (t10-11 vs tick 7, lag varies per run 0-3); damage tint and ring peak land within 1 tick of each other, so do not retune YAML offsets for it.
- 2026-10-24 bastion-greatsword-s1 (integration round 3): frame index = tick only for caster/opponent/side (frame_000 = cast tick, 71 frames at TICKS=70); compare has 6 pre-cast frames, so tick 0 = frame_006 and 77 frames. Real client lags the server 1-3 ticks (rig/hit appear at t+2..3 on the sheet, differs run to run): judge one-sword and hit sync on frames, not on the sheet label. VIEW=all does not recapture compare; after any content change recapture compare AND all three views, then publish all four (publish script takes build/skill-compare-published).
- 2026-10-24 bastion-greatsword-s1: tool shell refuses python/loops with `$var` program paths in a worktree agent; put commands in a scratch .sh with literal paths. Staged rig spawn + same-tick hide gave one sword every frame in side/compare/opponent (ticks 0-3, 55-62 cropped). Dropping the 2nd particle calls at ticks 32-33 and ground-poof removed the grey ball near the enemy's feet; ink smoke still reads as a large blue-grey mass at close range in the opponent view (t+12..14).
- 2026-10-24 bastion-greatsword-s1: caster first person: ring appears only as a gold band at the top edge (not overfilled), but the animator's lift (ticks 50-57) puts the rig blade across the lower-right of the caster camera (large, obstructive): route to animator.
- 2026-10-24 bastion-greatsword-s1 (integration round 4): under load avg 10-30 the compare capture failed `cameraMaxLatenessMs > 100` four times (133/207/102/158), passed on the 5th (97 ms); loop `cmp.sh` until `invalid` is absent (scratch retry script) instead of hand-retrying. `VIEW=all` server start failed once (container exit 1), plain retry worked. Hand-over measurement (client lags 1-2 ticks): rig blade is ~2.4 blocks long vs held item ~1.5 (ratio 1.58, both side and compare), grip point 0.25-0.34 block higher on the rig, tip 1.15-1.2 blocks higher, angle differs only 7 deg (32 vs 39 from vertical): the pop is a size jump plus tip height, not an angle jump. Caster first person: lifting rig blade covers ~30% of screen (full height, right third) at t54; narrower (~10%) t55-59, gone t60.
