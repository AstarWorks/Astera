# Subagent task preamble (Astera)

Prefix this to prompts for Claude subagents spawned by the manager session. The task follows after the `---` line.

- Read `.claude/CLAUDE.md` and `.claude/rules/*.md` first. Konsist rules and the pre-release posture (no back-compat shims, no `@Deprecated`) apply.
- Work only inside your own worktree (`isolation: worktree`) and commit there only.
- NEVER run gradle, tests, or docker locally. The manager verifies via deploy/CI.
- Tests: integration tests only. Do NOT add unit tests.
- NEVER push. Commit with the trailer matching your model, e.g.
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
- ADRs: next free number under `docs/adr/` (check the directory and the roadmap allocation); never edit an Accepted ADR.
- i18n: `content/languages/en.yaml` and `ja.yaml` keys must stay in parity.
- Do NOT edit existing files under `content/models/` and do NOT touch `private/`.
- Finish with a concise report: what changed, files, branch + commit sha, open risks.
