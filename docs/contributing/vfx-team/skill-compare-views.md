# skill-compare review views

Founder rule (2026-10-26): skills are reviewed and accepted ONLY on the skill-compare page (`make skill-compare`, `/case.html?id=<case>`). Everything produced for a skill must appear there, current and labelled. All views live under the published root (`SKILL_COMPARE_PUBLISHED`, default `<primary checkout>/build/skill-compare-published`) at `<skill>/<kind>/<revision>/` plus an atomic `current.json` (same scheme as `publish-capture.mjs`: an open page keeps its revision, a failed publish keeps the old pointer).

| View | Produced by | Published by | Shown as |
|---|---|---|---|
| real client capture | `tools/vfx-shot` | `scripts/publish-capture.mjs` | "In-game" column |
| preview | `make skill-preview SKILL=<s>` | `scripts/publish-preview.mjs` (called by the make target) | middle "Preview" column, tick-synced with reference and capture; banner "PREVIEW - not the real client" |
| lint | `make skill-lint SKILL=<s>` writes `build/skill-lint/<s>/report.json` (terminal output and exit code unchanged) | `scripts/publish-lint.mjs` (called by the make target) | lint panel under the columns (PASS/FAIL/SKIP per check with numbers, click a row to seek), plus per-tick "lint @ t+N" chips under the preview |
| overlay | lint check 1b / `make skill-overlay` -> `build/skill-lint/<s>/overlay/<beat>.png` | `publish-lint.mjs` | shown over the reference column on the beat's tick, toggle "注釈オーバーレイ" |
| round notes | optional `tools/skill-compare/data/<case>/round.md` (or any file) | `make skill-round SKILL=<s> [FILE=..]` / `scripts/publish-round.mjs` | box at the top of the page |

`PUBLISH=0` on `skill-preview` / `skill-lint` / `skill-overlay` skips publishing.

## Provenance and STALE

Every view shows branch, git revision, content revision, dirty flag (content/code) and creation time. The server compares the view's `contentRevision` (`git rev-parse HEAD:content` plus `+dirty-<hash>` of uncommitted content files; same fingerprint as vfx-shot and skill-lint) with the tree it runs from and shows a red **STALE** chip on mismatch, "最新" on match, "鮮度不明" when the view recorded none. Previews are stamped by `skill-preview` (`meta.json` `provenance`); a preview without it is refused by the publisher. The server must be started from the checkout whose content you review.

## Round notes

`round.md`: short, written by the director each round: what changed, open questions for the founder. Plain text (shown verbatim, max 20 kB). Skipped silently when absent.
