---
status: Accepted
date: 2026-05-20
deciders: Astera workspace (Wave _items-2b)
supersedes: null
superseded_by: null
---

# ADR-0076: Saturate VSCode yaml.schemas + legacy `&`-color support

## Status

Accepted

## Context

Two unrelated polish items consolidated into a single small wave:

1. **VSCode autocomplete coverage was partial.** Of the 12 generated
   `content/schemas/*.schema.json` files, only 5 had a corresponding
   `yaml.schemas` mapping in `.vscode/settings.json`. New schemas (particle,
   stage, shop, norma, gui, anomaly, generator, block-durability) were
   silently invisible to authors. The `weapon.schema.json` mapping pointed
   at a deleted directory.

2. **Item / skill author UX favored only MiniMessage.** Content YAML accepts
   `<gold>Sample</gold>` for color but not the Minecraft-classic `&6Sample`.
   New contributors familiar with traditional `&`-codes hit a wall.

## Decision

### 1. Regenerate every JSON Schema, saturate `.vscode/settings.json`

- Run `./gradlew :tools:schema-gen:generateSchemas` (12 schemas now: anomaly,
  block-durability, element-matrix, generator, gui, item, norma, particle,
  shop, skill, stage, status-effect).
- Map every schema to its content category in `.vscode/settings.json`. The
  obsolete `weapon.schema.json` mapping is removed (file already deleted in
  the prior commit `c589916`).

### 2. Dual color-syntax support via `AsteraTextDeserializer`

New singleton `core.adapter.paper.text.AsteraTextDeserializer.deserialize(text)`
auto-routes:

- If `text` matches `&[0-9a-fA-Fk-orK-ORxX]` → `LegacyComponentSerializer`
  (ampersand character, hex colors enabled)
- Otherwise → `MiniMessage`

All four existing `MM.deserialize(...)` call sites switched to the new
deserializer:

- `core.adapter.paper.gui.PaperItemTemplateRenderer`
- `core.adapter.paper.gui.PaperGuiAdapter`
- `core.adapter.paper.entity.PaperPlayer`
- `items.adapter.paper.PaperItemMaterializer`

Empty / blank input returns `Component.empty()` (defensive: avoids MM
parser exceptions on empty strings).

## Consequences

### Positive

- Authors gain VSCode autocomplete across every existing content category
- `&6サンプル` and `<gold>サンプル</gold>` both work in YAML
- Helper is centralized in core/adapter/paper, so future renderers automatically
  inherit dual-syntax

### Negative / Trade-offs

- Strings mixing both syntaxes (`&6foo<gold>bar</gold>`) are detected as
  legacy, leaving the `<gold>` tag literal. Documented as "pick one per
  string"; the heuristic detects on the legacy pattern because that pattern
  has tighter grammar
- LegacyComponentSerializer's hex extension uses `&#RRGGBB` syntax, not
  MiniMessage `<color:#RRGGBB>` — author has to know which they're in if
  using hex
- Block-durability is a singleton file, not a directory; the mapping is
  `content/balance/block-durability.yaml`

### Mitigations

- `add-an-item-yaml.md` (Phase 1 closing) documents "MiniMessage preferred,
  legacy `&` accepted as fallback"
- The detector is a single regex; if a future case proves the heuristic
  wrong, swap to explicit `text.startsWith("legacy:")` prefix-marker

## Alternatives Considered

- **Always use MiniMessage**: would force re-education of contributors who
  expect `&6`. Rejected for UX cost
- **Always use legacy**: loses MiniMessage features (gradients, hover, click,
  translatable). Rejected for expressiveness cost
- **Pre-convert legacy to MiniMessage at parse time** (serialize via
  `legacy.deserialize → mm.serialize`): brittle round-trip, may not preserve
  all formatting bits. Rejected for fragility

## References

- ADR-0073: Item-centric content model + multi-skill binding
- ADR-0067: Content lint Gradle pipeline (PR-time schema gate)
- ADR-0066: Promote studio to mainline (Studio uses MiniMessage internally)
- ADR-0061: Studio `/items` preview route (superseded for UI, schema track
  unaffected)
