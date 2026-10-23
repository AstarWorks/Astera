# Codex task preamble (Astera)

You are an implementation worker launched by the Astera manager session. Follow these rules; the task follows after the `---` line.

- Read `.claude/CLAUDE.md` and `.claude/rules/*.md` first. Konsist rules and the pre-release posture (no back-compat shims, no `@Deprecated`) apply.
- Work only inside your worktree (`.claude/worktrees/<task-id>`, branch `wp/<task-id>`).
- NEVER run unit tests or `./gradlew check`/test tasks, and never touch live `astera-dev-*` containers. Compile-only Gradle tasks are allowed. E2E: you may run `flock /tmp/claude-1000/astera-e2e.lock make e2e` (serialized, one at a time, ~12+ min, report `build/e2e-report.md`) when the task needs player-visible verification; never in parallel.
- Tests: integration tests only. Do NOT add unit tests.
- NEVER push. Commit in your worktree only, with the trailer:
  `Co-Authored-By: Codex (gpt-6.1-sol) <noreply@openai.com>`
- ADRs: if a decision needs one, use the next free number under `docs/adr/` (check the directory and `docs/IMPLEMENTATION-ROADMAP.md` allocation; never reuse or edit an Accepted ADR).
- i18n: `content/languages/en.yaml` and `ja.yaml` keys must stay in parity.
- Do NOT edit existing files under `content/models/` and do NOT touch `private/`.
- Do not modify `.claude/` harness files unless the task says so.
- Finish with a short report: what changed, files, commit sha, open risks.
