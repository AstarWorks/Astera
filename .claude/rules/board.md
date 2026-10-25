# Board (single source of truth for "what is next / what is live")

Founder-approved 2026-10-26 (ADR-0255). The Board replaces the RTM tracker, the
roadmaps and the core-vision backlog. Three layers, one job each:

| Layer | Where | Answers | Mutability |
|---|---|---|---|
| ADR | `docs/adr/` | why we decided | immutable (supersede only) |
| Area spec | `docs/vision/areas/*.md` (+ `docs/vision/VISION.md`) | how it is today and the ideal form | living, edited with the code |
| Board item | the Board | what we do next and how far it got | living, manager-only writes |

## Where it lives

claude.ai Artifact: https://claude.ai/artifact/2D2a8S41RpDwApdZFnPno3
Shared database via the `ArtifactData` tool (load it with `ToolSearch select:ArtifactData`), collections:

- `items` - one document per work item (schema below).
- `memos` - founder notes typed on the page (`{id, text, created, status: new|read|done, answer?}`).

GitHub Issues/Projects are NOT used (founder: no GitHub workflow, no CI).

## Single writer

Only the manager session writes the Board (`ArtifactData set|update|batch`). Agents and Codex read at most;
they end their report with a 「Board 更新案」 block (item id -> lane/evidence) and the manager applies it.
The founder writes through the page: `rank` on items and the memo box; chat and page comments also count.

## Rules for the manager

1. **Write the Board before delegating.** Any turn that contains founder feedback, a decision or a new
   request: first create/update the item (or append to its `why`, quoting the founder with the date), then
   spawn agents. A founder remark that is not on the Board does not exist.
2. **Session start:** read `memos` (status `new`) and items in `founder-check` / `blocked`; answer or act,
   mark memos `read`/`done` with an `answer`.
3. A rejection from the founder goes into the item's `why` (never silently overwritten) and the lane
   moves back (`in-progress` or `ready`).
4. An item is `ready` only when `verification` is fixed; cross-cutting decisions also need an ADR number
   reserved (the manager takes the next free number in `docs/adr/` and writes it into `adr`).
   ADR numbers are allocated by the manager before work starts. Tracks never pick a number themselves.
5. Never mark `verified` without evidence for every declared verification stage. `live` without evidence is
   reported as "unverified" in every batch report until fixed.
6. After each integration batch, dump `ArtifactData list` to the scratchpad and let a Haiku scribe commit
   it as `docs/board/SNAPSHOT.md` (read-only history; the Board stays authoritative).

## Item schema (1 item = 1 document, id = `<AREA>-<NN>`)

| field | meaning |
|---|---|
| id | prefix + number: `UI` `MODE` `ECO` `FX` `MAP` `SKL` `BOT` `OPS` |
| area | ui-access / modes-combat / economy-life / presentation-sound / builds-maps / skills-vfx / bots-qa / ops-harness |
| title, problem | problem = what the player sees that hurts, in one line |
| why | founder's words, dated; link to the area-spec section |
| priority, rank | S / A / B; `rank` is an integer only the founder edits |
| lane | see below |
| track, owner | `wp/<slug>` and Sonnet / Haiku / Codex / founder |
| verification | stages to reach: `boot`, `e2e:<TestClass#case>`, `sim:<scenario>`, `shot:<make vfx-shot ...>`, `founder:<where to stand, what to press, what to see>` |
| evidence | sha, `build/e2e-report.md` path, `deploy.log` line, asset URL |
| files, adr, deps, updated | main files touched (conflict detection), ADR numbers, dependent item ids, date |

## Lanes

`idea -> ready -> in-progress -> merged -> live -> founder-check -> verified`, plus `blocked` (needs a
founder decision; put the question and a recommended answer in the item) and `dropped`.

- `merged`: on the integration branch, not pushed. `live`: pushed to `main` and autodeploy shows the sha.
- `boot` alone is never done. Bot-judgeable behaviour needs `e2e:`/`sim:`; looks need `shot:`;
  only feel/look/sound that bots cannot judge uses `founder:`.

## Batch report (one per integration window, Japanese, at most 10 lines, fixed headings)

```
## バッチ <M/D AM|PM>  main=<sha> live=<deploy.log time>
反映: <ID title> / ...                       (one line each, with evidence)
見てほしい(founder-check): <ID where/what to do>
決めてほしい(blocked): <ID question> -> 推奨: <answer>
進行中: <track item> / ...
次バッチ: <IDs>
Board: https://claude.ai/artifact/2D2a8S41RpDwApdZFnPno3
```

A one-line founder reply in chat is applied to the Board in that same turn, before the next delegation.
