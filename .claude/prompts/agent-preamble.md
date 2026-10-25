# Subagent task preamble (Astera)

Prefix this to prompts for Claude subagents spawned by the manager session. The task follows after the `---` line.

- Read `.claude/CLAUDE.md` and `.claude/rules/*.md` first (especially `board.md` and `tracks.md`). Konsist rules and the pre-release posture (no back-compat shims, no `@Deprecated`) apply.
- Work only inside your own worktree (`isolation: worktree`) and commit there only.
- FILE OWNERSHIP: edit only the owned paths listed in your task (table: `.claude/rules/tracks.md`). Hot files belong to their owner track; `content/languages/{en,ja}.yaml` is append-only inside your own key-prefix block. If you need a file you do not own, do not edit it - write a 「依頼」 line in your report.
- NEVER run unit tests or `./gradlew check`/test tasks, and never touch live `astera-dev-*` containers (reading logs is fine). Compile-only Gradle tasks are allowed.
- Do NOT run `make e2e` yourself. The manager runs one batched e2e per integration window (12:00 / 21:00 JST). Instead ADD or UPDATE the e2e / game-sim test that proves your item and name it in the report.
- Tests: integration tests only. Do NOT add unit tests.
- NEVER push. Commit with the trailer matching your model, e.g.
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
- ADRs: only if the task says so; use the number the manager gave you, otherwise the next free number under `docs/adr/`. Never edit an Accepted ADR. Agents DO update the 現状 section of the relevant `docs/vision/areas/*.md` when their item changes it.
- i18n: `content/languages/en.yaml` and `ja.yaml` keys must stay in parity.
- Do NOT edit existing files under `content/models/` and do NOT touch `private/`.
- Finish with a concise report: what changed, files, branch + commit sha, tests added (names), open risks, and end with two blocks:
  - 「Board 更新案」: `<item id> -> <lane> / evidence (sha, test name, report path)` one per line
  - 「依頼」: `<owner track> に <file> の <change>` one per line, or `なし`
