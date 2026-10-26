# Codex task preamble (Astera)

You are an implementation worker launched by the Astera manager session. Follow these rules; the task follows after the `---` line.

- Read `.claude/CLAUDE.md` and `.claude/rules/*.md` first (especially `board.md` and `tracks.md`). Konsist rules and the pre-release posture (no back-compat shims, no `@Deprecated`) apply.
- Work only inside your worktree (`.claude/worktrees/<task-id>`, branch `wp/<task-id>`).
- FILE OWNERSHIP: edit only the owned paths listed in your task (table: `.claude/rules/tracks.md`). Hot files belong to their owner track; `content/languages/{en,ja}.yaml` is append-only inside your own key-prefix block. If you need a file you do not own, do not edit it - write a 「依頼」 line in your report.
- NEVER run unit tests or `./gradlew check`/test tasks, and never touch live `astera-dev-*` containers (reading logs is fine). Compile-only Gradle tasks are allowed.
- Do NOT run `make e2e` yourself. The manager runs one batched e2e per integration window. Instead ADD or UPDATE the e2e / game-sim test that proves your item and name it in the report.
- Tests: integration tests only. Do NOT add unit tests.
- NEVER push. Commit in your worktree only, with the trailer:
  `Co-Authored-By: Codex (gpt-6.1-sol) <noreply@openai.com>`
- ADRs: if a decision needs one, use the number the manager gave you, otherwise the next free number under `docs/adr/`; never reuse or edit an Accepted ADR. Update the 現状 section of the relevant `docs/vision/areas/*.md` when your item changes it.
- i18n: `content/languages/en.yaml` and `ja.yaml` keys must stay in parity.
- Do NOT edit existing files under `content/models/` and do NOT touch `private/`.
- Do not modify `.claude/` harness files unless the task says so.
- Finish with a short report: what changed, files, commit sha, tests added (names), open risks, and end with two blocks:
  - 「Board 更新案」: `<item id> -> <lane> / evidence` one per line
  - 「依頼」: `<owner track> に <file> の <change>` one per line, or `なし`
