# Wave-execution protocol

Session protocol for Wave-style implementation in Astera. **Not enforced by
CI** — violating these has burned past sessions, so treat them as durable.

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

## Phase A / B / C

- **Phase A — orchestrator solo.** Dep prep, port skeletons, `mkdir` for new
  directories so workers don't race, TaskCreate for the wave.
- **Phase B — workers in parallel.** Spawn one `Agent` per worker-territory
  row in the handoff doc. Each worker gets its own task list + scope.
- **Phase C — orchestrator solo.** Wire plugins
  (`plugin/<module>/src/main/kotlin/.../platform/Astera*Plugin.kt`), write the
  ADR, run `./gradlew check`, update `docs/RTM-PARITY-TRACKER.md` (use
  `rtm-sync` subagent), single commit.

## Single-commit-per-wave + user-pushes

- One commit per wave (unless the handoff doc says split — e.g., Wave 6 may
  split 6a + 6b).
- **Claude never pushes.** The user reviews and pushes manually.
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

Asset / studio tracks own these. Touching them outside their own waves causes
churn and merge friction:

- `tools/texture-gen/` and `.claude/skills/generate-textures/`
- `tools/studio/`, `tools/model-gen/`, `.claude/skills/build-model/`
- `content/models/`
- `.playwright-mcp/`
- Top-level PNG screenshots: `studio-*.png`, `amethyst-*.png`, `wave2-*.png`,
  `wave3-*.png`
- `docs/adr/0017-adopt-datadriven-contents-pattern.md`,
  `docs/adr/0018-ai-asset-creation-policy.md` — separate landing track

If a wave's handoff doc explicitly owns one of these, follow the handoff.
