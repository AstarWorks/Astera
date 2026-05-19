---
status: Superseded
date: 2026-05-19
deciders: orchestrator (Wave _infra-3)
supersedes: null
superseded_by: [[adr/0073]] (route rename `/weapons` → `/items`), [[adr/0075]] (route removed entirely)
---

# ADR-0061: Studio `/weapons` Preview Route

## Status

Superseded.
- ADR-0073 (2026-05-19) renamed the route to `/items` and absorbed it
  into the Item-centric content model.
- ADR-0075 (2026-05-20) removed the route entirely — read-only YAML
  preview did not justify the implementation cost; the original intent
  ("MC-less skill preview") is filed as future work (Skill Playground).

## Context

Wave _infra-1 (ADR-0059) introduced `AsteraTestHarness` so Kotlin scenario
tests can exercise weapon-fires → damage → kill → reward flows without
running Paper. Wave _infra-2b (ADR-0065) introduced
`/astera reload <category>` so operators can hot-swap any content YAML
without restarting the server. Both close part of the PDCA loop, but there
is still **no path for Claude or a human content author to *see* what a
weapon looks like** — visual + stats — without booting Paper + a Minecraft
client.

The studio at `tools/studio/web/` (Vite + Vue3 + Hono + Bun + Three.js)
already hosts `/textures`, `/models`, and `/effects` views per ADR-0018's
carve-out for AI-iteration tooling. Each existing view follows the same
shape: a sidebar list of artifacts in `content/<type>/` plus a main pane
with a 3D / image preview. The natural next step is `/weapons`.

While planning, exploration uncovered a chain that the original handoff
prompt assumed but does **not** exist:

- `content/weapons/<id>.yaml` → `item: <item-id>`
- `content/items/<id>.yaml` → `material: <BUKKIT_NAME>` — there is **no
  `model.json` reference** on the item side
- `tools/model-gen/samples/` contains only `sword.model.json`; no pistol or
  wand variants exist

The route must still render *something* 3D for every weapon, otherwise the
preview UX collapses for two of the three example weapons. The choice is
between (a) blocking on `_assets` to generate pistol / wand sample models,
(b) extending Kotlin `ItemYamlConfig` with an optional `model:` field
right now, or (c) shipping the route with a fallback model and documenting
the gap.

## Decision

Add a read-only `/weapons` route to `tools/studio/web/` with these
properties:

- **Backend**: new `server/routes/weapons.ts` exposing three endpoints —
  `GET /api/weapons` (list), `GET /api/weapons/load/:slug` (parse weapon
  YAML + cross-ref item YAML), `GET /api/weapons/model/:archetype` (sample
  model passthrough from `tools/model-gen/samples/`).
- **Frontend**: new `src/routes/WeaponsView.vue` (sidebar + main pane) and
  `src/components/WeaponPreview.vue` (3D viewport via existing
  `ThreeViewport` + stats card with attribute / rarity / cooldown / level
  chips).
- **Archetype-keyed model fallback** (choice (c) above): a server-side
  `ARCHETYPE_MODEL` map points each weapon archetype at a sample model
  file, falling back to `sword.model.json` for archetypes whose sample
  doesn't exist yet. The frontend renders a visible `FALLBACK MODEL` chip
  whenever this falls back so the viewer knows the rendered model is not
  archetype-specific.
- **No live YAML editing.** Preview only. Use `/astera reload weapons`
  in-game to apply changes after editing files.
- **No Kotlin / Gradle / Konsist touch.** This wave stays entirely in
  `tools/studio/web/` and `docs/`. Existing content YAML schemas are
  untouched.

The view participates in ADR-0018's carve-out (no runtime dependency on
the AI / studio stack from Astera plugins; the studio is purely dev /
iteration tooling).

## Consequences

### Positive

- **Visual PDCA loop closes.** Claude can now compare weapon iterations
  via Playwright screenshots (`.playwright-mcp/wave-_infra-3-*.png`) as
  part of any future weapon-tuning task.
- **Zero Three.js code authored.** Reuses `ThreeViewport` +
  `mcmodel-to-three.ts` from `/models`. No new 3D pipeline.
- **Cross-references the item YAML**, surfacing `material` / `external_key`
  + `pdc_tags` next to the weapon's `damage` / `cooldown` so authors can
  spot a typo across the weapon→item boundary without leaving the page.
- **Tab-completion-friendly future**: the sidebar list is alphabetical and
  reads from `content/weapons/*.yaml` on every refresh, so adding a new
  weapon YAML and hitting the reload (`↻`) button shows it instantly —
  no rebuild.

### Negative / Trade-offs

- **Pistol / wand archetypes render the sword model.** Visually they look
  identical to the sword preview. The `FALLBACK MODEL` chip signals this
  but does not fix it — a viewer who doesn't notice the chip would assume
  all archetypes look the same.
- **Stats panel renders i18n keys literally.** `display_name_key:
  astera.weapon.example-sword.name` is shown as the literal string, not the
  EN/JA rendered value. Phase 2 may add a locale toggle that fetches
  `content/languages/en.yaml` + `ja.yaml` and resolves the key.
- **Two parallel YAML parsers in the repo.** Kotlin uses `kaml` against the
  same files the TypeScript side now parses with `js-yaml`. Schema drift
  would have to be caught manually (or by Wave _infra-4's `contentLint`).
- **Top-level PNG churn risk**: the screenshots default to repo root if
  `.playwright-mcp/` isn't specified explicitly. The Phase C wiring moves
  them into `.playwright-mcp/` immediately, but future Playwright runs in
  this codebase should explicitly write to `.playwright-mcp/`.

### Mitigations

- **Fallback chip plus tooltip.** Mouseover the `FALLBACK MODEL` chip
  shows "No archetype-specific model yet — falling back to sword. Phase 2
  fix." which makes the gap discoverable.
- **Pistol / wand model generation** is a clean follow-up for the
  `_assets` track. When those land in `tools/model-gen/samples/`, the
  `ARCHETYPE_MODEL` map (one-line edit) routes them and the chip
  disappears.
- **i18n resolution** can be added without backend changes — the studio
  can fetch `content/languages/{en,ja}.yaml` directly and resolve client-side.
- **Item schema gap** (no `model:` field) can be revisited if a weapon
  ever needs a non-archetype-uniform 3D model. For Phase 1 the archetype-
  keyed fallback is sufficient.

## Alternatives Considered

- **Skip 3D, show only stats.** Rejected: the route's whole point is
  "what does the weapon look like?" Stats alone can be read from the YAML
  file directly.

- **Add `model:` to `ItemYamlConfig.kt` now.** Rejected: forces a Kotlin
  schema change for a feature whose visual fidelity is gated on the
  `_assets` track anyway. Would expand Wave _infra-3's scope from
  TypeScript-only to "TypeScript + Kotlin schema change + content YAML
  edits". The Phase 1 fallback is reversible — a Phase 2 ADR can flip to
  the explicit-`model:` design once the asset track generates non-sword
  variants.

- **Block Wave _infra-3 until pistol / wand sample models exist.**
  Rejected: blocks the visual PDCA loop on an independent track. The
  fallback approach unblocks Claude today; the assets follow.

- **Generate pistol / wand models inline as part of this wave.** Rejected:
  cross-track scope creep. Model generation belongs to `_assets` / the
  `build-model` skill (ADR-0018 carve-out); folding it into _infra-3
  would muddle the model.

- **Embed a writable form to edit YAML in the browser.** Rejected: out of
  scope. `/astera reload weapons` provides the operator-side hot-swap;
  YAML edits go through the regular editor.

## References

- Related ADRs:
  - [[adr/0017-adopt-datadriven-contents-pattern]] (substrate this previews)
  - [[adr/0018-ai-asset-creation-policy]] (the carve-out this wave extends)
  - [[adr/0059-fake-mc-server-scenario-dsl]] (Kotlin-side scenario testing
    counterpart — Kotlin tests + Studio preview close the PDCA loop)
  - [[adr/0060-datadriven-contents-pattern]] (DataDriven Contents
    Implementation — `WeaponContents` is the substrate this route reads)
  - [[adr/0065-per-category-reload-dispatcher]] (operators use
    `/astera reload weapons` after editing YAML; studio hint footer
    surfaces the command)
- Related docs:
  - `tools/studio/web/src/routes/WeaponsView.vue`
  - `tools/studio/web/src/components/WeaponPreview.vue`
  - `tools/studio/web/server/routes/weapons.ts`
  - `.playwright-mcp/wave-_infra-3-weapon-preview-sword.png`
  - `.playwright-mcp/wave-_infra-3-weapon-preview-pistol.png` (shows the
    `FALLBACK MODEL` chip behaviour)
  - `plugin/weapons/.../application/config/WeaponYamlConfig.kt` (the
    Kotlin DTO whose snake_case field names this route's TypeScript types
    mirror)
