---
status: Accepted
date: 2026-05-17
deciders: ryuzu
---

# ADR-0016: Content schema SSoT + multi-format wire pluggability

## Status

Accepted

## Context

Astera's content (weapons, skills, stages, languages, balance tables, future
UGC items) is authored as data files in `content/`. Three orthogonal needs
arise around that authoring:

1. **JSON Schema for validation** — non-tech contributors should get
   editor-side errors when a weapon YAML has a typo, not a server crash at
   load time.
2. **VSCode (Red Hat YAML extension) autocomplete** — schema-driven completion
   while editing.
3. **AI agent prompt constraints** — Phase 6 generative AI must produce
   schema-valid content automatically, not freeform JSON that drifts from
   the actual schema.

The naive approach is to write JSON Schema by hand, point VSCode at it,
embed it in the AI prompt, and hope the three stay in sync with the actual
Kotlin DTOs. They will not.

Separately, the user (2026-05-17) raised two refinements:

- **TOML vs YAML** — these are wire formats. The choice should be a
  configuration, not an architectural commitment. Contributors and AI agents
  should be free to pick whichever format they're best at.
- **YAML anchor & alias** — within a single file, contributors want to define
  an effect once and reference it (`&spark` then `*spark`). This must work.

## Decision

### Part 1: The Kotlin `@Serializable` DTO is the SSoT

The single source of truth for every content schema is the **`@Serializable`
Kotlin data class** in `plugin/application/config/` (today: `WeaponYamlConfig`).

Everything else is derived:

```
plugin/application/config/WeaponYamlConfig.kt   ← SSoT
        │
        │ gradle task `generateContentSchemas` (Phase 1.5 or Phase 4 impl)
        │ kotlinx.serialization.descriptors.SerialDescriptor introspection
        ▼
content/schemas/weapon.schema.json   ← derivation 1 (JSON Schema, committed)
        │
        ├──▶ .vscode/settings.json yaml.schemas mapping (derivation 2: VSCode autocomplete)
        └──▶ docs/ai-pipeline/agents/weapon-designer.md system prompt context (derivation 3: AI constraint)
```

**The 1-line rule**: when changing a content schema, edit the `@Serializable`
DTO. Everything else regenerates.

A schema-consistency check task fails the build if a hand-edit to a derived
artifact diverges from the SSoT (Phase 4+ implementation).

### Part 2: Wire format is one more derivation

YAML, TOML, and JSON are equally valid serializations of the SSoT. The
`ContentLoader` is format-agnostic; it picks a parser by file extension.

```
SSoT (Kotlin DTO with @Serializable)
        │
        ├─ YamlContentParser  (.yaml / .yml, kaml-backed) — anchors/aliases supported
        ├─ TomlContentParser  (.toml, ktoml-backed)       — type-strict, AI-friendly
        └─ JsonContentParser  (.json, kotlinx-serialization-json) — tooling-rich, AI-friendliest
```

Contributors write in any of the three. Files in the same directory can mix
formats (`example-sword.yaml` and `example-pistol.toml` coexist). The DTO is
the same.

### Part 3: YAML anchor / alias is required

Within a single YAML file, `&anchor` / `*alias` must work. kaml (already in
use) is YAML 1.2 compliant and supports anchors natively. Phase 2 mid
implementation includes an integration test asserting this:

```yaml
_anchors:
  spark: &spark { id: vanilla:electric_spark, count: 30, offset: { x: 0.3, y: 0.5, z: 0.3 } }
effects:
  particle:
    - *spark
```

### Part 4: Cross-file reuse via `@ref:<path>`

YAML anchors are file-local. For "use this spark effect in every weapon",
Astera adds a `@ref:<path>` syntax that the `ContentLoader` resolves as a
pre-processing step before deserialization:

```yaml
# content/effects/particles/electric-spark.yaml
id: vanilla:electric_spark
count: 30
offset: { x: 0.3, y: 0.5, z: 0.3 }
```

```yaml
# content/weapons/lightning-blade.yaml
effects:
  particle:
    - "@ref:effects/particles/electric-spark"
```

The loader sees `"@ref:..."` strings during walk, reads the referenced file
(via the same multi-format dispatch), and substitutes its parsed shape.
Refs in TOML or JSON look the same (`"@ref:..."` string).

### Part 5: language files (`content/languages/*.yaml`)

Languages are special: deep key trees + strings + occasional lists. YAML is
clearly the best wire format for these. We **recommend** YAML for languages
but the multi-format loader still accepts `.toml` / `.json` if a contributor
prefers (e.g. AI agent output).

## Consequences

### Positive

- One canonical schema definition. Editor hints, AI prompts, validation, and
  the actual runtime parser all read from the same DTO.
- Contributors choose the wire format that suits them — humans tend toward
  YAML for editability, AI tends toward JSON for low-error generation.
- YAML anchor/alias gives the "I want to define this effect once and reuse it"
  experience without inventing a new mechanism.
- Cross-file `@ref:` lets the eventual `content/effects/` library be shared
  across weapons / skills / stages without copy-paste.

### Negative / Trade-offs

- Multi-format support adds three parsers + an extension-dispatch layer
  (estimated +400 lines across application/config and one Phase 2 mid commit).
- AI agents see three valid output formats; we need to pick a default in the
  prompt or accept any of them.
- Schema generation (gradle task) requires investment in kotlinx-serialization
  introspection. Mitigated by deferring impl to Phase 1.5 / Phase 4 and
  keeping the DTO simple in Phase 1.

### Mitigations

- The `IContentParser` port + `ContentLoader<T>` work is shared across all
  content types — pay the cost once for weapons, skills/stages/items inherit
  it for free.
- Schema generation can start with the simplest reflection-based emit (Phase
  1.5 PoC) and grow into a library-grade implementation as Phase 4 UGC
  marketplace demands richer validation.

## Implementation phasing

| Phase | Adds |
|---|---|
| Phase 1 ✅ | DTO (`WeaponYamlConfig`) authored as `@Serializable`. YAML-only via kaml. |
| Phase 1.5 | Multi-format parsers (`YamlContentParser` / `TomlContentParser` / `JsonContentParser`). `IContentParser` port. `ContentLoader<T>` generic. Anchor/alias integration test. `@ref:` resolver. |
| Phase 1.5 / Phase 4 | `generateContentSchemas` gradle task. Initial PoC outputs `content/schemas/weapon.schema.json`. `.vscode/settings.json` mapping. |
| Phase 2 mid | Skill / Status / Stage DTOs follow the same pattern (single-file new DTOs, schemas auto-generated). |
| Phase 4 | UGC web form reads the schemas to build authoring UI. |
| Phase 6 | `weapon-designer` agent reads schema as system-prompt context. |

## Alternatives Considered

- **Hand-write `weapon.schema.json` and let the DTO mirror it**: the schema
  would drift the first time anyone updates the DTO without remembering to
  regenerate. Burns code-review time forever.
- **YAML only, no TOML/JSON**: fine until Phase 6 generative AI starts
  outputting content. AI's error rate on YAML indentation is high; JSON is
  near-zero. Forcing AI to fight YAML wastes inference budget on no
  architectural benefit.
- **Strip anchor/alias** (file-local re-use unsupported): Contributors writing
  similar weapons would copy-paste effect blocks; bad ergonomics, bad ADR-0013
  spirit (we'd be worse than RTM).
- **Replace YAML with TOML across the board**: TOML is great for flat config
  but verbose for deep nesting (`[[trigger]] [[trigger.action.status_effects]]`).
  Astera's content is deeply nested.

## References

- [[adr/0005-postgres-not-mongo]] (DTO-as-deserialization-shape pattern)
- [[adr/0013-rtm-divergence-policy]] §"Reflection in domain forbidden" — the
  schema generation is not reflection, it's `SerialDescriptor` introspection
  done in `application/config/` (the only layer where serialization metadata
  is allowed).
- [[architecture/rtm-divergence]] §"Redesign" row for ConfigurationModule
- 計画書 §14.7 (Schema SSoT and wire format pluggability)
- kaml (YAML library): https://github.com/charleskorn/kaml
- ktoml (TOML library): https://github.com/akuleshov7/ktoml
- kotlinx.serialization JSON: https://github.com/Kotlin/kotlinx.serialization
