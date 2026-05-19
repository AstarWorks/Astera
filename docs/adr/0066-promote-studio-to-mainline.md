---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0066: Promote Studio + model-gen + build-model skill to mainline

## Status

Accepted

## Context

[[adr/0018-ai-asset-creation-policy]] set a "carve-out" for AI asset
creation tooling. Its decision text mentions only `tools/texture-gen/` by
name (Rule 4), but it was later extended through
`.claude/rules/ai-delegation.md` to a broader scope:

> **Carve-out (ADR-0018):** textures, 3D models, and the studio iteration
> tooling are pre-opened tracks. They live under explicitly-scoped paths:
> - `tools/texture-gen/` + `.claude/skills/generate-textures/`
> - `tools/studio/`, `tools/model-gen/`
> - `.claude/skills/build-model/`
> - `content/models/`, `content/textures/` (output sinks)
> - Top-level PNG iteration artifacts

In practice the rule was read as "all these paths are dev-only, not
tracked in git." The off-limits list in
`.claude/rules/wave-execution.md` reinforced that — normal Waves were
forbidden to touch them.

The situation is now untenable:

- `tools/studio/web/` has matured into a full Vite + Vue 3 + Hono + Bun +
  Three.js app with four route surfaces (`/textures`, `/models`,
  `/effects`, `/weapons`), an ASDSL DSL state machine
  ([[adr/0062-asdsl-v2-schema-consolidation]]), per-iteration screenshot
  cache + crop tools (B9), and a Konsist-friendly TS test suite (59
  passing tests as of 2026-05-19). **It is no longer a sketch.**
- `.claude/skills/build-model/SKILL.md` is the canonical 450-line skill
  driving the LLM iteration loop that produced every sample model in
  `tools/model-gen/`. It's a first-class deliverable, not a scratch file.
- [[adr/0061-studio-weapons-preview-route]] was authored as `Accepted` and
  tracked in git, **implicitly assuming the studio tree is mainline**.
  Without this ADR, ADR-0061 references an off-git surface — a
  contradiction Konsist can't catch but every contributor will trip on.
- [[adr/0062-asdsl-v2-schema-consolidation]] (B6–B11 ASDSL v2) has
  shipped as code but is similarly stranded — the ADR sits untracked next
  to working implementations that nothing in main can see.

The original ADR-0018 invariant — **no generative AI in runtime, no
generative AI in the build pipeline** — is NOT violated by promoting
studio. The studio is a separate Node.js process talking to the live dev
server via HTTP/WS; it is not in the Gradle build graph and not loaded by
any `:plugin:*` runtime module. `./gradlew check` does not invoke any
studio code, no Anthropic SDK call, no Three.js render.

What ADR-0018 actually meant — and the part that should survive — is the
texture-side policy: Pixel Composer, Codex CLI, spritecook.ai, and
`content/textures/` outputs. That stays exactly as written.

The error was treating the studio as a sibling of texture-gen. They are
different tracks: texture-gen is a 1-shot image generator scaffolding;
studio is an LLM-collaboration IDE for content authoring. Bundling them
under one carve-out has produced more friction than the carve-out has
prevented.

## Decision

Promote the following paths to **first-class mainline tracked status**:

| Path | Role |
|---|---|
| `tools/studio/web/` (excluding `node_modules/`, `.iter/`, `.vanilla-cache/`, `dist/`, `.vite/`, `.bun-cache/`) | Studio app source + tests + lockfile |
| `tools/model-gen/` | ASDSL sample models, benchmark datasets, templates, PLAN.md |
| `.claude/skills/build-model/SKILL.md` | The LLM-iteration skill orchestrating studio + ASDSL |
| `content/models/` | The studio's output sink (committed model JSON, BBModels) |
| `docs/adr/0017-adopt-datadriven-contents-pattern.md` | Already `Accepted` but stranded; track it here |
| `docs/adr/0062-asdsl-v2-schema-consolidation.md` | Already `Proposed`; track it here |
| `docs/adr/0066-…` (this file) | The decision itself |

[[adr/0018-ai-asset-creation-policy]] is **not** superseded. Its scope is
narrowed to its literal Decision text — Rule 4's `tools/texture-gen/`
permission, plus the runtime/build invariants from Rules 2 and 3. The
broader interpretation in `.claude/rules/ai-delegation.md` is rewritten
to match.

[[adr/0010-postpone-generative-ai-to-phase-6]] is also not superseded.
The studio uses the Anthropic SDK in dev sessions, but no runtime or
build module loads it; the invariant holds.

Off-limits paths in `.claude/rules/wave-execution.md` are narrowed to:

- `tools/texture-gen/` and `.claude/skills/generate-textures/`
- `.playwright-mcp/`
- Top-level iteration PNG artifacts (now gitignored — see Mitigations)
- ADR-0018 itself remains untouched per ADR immutability

`tools/studio/`, `tools/model-gen/`, `.claude/skills/build-model/`, and
`content/models/` are removed from the off-limits list. Normal Waves may
touch them — but **they have their own coordination**: studio changes
should not regress its 59 TS tests, model-gen content should be linted
against ASDSL v2 grammar, etc.

## Consequences

### Positive

- ADR-0061 (weapons preview) and ADR-0062 (ASDSL v2) become coherent in
  the repo — they reference files that exist in main, not in
  working-tree limbo.
- B6–B11 (ADR-0062) lands as proper history with one commit instead of
  remaining a permanent untracked diff every contributor inherits.
- The LLM-iteration loop documented in `build-model/SKILL.md` is now
  reproducible by reading the repo, not by digging through session
  transcripts.
- Konsist gains a future enforcement target: forbid `:plugin:*` runtime
  modules from importing anything under `tools/studio/web/server/` (none
  do today; the rule prevents accidents).
- Future studio improvements have a clear landing target — they go on
  feature branches like any other code, not into a per-session
  scratchpad.

### Negative / Trade-offs

- Repo size grows: ~50 source files in studio/web, ~12 in model-gen.
  Lockfile (`bun.lock`) is ~10 KB. None are large.
- `bun.lock` becomes a tracked artifact, requiring discipline on
  `bun install` (same convention as any pinned-deps project).
- Contributors editing studio code now share the responsibility of
  keeping `bun run typecheck` and `bun test` green. Previously this was
  per-session discipline; now it's per-commit.

### Mitigations

- `.gitignore` covers: `node_modules/`, `.iter/`, `.vanilla-cache/`,
  `dist/`, `.vite/`, `.bun-cache/`, top-level PNG iteration artifacts
  (`/*.png` removed from working tree + ignored).
- A future Konsist rule will forbid `:plugin:*` source files from
  containing the string `tools/studio/` or importing TS files via any
  cross-tool bridge. (No bridges exist today.)
- A future `make check` extension can call `cd tools/studio/web && bun
  run typecheck && bun test` — deferred to a follow-up ADR that scopes
  CI integration properly.
- The texture-gen track and `.claude/skills/generate-textures/` stay
  exactly as ADR-0018 wrote them, no policy change.

## Alternatives Considered

- **Keep studio off-git per current rules.** Rejected. The work has
  outgrown the carve-out. Every session re-introduces the same
  untracked diff; the ADRs that depend on it (0061, 0062) are
  broken-by-construction.
- **Fully supersede ADR-0018.** Rejected. ADR-0018's actual Decision
  text (texture-gen + the runtime/build invariants) is correct and
  load-bearing. Replacing it would invite confusion about whether
  ML libraries can sneak into `:plugin:*`. Narrow the scope, don't
  remove the rule.
- **Promote everything (texture-gen too).** Rejected. Texture-gen is a
  different track with its own deferred-to-Phase-6 policy via
  [[adr/0010-postpone-generative-ai-to-phase-6]]. Promoting it requires
  a separate decision and isn't blocking anything today.
- **Promote only `tools/studio/web/`, leave model-gen + skill alone.**
  Rejected. The build-model skill exists exactly to drive the studio;
  model-gen is its sample data. They form one pipeline. Splitting them
  forces a future ADR to land what should land together.

## References

- [[adr/0010-postpone-generative-ai-to-phase-6]] — runtime / build
  invariant; preserved
- [[adr/0018-ai-asset-creation-policy]] — texture-gen carve-out;
  scope narrowed, not superseded
- [[adr/0017-adopt-datadriven-contents-pattern]] — tracked by this
  ADR's housekeeping commit
- [[adr/0061-studio-weapons-preview-route]] — already tracked, but
  presumed mainline studio; this ADR formalizes
- [[adr/0062-asdsl-v2-schema-consolidation]] — tracked by this
  ADR's housekeeping commit
- `.claude/rules/wave-execution.md` — off-limits list narrowed
- `.claude/rules/ai-delegation.md` — carve-out paragraph rewritten
