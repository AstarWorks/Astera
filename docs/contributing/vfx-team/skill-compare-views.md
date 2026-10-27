# skill-compare review views

Founder rule (2026-10-26): skills are reviewed and accepted ONLY on the skill-compare page (`make skill-compare`, `/case.html?id=<case>`). Everything produced for a skill must appear there, current and labelled. All views live under the published root (`SKILL_COMPARE_PUBLISHED`, default `<primary checkout>/build/skill-compare-published`) at `<skill>/<kind>/<revision>/` plus an atomic `current.json` (same scheme as `publish-capture.mjs`: an open page keeps its revision, a failed publish keeps the old pointer).

| View | Produced by | Published by | Shown as |
|---|---|---|---|
| real client capture | `tools/vfx-shot` (`VIEW=` compare, input, caster, opponent, side, right, front, back, high, near) | `scripts/publish-capture.mjs` | "In-game" column (source menu: one entry per published view) |
| preview | `make skill-preview SKILL=<s>` | `scripts/publish-preview.mjs` (called by the make target) | middle "Preview" column, tick-synced with reference and capture; banner "PREVIEW - not the real client" |
| lint | `make skill-lint SKILL=<s>` writes `build/skill-lint/<s>/report.json` (terminal output and exit code unchanged) | `scripts/publish-lint.mjs` (called by the make target) | lint panel under the columns (PASS/FAIL/SKIP per check with numbers, click a row to seek), plus per-tick "lint @ t+N" chips under the preview |
| overlay | lint check 1b / `make skill-overlay` -> `build/skill-lint/<s>/overlay/<beat>.png` | `publish-lint.mjs` | shown over the reference column on the beat's tick, toggle "注釈オーバーレイ" |
| round notes | optional `tools/skill-compare/data/<case>/round.md` (or any file) | `make skill-round SKILL=<s> [FILE=..]` / `scripts/publish-round.mjs` | box at the top of the page |

`PUBLISH=0` on `skill-preview` / `skill-lint` / `skill-overlay` skips publishing.

## Provenance and STALE

Every view shows branch, git revision, content revision, dirty flag (content/code) and creation time. The server compares the view's `contentRevision` (`git rev-parse HEAD:content` plus `+dirty-<hash>` of uncommitted content files; same fingerprint as vfx-shot and skill-lint) with the tree it runs from and shows a red **STALE** chip on mismatch, "最新" on match, "鮮度不明" when the view recorded none. Previews are stamped by `skill-preview` (`meta.json` `provenance`); a preview without it is refused by the publisher. The server must be started from the checkout whose content you review.

## Round notes

`round.md`: short, written by the director each round: what changed, open questions for the founder. Markdown, rendered on the page: headings, paragraphs, lists, GFM tables, quotes, code, **bold** / *italic*, `http(s)` links. Raw HTML is never interpreted (shown as text). Max 20,000 characters. Skipped silently when absent.

Images: `![top-down](beat-top.png)` with a path relative to the notes file, e.g. `make skill-round SKILL=<s> FILE=notes/beat-sheet.md` next to `notes/beat-top.png`, `beat-side.png`, `beat-front.png`. Rules (the publisher fails the whole publish on a violation):

- `.png` / `.jpg` / `.jpeg` / `.webp` only (content is checked, no SVG), at most 2 MB per image and 12 images per round.
- The file must live inside the notes file's directory (or below it): no `..`, absolute paths, URLs or symlinks leaving it.
- Images are copied into the immutable published revision and served from `/round/<skill>/<revision>/img/<name>`; the page only shows images the publisher listed. Rendering happens once when the case loads, so playback never repaints the notes.

## Capture completeness (every tick has its own frame)

A real-client capture (`tools/vfx-shot`) must contain, for every tick of the requested range, a frame that the client drew for THAT tick (read from the tick stamp rendered into the frame). Rules, enforced in code:

- Filming is always stepped (slow motion, `/tick rate` 5 by default). `STEPPED=0` / `FPS!=20` (realtime) needs `SHOT_REALTIME=1` and the result can never be published (`captureQuality.invalid`).
- A tick without a frame of its own (`skippedTicks > 0`, `meta.tickStamp.gaps`) makes `vfx-shot.sh` refilm the whole take automatically at half the tick rate (`SHOT_TICK_ATTEMPTS`, default 4). After the last attempt it fails loudly (exit != 0, frames removed). Nothing is published.
- `publish-capture.mjs` refuses a capture that is `captureQuality.invalid`, not stepped, or has `skippedTicks > 0`. There is no preview/realtime publish path.
- Cause of a miss: the software-GL client occasionally hitches longer than one slow tick (heavy VFX, first-time assets, host load) and then jumps over a tick; a realtime take (~10 fps client vs 20 tps server) misses 20-50 ticks by construction.
