# Wave-execution protocol

Session protocol for Wave-style implementation in Astera. **Not enforced by
CI** — violating these has burned past sessions, so treat them as durable.

## Every track → its own worktree

Several Claude sessions run at once. **Two sessions sharing one working tree
produce build breaks, commit scope bleed and blocked merges** (Wave 5b
`078f6e1`: a parallel 5c session's half-written `plugin/match/` files broke
`check` and stowed away into 5b's commit; 2026-10-24: a dirty primary
checkout blocked merges for every other session, and a single shared
handoff file let one session wipe another's baton).

**Rule**: every work track — a wave, a fix round, an asset rollout, anything
that commits — runs in its own worktree from the start, single-stream or not.
Slug = stable kebab-case track name (e.g. `vanilla-material`):

```
git -C /IdeaProjects/Astera fetch origin
git -C /IdeaProjects/Astera worktree add .claude/worktrees/<slug> -b wp/<slug> origin/main
cd /IdeaProjects/Astera/.claude/worktrees/<slug>   # launch / work here
```

- Merge agent/Codex branches into `wp/<slug>` inside that worktree; ship with
  `git fetch origin && git merge origin/main && git push origin HEAD:main`.
- Never commit, merge, stash or check out in the primary checkout
  `/IdeaProjects/Astera`; treat its working tree as someone else's.
- Codex (`scripts/codex-task.sh`, run from the track worktree) and
  `isolation: worktree` agents branch off the track's HEAD.
- Baton: `/IdeaProjects/Astera/.claude/state/handoffs/<slug>.md`, one file per
  track, written only by that track (`.claude/CLAUDE.md` §"Session handoff").
  Track done ⇒ remove its worktree and delete its handoff file.

Why: Claude sessions have no in-band signaling, no shared mental model,
and no merge tooling between them — so the cost of conflict is higher
than the human-human parallel-edit case. Isolation by default at the
filesystem layer is cheaper than reconciling at commit time.

## Read order on a `Wave X` prompt

When the user says `Wave 5b を実装して` (or similar), read these in this order
**before spawning any worker**:

1. **`/home/node/.claude/plans/home-node-claude-plans-radius-roadmap-handoff.md`**
   — session entry point. Lists every wave's trigger phrase + per-wave handoff
   doc.
2. **`/home/node/.claude/plans/wave-{N}{x}-handoff.md`** — execution detail
   for *this* wave: Phase A→B→C plan, worker territory, scope, completion
   criteria, ADR number.
3. **`docs/IMPLEMENTATION-ROADMAP.md`** — cross-reference the wave's row +
   cross-wave dependencies. SSOT for ordering.
4. **`docs/RTM-PARITY-TRACKER.md`** — feature × state × wave-assignment.
   SSOT for "what's left".

## Verification is CI, not local

Agents verify via CI on `wp/*` branches; never run tests (`./gradlew`, `make e2e`, docker) locally.
Push the branch and fetch results with `gh run watch` / `gh run view --log-failed` / `gh run download`;
details in `docs/contributing/ci.md`.

## Phase A / B / C

- **Phase A — orchestrator solo.** Dep prep, port skeletons, `mkdir` for new
  directories so workers don't race, TaskCreate for the wave.
- **Phase B — workers in parallel.** Spawn one `Agent` per worker-territory
  row in the handoff doc. Each worker gets its own task list + scope.
  Workers run as `subagent_type: astera-worker` (**Sonnet**); purely
  mechanical territories (bulk delete / rename / YAML rewrite) may pass
  `model: haiku`. Opus stays orchestrator-only (Phase A/C: planning,
  wiring, ADR, check, commit) — do not implement Phase B territory on Opus.
- **Phase C — orchestrator solo.** Wire plugins
  (`plugin/<module>/src/main/kotlin/.../platform/Astera*Plugin.kt`), write the
  ADR, run `./gradlew check`, update `docs/RTM-PARITY-TRACKER.md` (use
  `rtm-sync` subagent), single commit.

## Single-commit-per-wave + orchestrator pushes

- One commit per wave (unless the handoff doc says split — e.g., Wave 6 may
  split 6a + 6b).
- **Claude pushes `main` itself** after `./gradlew check` is green (founder
  authorization 2026-10-22; no per-push approval). Never force-push, never
  rewrite pushed history. Phase B workers / subagents still do not push.
- **Unit tests are forbidden** (founder 2026-10-23). Workers write no unit tests; coverage = game-sim scenario / e2e / `*IT`. `TestPolicyTest` (Konsist) enforces it. See `docs/contributing/testing-policy.md`.
- **Konsist must stay green.** Run `./gradlew :tools:architecture-test:test`
  (or `make check`) before commit. New Konsist rules may be added; existing
  rules must not be relaxed.

## Worker禁則 (Phase B workers MUST NOT)

- Run `./gradlew` — gradle daemon contention with the orchestrator. Workers
  rely on the orchestrator's compile feedback at Phase C.
- Touch another worker's conflict region. Phase A creates new dirs so each
  worker has a clean territory.
- Modify `platform/Astera*Plugin.kt` wiring — wiring is Phase C / orchestrator
  only.
- Write or edit ADRs — Phase C / orchestrator only (use the `adr-new` skill).
- Run `git commit` / `git push` / `git branch` / `git checkout`.
- Modify `docs/RTM-PARITY-TRACKER.md` — Phase C, via the `rtm-sync` subagent.

## ADR numbers are pre-allocated

`docs/IMPLEMENTATION-ROADMAP.md §"ADR allocation per wave"` lists the ADR
number reserved for each wave. **Use that number**; do not pick a fresh one.
Use the `adr-new` skill to scaffold.

## Off-limits paths (do not touch in normal waves)

- `.playwright-mcp/` — ephemeral browser-automation state.
- `private/` — submodule with env-specific overlays.

Studio, texture-gen, model-gen, and their `.claude/skills/` (build-model,
generate-textures) were extracted to a separate repo in 2026-05; see
[[adr/0079-extract-studio-to-own-repo]]. They no longer live in this repo.
Astera content (`content/textures/`, `content/models/`, `content/effects/`)
still lives here but is authored from AsteraStudio (sibling clone).

Top-level iteration PNGs (`studio-*.png`, `amethyst-*.png`, `wave?-*.png`)
are gitignored — never commit them.
