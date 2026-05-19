# Module layout (ADR-0022 multi-plugin)

Astera is **7 independent Paper plugins** (one Gradle module each), wired via
Core's `ServiceRegistry` + central `IDomainEventBus`. The canonical list lives
in `settings.gradle.kts`; the dependency-direction rules are enforced in
`tools/architecture-test/.../ArchitectureTest.kt`.

If anything below contradicts `settings.gradle.kts` or `ArchitectureTest.kt`,
**the code wins**.

## The 7 plugins

| Plugin | Role | Paper `depend` | Paper `softdepend` |
|---|---|---|---|
| `plugin:core` | base. shared domain, `ServiceRegistry`, `IDomainEventBus`, Paper adapter scaffolding. Owns ItemSpec / SkillId / SkillTrigger / DamageProfile vocabulary. | — | — |
| `plugin:items` | Item-centric content (ADR-0073): paper materializer, click→skill-trigger listener, `/astera-items give`. Renamed from `:plugin:weapons` in Wave _items-1. | Core | — |
| `plugin:combat` | damage / skill / status / element matrix. Owns projectile types. Subscribes to `SkillCastRequested`. | Core | Items |
| `plugin:match` | match lifecycle, phases, teams | Core | Combat |
| `plugin:stats` | XP / levels / norma / mission progress | Core | Items, Combat, Match |
| `plugin:economy` | currency / wallet / ledger; Phase 4 shop/enderchest | Core | Stats, Match |
| `plugin:persistence` | Postgres impls of any repository port (priority-override of in-memory) | Core | all features |

Plus `plugin:test-fixtures` (cross-plugin in-memory fakes) and
`tools:architecture-test` (Konsist rules).

## Sibling-isolation matrix (ADR-0022, Konsist-enforced)

A plugin's source may **only** reference Core + its softdepend list. Konsist
fails the build on violation.

```
Core         ← (nothing; base)
Items        ← Core
Combat       ← Core, Items
Match        ← Core, Combat
Stats        ← Core, Items, Combat, Match
Economy      ← Core, Stats, Match
Persistence  ← Core, every feature (DB-impl override)
test-fixtures← Core (no feature plugins, no Minecraft)
```

## Internal layer structure (per plugin)

Inside every feature plugin the package layout is:

```
com.astarworks.astera.<plugin>.domain.*          — pure Kotlin, no I/O
com.astarworks.astera.<plugin>.application.*     — use cases + outbound ports
com.astarworks.astera.<plugin>.application.config.*  — kaml DTOs
com.astarworks.astera.<plugin>.adapter.api.*     — vendor-neutral MC abstractions (Core only)
com.astarworks.astera.<plugin>.adapter.paper.*   — Bukkit / Paper bindings
com.astarworks.astera.<plugin>.platform.*        — JavaPlugin entrypoint, DI wiring
```

Konsist forbids:
- `*.domain.*` importing Bukkit family, JDBC, Exposed, Koin, kotlinx.serialization, java.io/nio/net
- `*.application.*` importing Bukkit family or persistence drivers
- `core.adapter.api.*` importing Bukkit family (vendor-neutral, ADR-0002)
- `*.application.*` importing raw JVM throwables → use sealed errors + `Result<T, E>`
- `combat.domain.*` importing `org.bukkit.entity.{Projectile,Arrow,Fireball}` etc.
- Feature plugins importing `core.adapter.paper.gui.*` (ADR-0021 — go through `IGuiAdapter`)
- GUI `slot` variants holding lambda-typed fields (silly-knuth §5)
- `content/languages/en.yaml` and `ja.yaml` having mismatched keys

## What lives where

| Concern | Location |
|---|---|
| Pure game rules / value objects | `plugin/<plugin>/src/main/kotlin/.../domain/` |
| Use cases + outbound ports | `plugin/<plugin>/src/main/kotlin/.../application/` |
| YAML config DTOs (kaml-annotated) | `plugin/<plugin>/src/main/kotlin/.../application/config/` |
| i18n logic | `plugin/core/src/main/kotlin/.../application/i18n/` |
| Vendor-neutral MC abstractions | `plugin/core/src/main/kotlin/.../adapter/api/` |
| Paper-specific bindings | `plugin/<plugin>/src/main/kotlin/.../adapter/paper/` |
| Postgres repositories | `plugin/persistence/src/main/kotlin/.../adapter/` |
| Plugin entrypoint + DI wiring | `plugin/<plugin>/src/main/kotlin/.../platform/Astera*Plugin.kt` |
| User-editable content | `content/{items,skills,stages,languages,...}/` (Wave _items-1: items replaces weapons) |
| k8s manifests (public) | `deploy/` |
| k8s overlays + secrets (private) | `private/` (submodule) |
| Konsist architecture tests | `tools/architecture-test/` |

## When you touch X

- **Add an item:** drop YAML into `content/items/`. Item is first-class
  (ADR-0073) — material + inline i18n + attributes + skill bindings in one
  file. See `docs/contributing/add-an-item-yaml.md`. No Kotlin needed.
- **Add a skill:** drop YAML into `content/skills/`. Skill defines an action
  (archetype + damage + status + effects); item bindings reference it by id.
- **Add a use case:** define the port in `<plugin>/application/port/outbound/`
  first, then implement use case in `<plugin>/application/usecase/`, then wire
  in `<plugin>/platform/Astera<Plugin>Plugin.kt`. Konsist catches direction
  violations.
- **Add a cross-plugin service:** register at `onEnable` via
  `services.register<IPort>(impl, priority = N)`. Look up via
  `services.get<IPort>()`. Higher priority wins (Persistence's Postgres impls
  override Items' in-memory defaults at priority 100).
- **Add a cross-plugin event:** the sealed `DomainEvent` lives in
  `plugin:core` (`core.domain.event`). Publish via `IDomainEventBus`. Define
  the variant in Core, publish from feature plugin, subscribe in another.
- **Change a dependency version:** edit `gradle/libs.versions.toml`. Never
  hardcode `"5.4.0"` in a module `build.gradle.kts`.
- **Touch k8s:** `deploy/` for public base; `private/infra/overlays/` for
  env-specific. ArgoCD picks both up via `deploy/apps/argocd-apps.yaml`.
