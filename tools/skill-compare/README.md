# skill-compare — Astera レビューハブ

制作・修正の統一手順と合格条件: [Limbus参照武器スキルの制作・修正手順](../../docs/contributing/limbus-skill-reproduction.md)。撮影済み・反復回数到達は再現合格を意味しない。

Local web app (Bun): a reference video at **native fps** and Astera's Minecraft frames, side by side on one millisecond clock with an
editable timeline, plus a text **beat sheet** (segments, case notes, optional `beats`) that follows the clock.
Not shipped, not in `check`. Needs `bun`, and for fetching `yt-dlp`, `ffmpeg`, `python3` + `numpy`.

```
make skill-compare-fetch VIDEO=Svaaq9F7Ty8     # download + native-fps frames + waveform/onsets/cuts into cache/
make skill-compare                              # http://0.0.0.0:8097  (PORT=, HOST= env); founder: http://192.168.11.5:8097
bun scripts/shot.mjs --ms 1100 --out build/shot.png [--focus video|mc] [--width 600]   # Chromium screenshot
```

Pages: `/` case index, `/case.html?id=<case>` (video | Minecraft columns, beat sheet under them, timeline below).

## Data

| Path | In git | What |
|---|---|---|
| `data/<case>/case.json` | yes | videoId, start_s/end_s, skill (name, coins, `asteraSkillId`), `segments`, optional `beats`, `mc` (source, `offset_ms`, `castTickInTrace`), `focus`, `notes`, `rev`, `editLog` |
| `cache/` | **no** (gitignored, copyright) | `videos/<id>.mp4`, `<case>/frames/frame_NNNNN.png` (native fps, native resolution, lossless; best stream YouTube offers, usually 1080p VP9), `meta.json`, `analysis.json` |

Beats (optional, shown as a table in the beat sheet, active row highlighted, click = seek): `[{t_s, label, weapon, caster_move, vfx, notes}]`,
`t_s` in absolute video seconds, the rest strings (weapon orientation, caster movement, effects per moment). Old `intent*.json` files are ignored.

Segments: `{id, kind: skill|range|hit, label, notes, start_s,end_s | t_s}`, times in absolute video seconds.
**Save** POSTs the case; the server validates, checks `rev` (409 if someone saved meanwhile), writes `case.json`
atomically and appends `{who, at, summary}` to `editLog` (`~hit1 +hit2 -seg1 notes mc focus`). Read that file to get the
founder's corrections.

## Timeline model

Master clock = ms since `start_s`. Video frame = `floor(ms*fps/1000)` (30 fps: 33.3 ms, never resampled);
MC tick = `floor((ms - castMs)/50)` with `castMs` = skill start + offset slider.
Focus zones: +-150 ms around strong onsets (>= 50 % of the max) and every cut; playback drops to `min(speed, factor)` there
(factor default 0.2, toggle `F`).

The Minecraft column shows a provenance badge (branch, commit, capture time, FOV + mode, resolution, published / unpublished, repeated-frame warning); a red chip means the capture did not record that field. Columns are labelled
Reference / In-game and always share one equal 16:9 size. The beat sheet lists the segments in time order (active ones highlighted and scrolled into view; click = seek), the case `notes`, and the `beats` table. `[` / `]` or the contact buttons jump to the previous / next hit.

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

Trusted LAN only; there is no authentication. The hub reads the explicitly listed output directories below.
`/hub-file/` resolves real paths inside the selected root, refuses symlink escapes, and only serves viewable assets;
HTTP never launches a generator. Generated HTML runs in a sandboxed iframe. Keep generated review files trusted.
The live tab embeds the capture server on its own origin, preserving its JSON/Origin and WebSocket checks.

## Agent PDCA loop

Each agent works in its own worktree; frames stay in that worktree's `build/`, only `data/<case>/` is committed.

1. **Case**: `make skill-compare-case CASE=<id> VIDEO=<youtubeId> START=<s> END=<s> SKILL=<astera-skill-id> [NAME=.. COINS=..]` writes `data/<id>/case.json`
   and extracts native-fps frames/onsets/cuts. Several cases per video share one download (`SKILL_COMPARE_VIDEO_DIR`,
   default `~/.cache/astera-skill-compare/videos` via make).
2. **Beats**: write the frame-by-frame reading of the video into `case.json` `beats` (weapon orientation, swing, caster movement, effects) and the `segments` notes.
3. **Author**: write/adjust the Astera skill YAML / VFX.
4. **Capture**: `SKILL=<skill> VIEW=compare CASE=<id> bash tools/vfx-shot/vfx-shot.sh` (queues on the global slots, see `tools/vfx-shot/README.md`).
5. **Pairs**: `make skill-pairs CASE=<id> [SKILL=<skill>] AT="19.7:hit1 20.9:hit2"` (t = absolute video seconds; video, t0 and pre-ticks are derived)
   -> `build/pairs/<id>.png`; read it, compare, go back to 3.
5b. **Brief (AI, no browser)**: `make skill-brief CASE=<id> [VIEW=compare] [AT="t:label"]` -> `build/brief/<id>/sheet-NN.png` (rows = start / each hit / end, columns = reference | in-game, equal scale) +
   `manifest.json` (per-row video s / master ms / MC tick / frame / file paths; capture branch, commit, time, FOV, resolution, published revision; `warnings`). Alignment is the page's (cast = skill segment start + `mc.offset_ms`).
   Capture source: `build/skill-compare-published/<skill>/<view>/current.json` if published, else `build/vfx-shot/<skill>/<view>` (override with `--mc <dir>` via `python3 -I scripts/brief.py`).
6. **Review**: `make skill-compare` serves cases from the checkout it runs in. To also show other worktrees' captures:
   `SKILL_COMPARE_MEDIA_DIRS=/path/wt-a/build/vfx-shot:/path/wt-b/build/vfx-shot make skill-compare` (the main dir is searched first).

## Animation review (`/anim`, skin-rig motions of ADR-0250)

Pages: `/anim` (one row per `content/animations/*.yaml`, `_rig/` excluded: thumbnail, length, loop / movement-lock flags, the latest capture's
commit + date, review status 未レビュー / OK / NG with the latest comment) and `/anim.html?id=<animation id>`:
(a) rest comparison, vanilla above rig x front / side / top; (b) blend-in / blend-out strips labelled by tick (click = jump the player there);
(c) player over `play/` frames: play / pause, tick step, speed 1 / .5 / .25 / .1, front / side (Space, arrows, 1-4); (d) the same provenance badge as the case page
(`src/prov.js`); (e) review box: status, overall comment, comments pinned to the tick shown in the player. The main index header links to `/anim`.

Capture (one command per motion, `make anim-shot ANIM=<animation id | skill id>`, see `tools/vfx-shot/README.md`) writes
`build/vfx-shot/anim/<id>/` (`vanilla-*.png`, `rig-*.png`, `play/`, `blend-in/`, `blend-out/`, `meta.json`). It is found like every other capture: this checkout's
`build/vfx-shot`, other worktrees', and `SKILL_COMPARE_MEDIA_DIRS`; the most recently written `anim/<id>/meta.json` wins. There is no publish step
for animations (frame URLs carry the capture's mtime so a recapture is never shown stale). Media is served only at
`/anim-media/<id>/(vanilla|rig)-(front|side|top).png` and `/anim-media/<id>/(play|blend-in|blend-out)/(front|side)/frame_NNN.(png|webp)`.

**Where agents read the founder's feedback**: `tools/skill-compare/data/anim/<id>/review.json`
`{id, rev, status: unreviewed|ok|ng, comment, pins: [{tick, text}], editLog: [{who, at, summary}]}`. `tick` is relative to the trigger (t+0 = first rig tick, negative = before).
Saving POSTs `/api/anims/<id>/review` with the `rev` it loaded (409 if someone saved meanwhile), appends `status old->new`, `comment "..."`, `+pin@7 "..."`, `-pin@7`
to `editLog`, and writes the file atomically. Highest `rev` across main + worktrees wins when reading (as for cases); commit the file with the fix it asked for.
`GET /api/anims` and `GET /api/anims/<id>` are the JSON.

## Index (weapon roster) and multi-worktree aggregation

The index lists the persona weapons (`content/items/*.yaml` with ADR-0230 bindings, 10 today) with item-art icon, ja/en name, and one row per bound slot
(左クリック S1 / 右クリック S2 / スニーク+右 S3 / スニーク+左 DEF / Q) with skill name, range type, coins vs implemented hits, video+MC thumbnails at the first hit,
status chip (未着手 / 分析済 / 実装済 / 撮影済), `N回目`, REPORT link and last update. Weapons without cases stay in the list, greyed. `GET /api/index` is the JSON.

Case discovery is cached for one second and scans main + `.claude/worktrees/*/tools/skill-compare/data/*/case.json` (+ their `build/vfx-shot/`), plus `SKILL_COMPARE_DATA_DIRS` / `SKILL_COMPARE_MEDIA_DIRS`.
Duplicate ids: the highest `rev` wins, then the newest `case.json` (an identical copy in main never loses to a worktree checkout). Each row shows which branch it came from.
Saving a case writes the winning copy (in whichever worktree it lives).

Optional case.json fields read by the index (the page preserves them on save): `identity` (character/source), `weapon` (item id), `videoTitle`,
`status` (`analysed` default, `built` when the skill is authored; `captured` is derived from the existence of `compare` frames), `iterations` (number), `report` (URL or `/path`).


### Round 2 playback and provenance

`SKILL_COMPARE_CANONICAL=1 bun server.mjs` prioritizes the serving checkout's cases over older worktrees. `SKILL_COMPARE_WORKTREE_ROOT` can point to another checkout's `.claude/worktrees` for shared reference caches. Native-fps reference caches must match the case's video and exact interval. Cases with `status: from-wiki` do not borrow footage of another skill.

Round 2 captures require the case `referenceRevision` and `contentRevision` to equal the capture `meta.json` `referenceRevision` and `caseContentRevision` (the capture's own `contentRevision` is a fingerprint of the filmed `content/`, see the vfx-shot README). Changed content requires recapture. The player bounds image concurrency and memory, waits for both columns before advancing, and exposes loading/errors/retry. Automatic focus slow motion defaults off. Missing traces are shown as unmeasured rather than zero hits.


Frames use WebP previews when the full preview sequence exists; PNG remains the measurement source and fallback. Frame URLs include capture metadata modification time, so recapturing the same case does not display stale cached images. `captureQuality.invalid: true` excludes a confirmed stalled capture; repeated idle frames alone do not prove a stall.

The Minecraft selector separates forced comparison shots (`compare/`) from real key/mouse recordings (`input/`). Both require current reference/content hashes. Normal-input setup and trigger are recorded in metadata; having a recording is not proof that the requested skill was accepted. `/report/<weapon>`, `/reference/<weapon>/<file>`, and `/evidence/<case>.png` expose the review record, source analysis, and same-time image pairs.


### Public URL recovery

A local HTTP 200 or a public Cloudflare Access login redirect does **not** establish that the tunnel is connected. For the existing deployment, inspect `cloudflared tunnel info b96ef35b-39d2-4a12-89ff-458f927cb649` and the running connector. Its configuration is `~/.cloudflared/astera-skill-compare.yml`, forwarding to `http://127.0.0.1:8097`. Restore a stopped connector with `cloudflared tunnel --config ~/.cloudflared/astera-skill-compare.yml run` in a persistent supervised process, without removing Access protection. Confirm registered edge connections, origin response, and an authenticated public page separately.

Camera keyの `transition: linear` は直前のkeyからの連続移動を表す。比較撮影は20TPSで位置・注視点・FOVを補間し、設計図表示も同じ指定を解釈する。明示的な `transition: cut` は瞬間切替。撮影ツールで指定がないkeyは従来どおりcut、設計図の旧データは従来の補間規則を保つ。

### Publish completed Minecraft captures

After capture, WebP conversion, quality review, and runtime/content hash stamping, publish the accepted result:

```sh
node tools/skill-compare/scripts/publish-capture.mjs \
  build/vfx-shot/edict-procuration-s3/compare \
  /IdeaProjects/Astera/build/skill-compare-published \
  edict-procuration-s3 compare
```

The server uses `build/skill-compare-published` under its repository by default; `SKILL_COMPARE_PUBLISHED` overrides it. The publisher requires matching skill/view metadata, a complete contiguous PNG sequence, and no `captureQuality.invalid`. It copies WebP when every frame is present, otherwise PNG. Finish writing and reviewing the source before invoking it.

Each publication has an immutable SHA-256 revision directory. An atomic `current.json` pointer selects the revision for newly opened pages. Frame requests include that revision, so an already open page keeps its images while the next capture overwrites the working directory. Failed publication retains the previous pointer. Reference/content revision gates still apply; older captures are not relabeled as matching new content. Keep old revision directories while pages may still be using them.

Legacy unpublished captures remain readable. Publish every accepted new capture; once a skill/view has a publication pointer, the server never falls back to its mutable working directory. Reload an existing page once when adopting this publication mechanism.

## Case page (founder view)

`/case.html?id=<case>`: header (round / gate / dev sha vs capture sha with STALE badge / focus / fire hint), reference and ours (side, top, front; others behind a toggle) on one clock, beat table (from `data/<case>/beat-sheet/beat-sheet.md`), sound buttons, collapsed 作者向け section. The timeline/segment editor and Save were removed.

- `data/<case>/review.json`: `{round, gate (1|2), focus, fireHint, soundKind, reviewer:{<beat>:{v:"ok"|"ng", reason}}, founder:{...}}`. The founder column is written by `POST /api/cases/<id>/founder` (only the `founder` key).
- dev sha: env `SKILL_COMPARE_DEV_SHA` or `<published>/dev-deployed.json` `{sha, at}`.
- Sound: reference from `cache/videos/<videoId>.mp4`; current/previous from `<published>/<skill>/sound/{current,previous}.{ogg,mp3,wav}`.
- A capture whose meta has `skippedTicks > 0` is labelled 未確認 (欠落あり).

## Always in sync with dev (`make skill-compare-up`)

`scripts/skill-compare-follow.sh` (`make skill-compare-up|down|status`) serves the page from its own detached worktree
(`.claude/worktrees/skill-compare-serve`) and follows `.claude/state/dev-deploy/deploy.log`: on every `deployed <sha>` it runs
`git checkout -f --detach <sha>` there, restarts the server only if `tools/skill-compare` changed, and restarts it if it dies.
Nothing is done by hand; start it once (the old hand-started server on :8097 must be stopped first).

- **Live page**: `/api/events` (SSE) fires when a view is republished, the dev sha moves, or a skill's render state changes. The open case
  page refreshes data, swaps videos to the new revision (`?v=<revision>`) and keeps the current tick; the index re-lists. The green dot
  in the header is the live connection. A new server code revision reloads the page once (keeping `#t=`).
- **Honest freshness** (`lib/freshness.mjs`): the header shows `dev <sha7>`; every video shows its own content sha and age. A video is
  outdated only when *that skill's* content changed between the video's sha and dev (`tools/vfx-shot/changed-skills.py`, plus
  models/particles/items for the preview kinds), not on every push. Then: `生成中… (旧版を表示中)` while a render is queued/running
  (queued longer than 30 min is flagged stalled), `失敗` + reason when preview.log / farm.log record a failure after the deploy.

## Comparison API (the one standard; reviewers, directors, manager and the 比較 panel all use it)

Reviews are NOT hand-made crops or PIL contact sheets: those disagree between people (the same dash was measured 3.3 / 5.4 / 6.5 blocks by eye)
and a regression slipped through unnoticed. Everyone asks the server, so everyone sees the same frames and the same numbers.

- `GET /api/cases/<id>/compare?ticks=2,10,41,63&views=ref,compare,side,top,preview-side&rounds=current,prev` -> JSON.
  `ticks` (default: the beat sheet's contact ticks = tick 0 plus each hit at -2 / 0 / +2, max 12), `views` (`ref` = the original video, else any published view), `rounds`
  (`current`, `prev`, `prev2`, or a revision hex prefix such as `f3eb4c,ac4809`). Per tick and view: frame number + URL for every round,
  `metrics`, `delta` vs the next older round of that view, `flags`. `sources[]` gives revision, sha, capturedAt, freshness (stale / building /
  failed) and failure for every source. `regression: true` + `regressions[]` summarise the flags.
- `GET /api/cases/<id>/sheet.png?...` (same params) -> one labelled grid (rows = view x round, columns = ticks, red frame = regression). Default `zoom=fit` is a 288x162 downscaled OVERVIEW; for judging detail use `zoom=native`: every cell is the source pixels 1:1 (never resampled), optionally cropped with `crop=x0,y0,x1,y1` (fractions of the frame, same window on every row; `zoom=native` is implied) or `crop=auto` (a window around the caster + fx of each row). Cells are as large as the biggest crop, smaller sources are padded, never upscaled; a sheet over 16000 px is refused with the numbers instead of being shrunk. `sources[]` carries `nativeWidth/nativeHeight` (+ `crop.px`) per source, and the ref also `video` (codec, size, fps, kbps). Metrics stay downscaled.
- Alignment: MC frame = `tick*fps/20 + castFrame - firstTick*fps/20` (20 fps: frame N = plan tick N); reference frame = `floor((castMs + tick*50)*fps/1000)` where
  `castMs` includes the case's `mc.offset_ms`. Never re-derive it by hand.
- Metrics (`scripts/compare.py`, frames downscaled to 320 px): `fx_pct` = emissive pixels that are new vs the clip's median background (so sky/floor do
  not count), `mean_luma` / `peak_luma` (p99), `mover_h_pct` = vertical extent of what moved (informational only). A flag is raised when
  `fx_pct` / `mean_luma` / `peak_luma` fall more than 20% vs the previous round (floors ignore near-black noise). Example: bastion-greatsword-s3 `rounds=f3eb4c,ac4809`
  flags t41 (`fx_pct 2.37 -> 0.4`, the thrust hit burst is gone).
- Trace numbers: if `<published>/<skill>/<view>/<revision>/trace.log` exists (the farm saves the capture's server-log slice there, containing the
  `[vfx-trace] play|pos|spawn` lines of `ASTERA_VFX_TRACE`), `sources[].trace` carries `fxSpawnTicks`, `casterTravelBlocks` (first to last caster
  position), `casterMaxFromStartBlocks` and `finalGapBlocks` (caster to target at the last sample). Absent file = no `trace` key.
- The case page has a collapsible **比較** panel (the same sheet, regression list, links to the JSON / PNG).
- **Framing parity** (`framing[]` per source; the framing rule is `ratioToRef` within 0.5x-2x): the caster's on-screen height (1.8 blocks, feet to head) as % of the
  frame height. MC rows are **analytic**: the capture camera (`compare` = `intent.json` camera keys exactly as `tools/vfx-shot/compare_plan.py` plays them; `side`/`right`/`front`/
  `back`/`high`/`near` = vfx-shot `orbit_cam` with the case intent distance; fov from the capture meta) projected onto the caster position of the capture's `trace.log`
  (`[vfx-trace] pos` lines). Other views (`top`, `preview-*`) report an `error`. The ref row is **annotated**: `case.json` `ref_caster_height_pct: {"<tick>": pct}` (hair top to feet
  of the upright caster in the ref frame, read off a gridded frame; add one entry per camera cut and for the key ticks; a tick uses the nearest annotated tick within +-2, else none).
  `ratioToRef` = median of per-tick this/ref over the ticks where both exist (`ratioTicks`); `perTick` shows each value (`onScreen` = feet and head inside the frame). bastion-greatsword-s3
  is annotated. **Capture meta wins over reconstruction**: when a capture's manifest `meta.cameraKeys` is present (`[{tick, pos:[x,y,z] world eye, yaw, pitch, fov}]`, tick = cast-relative plan tick,
  the key in force at a tick is the last one with `tick <=` it; per-tick samples are fine) the height is projected from those keys and the trace caster's world position, and `framing[].source` says so.
  The old key-colour bbox (`caster_bbox_pct` per cell, `framing[].debugHueBboxPct`) is debug only: it swung 1.7-35% on r7 (fx share the hair hue) and is not a gate input.
- **Source quality** (audited 2026-10-27, all 10 reference videos): YouTube offers 1080p at most (no 1440p/4K, no 60 fps); the cache holds the best stream (VP9 "premium"
  ~3.3-4.0 Mbps for 8 videos; Svaaq9F7Ty8 only offers H.264 2.2 Mbps). Reference frames are lossless PNG at native size/fps. MC captures are far smaller:
  `side`/`top` 640x360, `input` 854x480, `compare`/`preview` 1280x720 (`RES=` in vfx-shot raises it), so judge MC detail with `zoom=native`.

## 統合タブ（founder の入口は http://192.168.11.5:8097）

全画面（case 詳細・アニメーション詳細も含む）に同じ日本語ナビゲーションを表示する。
`make skill-compare-up` が dev の detached worktree に追従する。旧ギャラリーの別サービス・Make target は削除済み。

### スキル比較 — `/`

武器一覧から `/case.html?id=<case>` へ。参照動画・Minecraft 撮影・beat sheet・レビューは既存の比較 API を共有する。

### アニメーション — `/anim`

モーション一覧・詳細・tick 付きレビュー。撮影は `make anim-shot ANIM=<id>`。上記 Animation review 節参照。

### スプライト — `/sprites`

全スプライトの animated card、ID/tag 検索、タグフィルタ、4×/8×、1–60 fps、背景・加算合成・一時停止。
Minecraft 表示は距離 3/5/10 blocks と縦 FOV 70°、alpha cutoff 0.1 の寸法近似（shader の実機評価は撮影で行う）。
詳細には全フレーム、スクラバー、原文 YAML、ノード中間 PNG。`content/textures/sources/sprites.yaml`、
`content/sprites/`、`content/textures/generated/sprites/` は**配信 checkout** から読む。
中間 PNG は checkout と primary の `build/`、実機シートは capture の検索先から読む。2 秒ごとに自動更新、変更画像は別 URL。
`GET /api/sprites/catalog` が共通データ。タブ間の検索・表示設定はブラウザ session 内で保持する。

### VFX サンプル — `/samples`

各 skill の演出 YAML、shader_effect ID、実クライアント side コンタクトシート、未撮影の明示。
`sample-*` で実機シートがない場合は「寸法近似」と明記した sprite sheet を表示する。
実機: `build/vfx-shot/<id>/side/sheet.png`、近似: `build/skill-preview/<id>/side/strip.png`。
14 sample の生成・shader の撮影コマンドは [sprite-graph](../../docs/contributing/vfx-team/sprite-graph.md) と
[shader-effects](../../docs/contributing/vfx-team/shader-effects.md)。

### 3D プレビュー — `/viewer`

`make skill-preview SKILL=<id>` / `tools/skill-viewer` の生成済み strip HTML・画像・metadata を閲覧する。
対象: `build/skill-preview/` と `tools/skill-viewer/build/`。HTML は同じタブ内に埋め込み、隣接画像も同じ配信元から取得する。headless 用の `file://` 参照は固定出力先に含まれるものだけ hub URL に変換する。
現行 skill-viewer は trace v2 の headless renderer。空の `src/preview2.html` は trace/config 注入が必要なので直接の閲覧対象にしない。

### HUD — `/hud`

`tools/hud-preview/build/hud-frames/<trace>/` の生成画像を選択して表示する。生成手順は
[HUD preview](../hud-preview/README.md)。HTTP から Gradle やシナリオは実行しない。

### アリーナ — `/arena`

`make arena-preview` の `build/arena-previews/`。top / iso / section / height PNG と report.md を閲覧する。

### モデル — `/models`

`tools/model-forge/{work,batches}/` と `build/model-forge/` のレビュー HTML・画像・JSON・文書。
`build-reviewer.mjs <data.json> <tools/model-forge/work/.../viewer.html>` の出力は HTML 選択で埋め込む。
生成コマンドやモデル制作手順は [model-forge](../model-forge/README.md) のまま。

### 撮影結果 — `/captures`

`build/vfx-shot/`、`build/skill-compare-published/`、`build/live-view/` の生成画像・動画・metadata を閲覧する。
`SKILL_COMPARE_VFX_SHOT` / `SKILL_COMPARE_PUBLISHED` の設定先も対象。`make vfx-shot` と publish は従来どおり。

### ライブ — `/live`

`make live-view SKILL=<id>` を起動すると実クライアント配信を埋め込む。音声・cast/recast・HUD・snapshot・clip をそのまま操作できる。
既定 backend は閲覧ホストの `:8098`（ブラウザで別 URL を開く必要はない）。異なるポート/ホストは hub サーバー環境変数
`SKILL_COMPARE_LIVE_URL=http://capture-host:port`。停止中や別アプリが動いている場合は停止表示と起動コマンドを示す。
配信は別プロセス・撮影 slot を使う。hub はプロセスの起動/停止を行わない。`make live-view` の直接配信も維持。

### 生成物の検索と検証

生成物タブは配信 checkout と primary checkout、および capture/preview 出力を持つ primary の worktree の上記固定ディレクトリを出力元として列挙する。`SKILL_COMPARE_WORKTREE_ROOT` で worktree 検索先を変更できる。
detached follow checkout に build がなくても primary の生成物を閲覧できる。出力元・ディレクトリを選択し、画像/動画/HTML/テキストを表示。
「再読み込み」で新しい生成物を再列挙する。未生成は出力先・生成コマンドを表示。スプライト/VFX は自動更新。追加 worktree 自体の検出はサーバー起動時なので、新規撮影 worktree を作った場合は hub を再起動する。

独立した空きポートで起動し、Chromium browser integration を実行する（共有 8097/8098 を停止しない）。

```sh
PORT=18197 HOST=127.0.0.1 bun tools/skill-compare/server.mjs
HUB_URL=http://127.0.0.1:18197 bun tools/skill-compare/scripts/gallery-browser.e2e.mjs
HUB_URL=http://127.0.0.1:18197 bun tools/skill-compare/scripts/samples-browser.e2e.mjs
HUB_URL=http://127.0.0.1:18197 bun tools/skill-compare/scripts/hub-browser.e2e.mjs
```

`PLAYWRIGHT_PACKAGE_ROOT` は既存 playwright-core の package root、`CHROMIUM_PATH` は Chromium の指定。
`REQUIRE_SAMPLE_CAPTURES=1` は全 sample の実機シート必須。hub-browser は生成物 fixture・HTML 隣接 asset・
パス境界・ライブ埋め込み/停止表示を検証し、全 10 タブを `/tmp/claude-1000/vfx-nodegraph/hub-<tab>.png` に撮影する。
Minecraft の live stack を起動する検証は manager の統合 window で行う。
