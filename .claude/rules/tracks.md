# Tracks, file ownership and integration windows

Founder-approved 2026-10-26 (ADR-0266). Track = one area of the game, worktree = track, one session per
track (`/IdeaProjects/Astera/.claude/worktrees/<slug>`, branch `wp/<slug>`; see `.claude/CLAUDE.md`).
Wave vocabulary is retired; say "track" and "integration window".

## Ownership table

A track edits only what it owns. Hot files are edited by their owner only; everybody else asks.

| track | owns | hot files (owner-only) |
|---|---|---|
| `wp/ui-access` | `content/gui`, `content/lobby-npcs`, `content/lobby-boards`, `content/tutorials`, core gui/hotbar/onboarding code | `content/gui/main-menu.yaml`, `content/gui/kit/lobby-kit.yaml` |
| `wp/modes-combat` | `content/game-modes`, `content/matchmaking`, `content/generators`, stage placement values in `content/stages/*.yaml`, `plugin/match`, combat rules in `plugin/combat` | `content/game-modes/norma.yaml`, `content/matchmaking/queue.yaml` |
| `wp/economy-life` | `content/{shops,balance,progression,achievements,login-bonus,norma,quests,pve,gathering,recipes}` (norma = missions), `plugin/economy`, `plugin/stats` | `content/balance/starter-grant.yaml`, `content/balance/match-rewards.yaml` |
| `wp/presentation` | match/lobby moments and sound: `content/{sounds,music}`, HUD moments, the Paper `IMusicGateway`, `PaperNormaPresentation` | `content/hud/*` |
| `wp/skills-vfx` | Limbus track: `content/{vfx,animations,particles,sprites,models}`, per-skill VFX/sound/model | `content/skills/*.yaml` |
| `wp/builds-maps` | `ArenaIslandGenerator`, `LobbyIslandGenerator`, `content/worlds`, `tools/lobby-gen`; the theme/structure section of `content/stages/*.yaml` | `content/stages/*.yaml` (shared with modes: builds touches theme/structures only) |
| `wp/bots-qa` | `tools/bot-runner`, `tools/e2e` harness and shared tests, `*PlaythroughE2eTest` | each area writes its own `<Area>*E2eTest.kt`; only the harness is qa's |
| `wp/ops-harness` | `.claude/rules`, `.claude/prompts`, `.claude/agents`, `.claude/CLAUDE.md`, `docs/vision`, `docs/INDEX.md`, `docs/board`, ADR numbering | - |
| `wp/integrate` (manager) | `platform/Astera*Plugin.kt` wiring, merges, e2e window, push | `content/languages/{en,ja}.yaml` |

Shared append-only files: `content/languages/{en,ja}.yaml` - append inside your own key-prefix block, never
reorder or rewrite another block; `platform/Astera*Plugin.kt` wiring - minimal additive lines.

Need a file you do not own? Do not edit it. Put a line in your report:
`依頼: <ID or new item> - <owner track> に <file> の <change> を含める`. The manager turns it into a Board
item for the owner. Codex and subagent briefs paste the owner's row of this table.

## Integration windows

- Two windows per day, 12:00 and 21:00 JST (a third on request). Tracks do not run e2e.
- At a window the manager merges every track that is merge-ready (compiles, its own e2e/sim tests added and
  named in the report, report received) into `wp/integrate`, then a QA subagent runs ONE
  `flock /tmp/claude-1000/astera-e2e.lock make e2e` (about 12 minutes, report `build/e2e-report.md`).
- Green: `git push origin HEAD:main` (never force), autodeploy picks up `origin/main`, the `deploy.log` sha is
  the evidence, Board lanes move to `live` in one batch, then the batch report goes out.
- Red: drop the owning track from the batch (do not revert) and re-run; one run is cheaper than debugging.
- A single-track e2e between windows is allowed only for an S-priority bot-judgeable item, with the manager's
  OK and a Board `e2e-request`, queued behind the lock.
- Manual deploys (docker/compose changes) go through `/tmp/claude-1000/astera-dev-deploy.lock` and
  `mark-deployed`. Never bypass the lock.
- Sunday: a Haiku agent removes merged `agent-*` / `codex-*` worktrees; worktrees without a handoff are
  deletion candidates.

## Area spec upkeep

When an item reaches `verified`, the agent that implemented it updates the 現状 section of its area spec
(`docs/vision/areas/<area>.md`) in the same branch (`area-spec-sync` agent for bulk edits). Agents do not
write ADRs unless the brief says so; the manager reserves numbers.
