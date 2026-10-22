# skill-compare

制作・修正の統一手順と合格条件: [Limbus参照武器スキルの制作・修正手順](../../docs/contributing/limbus-skill-reproduction.md)。撮影済み・反復回数到達は再現合格を意味しない。

Local web app (Bun + three.js 0.170, same stack as `tools/skill-viewer`): a reference video at **native fps**, my
"intent" render of it, and Astera's Minecraft frames, on one millisecond clock with an editable timeline.
Not shipped, not in `check`. Needs `bun`, and for fetching `yt-dlp`, `ffmpeg`, `python3` + `numpy`.

```
make skill-compare-fetch VIDEO=Svaaq9F7Ty8     # download + native-fps frames + waveform/onsets/cuts into cache/
make skill-compare                              # http://0.0.0.0:8097  (PORT=, HOST= env); founder: http://192.168.11.5:8097
bun scripts/shot.mjs --ms 1100 --out build/shot.png [--focus video|intent|mc] [--width 600]   # Chromium screenshot
```

Pages: `/` case index, `/case.html?id=<case>`.

## Data

| Path | In git | What |
|---|---|---|
| `data/<case>/case.json` | yes | videoId, start_s/end_s, skill (name, coins, `asteraSkillId`), `segments`, `mc` (source, `offset_ms`, `castTickInTrace`), `focus`, `notes`, `rev`, `editLog` |
| `data/<case>/intent.json` | yes | my reading of the video (prototype schema; tick 0 = `source.start_s`, 20 ticks/s) |
| `cache/` | **no** (gitignored, copyright) | `videos/<id>.mp4`, `<case>/frames/frame_NNNNN.jpg` (native fps, 720p), `meta.json`, `analysis.json` |

Segments: `{id, kind: skill|range|hit, label, notes, start_s,end_s | t_s}`, times in absolute video seconds.
**Save** POSTs the case; the server validates, checks `rev` (409 if someone saved meanwhile), writes `case.json`
atomically and appends `{who, at, summary}` to `editLog` (`~hit1 +hit2 -seg1 notes mc focus`). Read that file to get the
founder's corrections.

## Timeline model

Master clock = ms since `start_s`. Video frame = `floor(ms*fps/1000)` (30 fps: 33.3 ms, never resampled);
MC tick = `floor((ms - castMs)/50)` with `castMs` = skill start + offset slider; intent tick is fractional.
Focus zones: +-150 ms around strong onsets (>= 50 % of the max) and every cut; playback drops to `min(speed, factor)` there
(factor default 0.2, toggle `F`).

Keys: Space play/pause, Left/Right 1 video frame, Shift+Left/Right 1 tick, 1-4 speed 1/.5/.25/.1, F focus slow-mo,
H add hit, Del remove, Esc clear loop, Ctrl+S save. Shift-drag the timeline = loop range. Drag hits / segments (and
segment edges); they snap to video frames. Column focus buttons enlarge one column.

## Minecraft sources

Selected per case in the page, discovered by the server for `skill.asteraSkillId`:

- `vfx-compare` (default when present): third-person real-client frames from the case's own camera keys, `make`-less:
  `SKILL=<skill> VIEW=compare CASE=<case> bash tools/vfx-shot/vfx-shot.sh`; `meta.json` `cast_tick_ms` aligns it automatically.
- `vfx-caster|opponent|side`: real client frames `build/vfx-shot/<skill>/<view>/frame_NNN.png` (`make vfx-shot SKILL=...`,
  frame NNN = tick after the cast; override the dir with `SKILL_COMPARE_VFX_SHOT`).
- `viewer`: `tools/skill-viewer/build/skill-frames/<skill>/frames/key-<tick>.png` (nearest key tick; only key ticks exist).
- MC hit markers: damage ticks of `tools/game-sim/build/sim-traces/showcase-<skill>.json` (minus `castTickInTrace`, default 5)
  or `build/vfx-shot/<skill>/meta.json` `{"hitTicks":[..]}` (relative to the cast).

Frames before the cast / after the last frame are shown dimmed. Align with the "cast offset" slider.

## Security

Only `public/`, `src/` (bundled on the fly), `data/`, `cache/` and the three build dirs above are reachable, by fixed
URL patterns with strict file-name regexes. Binding `0.0.0.0` is intended for the LAN; there is no auth, do not expose it.

## Agent PDCA loop

Each agent works in its own worktree; frames stay in that worktree's `build/`, only `data/<case>/` is committed.

1. **Case**: `make skill-compare-case CASE=<id> VIDEO=<youtubeId> START=<s> END=<s> SKILL=<astera-skill-id> [NAME=.. COINS=..]` writes `data/<id>/case.json`
   + an `intent.json` template and extracts native-fps frames/onsets/cuts. Several cases per video share one download (`SKILL_COMPARE_VIDEO_DIR`,
   default `~/.cache/astera-skill-compare/videos` via make).
2. **Intent**: fill `intent.json` (camera / actors / weapon / effects; tick 0 = START, schema = `data/zwei-s3/intent.json`).
3. **Author**: write/adjust the Astera skill YAML / VFX.
4. **Capture**: `SKILL=<skill> VIEW=compare CASE=<id> bash tools/vfx-shot/vfx-shot.sh` (queues on the global slots, see `tools/vfx-shot/README.md`).
5. **Pairs**: `make skill-pairs CASE=<id> [SKILL=<skill>] AT="19.7:hit1 20.9:hit2"` (t = absolute video seconds; video, t0 and pre-ticks are derived)
   -> `build/pairs/<id>.png`; read it, compare, go back to 3.
6. **Review**: `make skill-compare` serves cases from the checkout it runs in. To also show other worktrees' captures:
   `SKILL_COMPARE_MEDIA_DIRS=/path/wt-a/build/vfx-shot:/path/wt-b/build/vfx-shot make skill-compare` (the main dir is searched first).

## Index (weapon roster) and multi-worktree aggregation

The index lists the persona weapons (`content/items/*.yaml` with ADR-0230 bindings, 10 today) with item-art icon, ja/en name, and one row per bound slot
(左クリック S1 / 右クリック S2 / スニーク+右 S3 / スニーク+左 DEF / Q) with skill name, range type, coins vs implemented hits, video+MC thumbnails at the first hit,
status chip (未着手 / 分析済 / 実装済 / 撮影済), `N回目`, REPORT link and last update. Weapons without cases stay in the list, greyed. `GET /api/index` is the JSON.

Every request re-scans main + `.claude/worktrees/*/tools/skill-compare/data/*/case.json` (+ their `build/vfx-shot/`), plus `SKILL_COMPARE_DATA_DIRS` / `SKILL_COMPARE_MEDIA_DIRS`.
Duplicate ids: the highest `rev` wins, then the newest `case.json` (an identical copy in main never loses to a worktree checkout). Each row shows which branch it came from.
Saving a case writes the winning copy (in whichever worktree it lives).

Optional case.json fields read by the index (the page preserves them on save): `identity` (character/source), `weapon` (item id), `videoTitle`,
`status` (`analysed` default, `built` when the skill is authored; `captured` is derived from the existence of `compare` frames), `iterations` (number), `report` (URL or `/path`).


### Round 2 playback and provenance

`SKILL_COMPARE_CANONICAL=1 bun server.mjs` prioritizes the serving checkout's cases over older worktrees. `SKILL_COMPARE_WORKTREE_ROOT` can point to another checkout's `.claude/worktrees` for shared reference caches. Native-fps reference caches must match the case's video and exact interval. Cases with `status: from-wiki` do not borrow footage of another skill.

Round 2 captures require matching `referenceRevision` and `contentRevision` in the case and capture `meta.json`. Changed content requires recapture. The player bounds image concurrency and memory, waits for both columns before advancing, and exposes loading/errors/retry. Automatic focus slow motion defaults off. Missing traces are shown as unmeasured rather than zero hits.
