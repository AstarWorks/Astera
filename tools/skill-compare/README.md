# skill-compare

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

- `vfx-caster|opponent|side`: real client frames `build/vfx-shot/<skill>/<view>/frame_NNN.png` (`make vfx-shot SKILL=...`,
  frame NNN = tick after the cast; override the dir with `SKILL_COMPARE_VFX_SHOT`).
- `viewer`: `tools/skill-viewer/build/skill-frames/<skill>/frames/key-<tick>.png` (nearest key tick; only key ticks exist).
- MC hit markers: damage ticks of `tools/game-sim/build/sim-traces/showcase-<skill>.json` (minus `castTickInTrace`, default 5)
  or `build/vfx-shot/<skill>/meta.json` `{"hitTicks":[..]}` (relative to the cast).

Frames before the cast / after the last frame are shown dimmed. Align with the "cast offset" slider.

## Security

Only `public/`, `src/` (bundled on the fly), `data/`, `cache/` and the three build dirs above are reachable, by fixed
URL patterns with strict file-name regexes. Binding `0.0.0.0` is intended for the LAN; there is no auth, do not expose it.
