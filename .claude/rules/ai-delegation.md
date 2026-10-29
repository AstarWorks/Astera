# AI delegation and content authoring

## Code, tests, docs

- **Code generation / review** — Claude proposes freely; a human approves the
  merge. No auto-merge.
- **Tests and docs** — Claude may write autonomously; human review optional
  for trivial changes.

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

Astera plugins have no runtime dependency on Studio.
