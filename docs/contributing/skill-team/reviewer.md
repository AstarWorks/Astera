# Reviewer knowledge base

Agent: `.claude/agents/limbus-reviewer.md`. Team overview: [README.md](README.md).

## Independence rule

The reviewer is read-only on content, code, briefs and case data. It never fixes; it writes diffs. The people who built the skill do not grade it. The reviewer judges against the **reference video**, not against the director's briefs or intent file ("intent is an interpretation, not proof").

## Acceptance rows (source: [limbus-skill-reproduction.md](../limbus-skill-reproduction.md) section 6)

| Row | Pass condition |
|---|---|
| Target skill | video segment and binding / implementation id match |
| Motion | move direction, swing direction, contact order, count, return match the observation table |
| Shape | main shape, pose, size, placement, colour targets met; no board facing the wrong way, occlusion, floating or missing piece |
| Timing | beats align from a common origin; 20 TPS rounding is up to 25 ms; real-client lag measured separately; if unknown, timing does not pass |
| Hit | display and damage agree; expected count of hits with the weapon equipped |
| Playability | caster and opponent views, telegraph, danger range, cleanup |
| Budget | simultaneous displays and lifetimes within limits; exceptions justified |

Add for this team: a sound exists for every beat; only one weapon is visible during the skill; the model is multi-part, multi-material, not a flat slab; sprites are smooth at close-up.

Status vocabulary for the report: 未分析 / 実装済・未検証 / 検証不合格 / 再現合格 / 独自設計・動作確認済. "Captured" or "iteration limit reached" is never a pass. A skill with no observed reference never gets an original-match verdict.

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
- Only one real-client capture runs at a time; a compare capture and a 3-view capture of the same skill share a `SHOT_ID`. Do not start captures yourself unless the director asks.
- Runtime facts that explain "missing" effects: font sprites render only up to 256 px (512 fails); a horizontal `fixed`-billboard TextDisplay does not render in the real client; `emit.size_over_life` with more than 2 values silently drops the whole VFX while content-lint passes; hit VFX must peak on the damage tick; `hide_held_item` is only on `wp/pilot-bastion-s1`/ee976100, not main (two weapons visible on other branches is expected until merged).

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

## Diff format (each diff)

```
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
