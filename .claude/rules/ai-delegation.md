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
- **No `:plugin:*` runtime module** imports the Anthropic SDK, OpenAI SDK,
  or any ML library. No path under `:plugin:*` calls out to AI services
  at request-handling time.

## Content authoring tools moved to AsteraStudio

The Studio (Nuxt 4 IDE), texture-gen scripts, model-gen samples, and the
`.claude/skills/build-model` + `.claude/skills/generate-textures` skills
were extracted into a separate repo in 2026-05 per
[[adr/0079-extract-studio-to-own-repo]]. They are no longer part of this
repo's source tree, build graph, or `.claude/` ecosystem.

What stays here:

- `content/{items,skills,languages,stages}/` — plugin-runtime YAML
- `content/{textures,models,effects}/` — output sinks for AsteraStudio
  (authored externally; this repo treats them as committed artifacts)

What's in AsteraStudio:

- `tools/texture-gen/` + texture-gen skill
- `tools/model-gen/` + build-model skill
- `tools/studio/web/` → now the AsteraStudio repo root

Astera plugin's invariants are unchanged: no AI/ML SDK in `:plugin:*`,
no AI tool called from Gradle build, no runtime dependency on Studio.

## What "Phase 1" means here

- We're building the multi-plugin skeleton + first content waves
  (ADR-0022 / IMPLEMENTATION-ROADMAP Phase 1).
- Texture pipelines stay intentionally minimal — enough to demo, not enough
  to ship — until ADR-0010 is revisited.

When Phase 6 lands and ADR-0010 is superseded, the texture-side policy
will be rewritten.
