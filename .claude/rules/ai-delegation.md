# AI delegation policy (Phase 1)

## Code, tests, docs

- **Code generation / review** — Claude proposes freely; a human approves the
  merge. No auto-merge.
- **Tests and docs** — Claude may write autonomously; human review optional
  for trivial changes.

## Generative AI for assets

- **Default: deferred to Phase 6** per [ADR-0010](../../docs/adr/0010-postpone-generative-ai-to-phase-6.md).
  Do not introduce new image / audio generation pipelines.
- **Carve-out (ADR-0018):** textures, 3D models, and the studio iteration
  tooling are pre-opened tracks. They live under explicitly-scoped paths:
  - `tools/texture-gen/` + `.claude/skills/generate-textures/`
  - `tools/studio/`, `tools/model-gen/`
  - `.claude/skills/build-model/`
  - `content/models/`, `content/textures/` (output sinks)
  - Top-level PNG iteration artifacts (`studio-*.png`, `amethyst-*.png`,
    `wave2-*.png`, `wave3-*.png`)

Normal waves **must not touch** the carve-out paths. The asset/studio waves
have their own handoff docs. See `wave-execution.md` for the off-limits list.

## What "Phase 1" means here

- We're building the multi-plugin skeleton + first content waves
  (ADR-0022 / IMPLEMENTATION-ROADMAP Phase 1).
- Asset pipelines (textures / models / sounds) are intentionally minimal —
  enough to demo, not enough to ship.
- The generative-AI carve-out is to let the studio iterate independently
  without blocking on Phase 6.

When Phase 6 lands and ADR-0010 is superseded, this policy will be rewritten.
