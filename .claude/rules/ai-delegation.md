# AI delegation policy (Phase 1)

## Code, tests, docs

- **Code generation / review** — Claude proposes freely; a human approves the
  merge. No auto-merge.
- **Tests and docs** — Claude may write autonomously; human review optional
  for trivial changes.

## Generative AI for assets

- **Default: deferred to Phase 6** per [ADR-0010](../../docs/adr/0010-postpone-generative-ai-to-phase-6.md).
  Do not introduce new image / audio generation pipelines into the runtime
  or build graph.
- **Texture carve-out (ADR-0018):** AI image generators (Codex CLI, PXC,
  spritecook.ai, …) are permitted as designer-side authoring tools, with
  outputs committed as static `*.png` under `content/textures/`. Scaffolding
  lives under `tools/texture-gen/` + `.claude/skills/generate-textures/`.
  Nothing in those paths may be referenced from any `:plugin:*` runtime
  module or from the Gradle build graph.

### Studio / model-gen / build-model — mainline tracks (ADR-0066)

Per [[adr/0066-promote-studio-to-mainline]] (2026-05-19), the following
paths are **first-class mainline tracked code**, not carve-out:

- `tools/studio/` (Vite + Vue 3 + Hono + Bun + Three.js dev server)
- `tools/model-gen/` (ASDSL samples, benchmark, templates)
- `.claude/skills/build-model/` (LLM iteration skill)
- `content/models/` (committed model JSON / BBModel outputs)

They are subject to normal wave discipline: changes ship in commits, keep
`bun run typecheck` and `bun test` green inside `tools/studio/web/`, and
add tests when adding features.

ADR-0010's runtime/build invariant still applies: no `:plugin:*` runtime
module imports the Anthropic SDK or any ML library. The studio's use of
the Anthropic SDK is in a separate Node.js dev-server process not in the
Gradle build graph.

## What "Phase 1" means here

- We're building the multi-plugin skeleton + first content waves
  (ADR-0022 / IMPLEMENTATION-ROADMAP Phase 1).
- Texture pipelines stay intentionally minimal — enough to demo, not enough
  to ship — until ADR-0010 is revisited.
- The studio + ASDSL track has accelerated past "minimal demo" and now ships
  as mainline (ADR-0066).

When Phase 6 lands and ADR-0010 is superseded, the texture-side policy will
be rewritten; studio mainline status is unaffected.
