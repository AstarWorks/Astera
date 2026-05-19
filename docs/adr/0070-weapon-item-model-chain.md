---
status: Accepted
date: 2026-05-19
deciders: orchestrator (Wave _infra-5)
supersedes: null
superseded_by: null
---

# ADR-0070: Weapon → Item → Model Chain (close `ItemYamlConfig.model:`)

## Status

Accepted

## Context

Two prior decisions left a structural hole at the same spot:

- [[adr/0061-studio-weapons-preview-route]] (Wave _infra-3) shipped
  Studio's `/weapons` route with an archetype-keyed model fallback —
  `ARCHETYPE_MODEL[archetype]` resolves every archetype to
  `sword.model.json` because that is the only sample model in
  `tools/model-gen/samples/`. The ADR explicitly flagged "Add `model:`
  to `ItemYamlConfig.kt` now" as a rejected Phase-1 alternative,
  noting "a Phase 2 ADR can flip to the explicit-`model:` design once
  the asset track generates non-sword variants."
- [[adr/0073-item-centric-content-model-and-skill-binding]] (Wave
  _items-1) made items first-class — `content/items/<id>.yaml` carries
  material, inline i18n, attributes, and skill bindings — and renamed
  `plugin:weapons` → `plugin:items`. The rewrite did **not** include a
  `model:` field on the item DTO, so even though items are now the
  authoring unit, there is still no way for a YAML to declare which
  3D model it should preview as.

The Studio frontend also lagged the Wave _items-1 backend migration:
`tools/studio/web/server/routes/items.ts` was already moved to
`/api/items`, but `tools/studio/web/src/routes/WeaponsView.vue`,
`tools/studio/web/src/components/WeaponPreview.vue`, and the matching
`src/api.ts` types still consumed the deleted `/api/weapons` route
with the pre-_items-1 `WeaponYamlConfig` shape (`display_name_key`,
`lore_key`, `damage.base`, `cooldown_ticks`, archetype). The `/weapons`
page was therefore broken at runtime.

Astera's pre-release posture (`/IdeaProjects/Astera/.claude/CLAUDE.md
§Pre-release`) is to fold both gaps into a single forward-only commit:
no aliases, no shims, no dual-decoder, no `@Deprecated`.

## Decision

Wave _infra-5 closes the chain end-to-end:

1. **Kotlin DTO**: add `public val model: String? = null` to
   `plugin/core/src/main/kotlin/com/astarworks/astera/core/application/config/ItemYamlConfig.kt`.
   The field is an opaque slug — no `.model.json` suffix; resolution
   is up to the consumer.
2. **Kotlin domain**: add `val modelSlug: String? = null` to
   `plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/item/ItemSpec.kt`,
   threaded through `toSpec()`. The slug rides on every materialized
   item so future Paper / Bedrock adapters can use it for CMD wiring
   or resource-pack routing without re-parsing YAML.
3. **Studio backend**:
   `tools/studio/web/server/routes/items.ts` `/load/:slug` resolves
   the model in priority order:
   1. `${modelsContentDir}/${spec.model}.model.json` (modelSource =
      `content`)
   2. `${modelSamplesDir}/${spec.model}.model.json` (modelSource =
      `samples`)
   3. fall back to `ARCHETYPE_MODEL[archetypeHint]` (modelSource =
      `archetype`, `modelFallback = true` only when archetypeHint ≠
      `sword`)
   A new endpoint `GET /api/items/model/by-slug/:slug` resolves an
   arbitrary slug to a `.model.json` via the same content-then-samples
   priority.
4. **Studio frontend**: rename `/weapons` → `/items` end-to-end.
   `WeaponsView.vue` → `ItemsView.vue`, `WeaponPreview.vue` →
   `ItemPreview.vue`, `api.ts` types replaced with `ItemListEntry` /
   `ItemYamlConfig` / `LoadedItem` (incl. `modelSource` and
   `modelPathRelative`), router + nav tab updated. Inline-i18n name /
   lore are resolved via the existing `useI18n` composable
   ([[adr/0069-studio-public-release]]). The FALLBACK chip now
   appears only when `modelSource === 'archetype' && modelFallback`.
5. **Sample content**: `content/items/example-pistol.yaml` declares
   `model: example-pistol` and `content/models/example-pistol.model.json`
   exists (seeded from `tools/model-gen/samples/sword.model.json`).
   The chain works; visual quality is out of scope — see Mitigations.

The runtime Bukkit `custom_model_data` path on `ItemStack` is
unchanged. `modelSlug` is read-only metadata for now.

## Consequences

### Positive

- **Mismatch C from ADR-0061 closed.** Items can declare their own
  model; the archetype-keyed fallback is a true fallback (only for
  items without `model:`) rather than the unconditional path it was.
- **Wave _items-1 frontend migration finished.** Studio is fully
  Item-centric top to bottom; no stale `WeaponsView` / `WeaponPreview`
  / `LoadedWeapon` types remain. The `/weapons` page (previously
  broken at runtime) is replaced by a working `/items` page.
- **Read-only metadata sits on the domain.** `ItemSpec.modelSlug` is
  available to any adapter that wants to drive resource-pack routing
  or external-item plugin model overrides — no second YAML parse
  needed.
- **Studio's `modelPathRelative` debug field** surfaces where a model
  actually came from (e.g. `content/models/example-pistol.model.json`
  vs `tools/model-gen/samples/sword.model.json`), useful when an
  iteration "looks wrong" and the author needs to know which file to
  edit.

### Negative / Trade-offs

- **Visual quality still placeholder.** `content/models/example-pistol.model.json`
  is a literal copy of `sword.model.json`. The chain resolves
  correctly, but the pistol previews as a sword. ADR-0061 Mismatch A
  (per-archetype model fidelity) remains open and is left to a future
  `build-model` session.
- **One more parser in sync to keep.** `ItemYamlConfig` now has a
  TypeScript twin and a Kotlin original; the `model:` field has to be
  added in both. Wave _infra-4's `contentLint` (ADR-0067) is the
  long-term answer for cross-language drift detection.
- **`I18nText.vue` now orphaned.** The pre-Wave-_items-1 component
  resolved `display_name_key`-style refs. With the inline-i18n
  rewrite of the Item preview, the only Studio consumer was the
  deleted `WeaponPreview.vue`. The file is retained for now in case a
  future Studio view needs it; a follow-up can delete it if no use
  appears.

### Mitigations

- **Visible model source.** Studio's `LoadedItem.modelSource` lets
  the preview UI distinguish "model declared by this item" from
  "guessed from archetype" — the latter case keeps the FALLBACK chip
  + the original ADR-0061 tooltip, so an author who sees a
  sword-shaped pistol knows whether the issue is the `model:` slug or
  the missing sample asset.
- **Sample model resolution priority.** Content models override
  sample models, so when the `build-model` track produces a real
  pistol model under `content/models/`, no Studio code change is
  needed — drop the file, refresh `/items`, the chip disappears.

## Alternatives Considered

- **Add `model:` to `WeaponYamlConfig` only (no Item touch).**
  Rejected: `WeaponYamlConfig` no longer exists (ADR-0073 deleted it).
  Items are the SSoT for material + visual now.
- **Mandatory `model:` field.** Rejected: today most items use the
  vanilla material's stock model (e.g. `IRON_SWORD` ⇒ vanilla sword
  texture). Requiring a slug forces every YAML to declare one even
  when `material` alone is enough.
- **Make `model:` a structured object (e.g. `model: { slug: …,
  custom_model_data: … }`).** Rejected: `custom_model_data` is already
  a sibling top-level field — collapsing them would only matter when
  Astera ships its own resource pack, and that's Phase 6 territory.
- **Block on `_assets` / `build-model` to generate pistol / wand
  models first.** Rejected: same cross-track-blocking objection
  ADR-0061 already named. The chain working is independent of any
  particular model's visual fidelity.
- **Defer Studio frontend rewire to a separate wave.** Rejected: the
  `/weapons` page was broken anyway after Wave _items-1, and the new
  `model:` field needs to be consumed somewhere to prove the chain.
  Folding both into one commit matches the pre-release posture (one
  clean change > two staged migrations).
- **Use Bukkit `customModelData` (Int) as the slug.** Rejected: CMD
  is a numeric resource-pack pointer that has to be matched by a
  client-side pack. `modelSlug` is a server-side identifier for
  Studio + future external-plugin / Bedrock paths and is decoupled
  from the resource-pack CMD pipeline.

## References

- Related ADRs:
  - [[adr/0061-studio-weapons-preview-route]] — the Phase-1 trade-off
    this ADR closes (Mismatch C). Stays Accepted; not superseded.
  - [[adr/0073-item-centric-content-model-and-skill-binding]] — the
    item-first reframe this builds on. Stays Accepted; not
    superseded.
  - [[adr/0069-studio-public-release]] — Studio env overrides
    (`STUDIO_MODELS_DIR`, `STUDIO_MODEL_SAMPLES_DIR`) +
    inline-i18n `useI18n` composable consumed here.
  - [[adr/0066-promote-studio-to-mainline]] — Studio mainline status
    (means this Studio rewire ships in normal-wave discipline, not
    carve-out).
  - [[adr/0067-contentlint-tooling-track]] — long-term answer for
    Kotlin↔TypeScript schema drift on Item / Skill / etc. YAML.
- Related code:
  - `plugin/core/src/main/kotlin/com/astarworks/astera/core/application/config/ItemYamlConfig.kt`
    (DTO + `model: String?`)
  - `plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/item/ItemSpec.kt`
    (`modelSlug: String?` domain field)
  - `tools/studio/web/server/routes/items.ts`
    (`/load/:slug` resolution + `/model/by-slug/:slug`)
  - `tools/studio/web/src/routes/ItemsView.vue` +
    `tools/studio/web/src/components/ItemPreview.vue` (Studio
    frontend consumer)
  - `content/items/example-pistol.yaml` (`model: example-pistol`)
  - `content/models/example-pistol.model.json` (chain target)
