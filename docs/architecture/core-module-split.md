# Core module split (Folia T1)

The 1,422 original production Kotlin files retain their packages and exact source bytes.
All twelve library modules are bundled into the existing Astera-Core shadow jar.
The seven runtime plugin descriptors and ServiceRegistry wiring remain unchanged.

## Inventory

| Module | Main Kotlin files | Test Kotlin files | Responsibility |
|---|---:|---:|---|
| `:core:feature-admin` | 68 | 0 | Admin operations, moderation, maintenance, observability, analytics, anticheat, AFK |
| `:core:feature-animation` | 15 | 1 | Player/skin-rig animation and cinematics |
| `:core:feature-effects` | 25 | 1 | Effects/VFX, sound/music, per-viewer/visibility |
| `:core:feature-gameplay` | 269 | 4 | GUI, lobby, profile/inventory, world, survival/vault/recipes, HUD, cross-feature quest HUD listener |
| `:core:feature-progression` | 19 | 0 | Quest, advancement, login bonus, engagement |
| `:core:feature-pve` | 20 | 0 | Mob and PvE services/content/adapters |
| `:core:feature-social` | 23 | 0 | Social/party/friendship, chat, presence |
| `:core:feature-tutorial` | 7 | 0 | Tutorial guide/sequencer and progress storage |
| `:kernel:domain` | 632 | 0 | Pure shared records, IDs, geometry, math, and event base |
| `:kernel:mc-api` | 9 | 0 | Vendor-neutral MC abstractions, McPlayerGateway, IPlayerGateway |
| `:kernel:paper` | 26 | 0 | Shared server/player, scheduler, event, command, packet, broadcast/proxy/tracing adapters |
| `:kernel:runtime` | 278 | 0 | Shared ports, event bus, registry, content/i18n, command substrate and generic console, permission backend |
| `:plugin:core` | 31 | 2 | Platform wiring, plugin.yml, and internal wiring helpers |

Six content integration test files moved with their owners. The cross-feature shipped-content
decode test and cross-plugin command-key scan remain in `plugin:core`. The command-key
scan, architecture scopes, test policy and i18n literal coverage scan include `kernel/` and
`core/`; existing allowlist entries follow the moved test paths. No allowlist exceptions were added.

## Dependency graph

Each row lists the direct Gradle project dependencies. `api` exposes the types used in public
signatures; no feature library depends on the plugin host. External libraries remain catalog-based.

| Module | Direct project dependencies |
|---|---|
| `:core:feature-admin` | `:core:feature-gameplay`, `:kernel:domain`, `:kernel:mc-api`, `:kernel:runtime` |
| `:core:feature-animation` | `:core:feature-gameplay`, `:kernel:domain`, `:kernel:runtime` |
| `:core:feature-effects` | `:kernel:domain`, `:kernel:runtime` |
| `:core:feature-gameplay` | `:core:feature-effects`, `:core:feature-progression`, `:core:feature-social`, `:kernel:domain`, `:kernel:mc-api`, `:kernel:paper`, `:kernel:runtime` |
| `:core:feature-progression` | `:kernel:domain`, `:kernel:runtime` |
| `:core:feature-pve` | `:core:feature-gameplay`, `:kernel:domain`, `:kernel:runtime` |
| `:core:feature-social` | `:kernel:domain`, `:kernel:mc-api`, `:kernel:runtime` |
| `:core:feature-tutorial` | `:kernel:domain`, `:kernel:mc-api`, `:kernel:runtime` |
| `:kernel:domain` | — |
| `:kernel:mc-api` | `:kernel:domain` |
| `:kernel:paper` | `:kernel:domain`, `:kernel:mc-api`, `:kernel:runtime` |
| `:kernel:runtime` | `:kernel:domain`, `:kernel:mc-api` |
| `:plugin:core` | `:core:feature-admin`, `:core:feature-animation`, `:core:feature-effects`, `:core:feature-gameplay`, `:core:feature-progression`, `:core:feature-pve`, `:core:feature-social`, `:core:feature-tutorial`, `:kernel:domain`, `:kernel:mc-api`, `:kernel:paper`, `:kernel:runtime` |

## Boundaries retained by the move-only constraint

- GUI ↔ lobby, inventory/profile ↔ lobby, and world ↔ lobby form one strongly connected cluster.
  These are merged in `feature-gameplay`. Existing cross-module smart casts require
  `BlockDrop`, `CustomBlockSpec`, `ItemSpec`, `Portal`, `RegionRule`, and the HUD layout records
  to share that compilation boundary with their consumers. Their dependent ports move with them.
- Survival/vault/recipe code and HUD code join gameplay because their dependencies would
  otherwise form cycles with that cluster. The quest-to-HUD listener sits with gameplay;
  progression services remain a separate dependency below it.
- Generic command-console helpers stay beside `ArgumentType` in runtime, preserving existing
  smart casts. `MinimalPermissionBackend` and `RoleRecord` likewise share runtime.
- `SignAction` imports the command API, so it belongs to runtime rather than the pure domain
  module. `IPlayerGateway` accompanies `McPlayerGateway` in mc-api to avoid a runtime/API cycle.
- Plugin wiring uses Kotlin-internal Paper adapters. They and their direct consumers stay
  in `plugin:core`; no visibility edits, friend paths, source duplication, or compatibility
  bridges are introduced. The retained non-platform files are:

  - `adapter/paper/admin/PaperAdminQuickAccessListener.kt`
  - `adapter/paper/arena/PaperArenaVoidListener.kt`
  - `adapter/paper/arena/PaperArenaWorld.kt`
  - `adapter/paper/entity/PaperNpcBodyService.kt`
  - `adapter/paper/inventory/PaperInventorySnapshotSerializer.kt`
  - `adapter/paper/inventory/PaperPlayerInventoryGateway.kt`
  - `adapter/paper/lobby/PaperLobbyLanePads.kt`
  - `adapter/paper/lobby/PaperLobbyMenuKit.kt`
  - `adapter/paper/lobby/PaperLobbyRulesListener.kt`
  - `adapter/paper/lobby/PaperLobbyWorld.kt`
  - `adapter/paper/lobby/PaperPlayGateHologram.kt`
  - `application/service/effect/NoopWorldGatewayForEffects.kt`

## Consumers

Items, combat, match, stats, economy, persistence and game-sim still directly import
`AsteraCorePlugin` or Core wiring types, so they retain the permitted `plugin:core` API exposure.
Test fixtures now use gameplay/domain/mc-api/runtime directly. Bot-runner uses runtime;
hud-preview and resource-pack use domain; lobby-gen uses gameplay/domain; schema-gen uses
animation/effects/gameplay/runtime; vfx-preview uses effects/domain/runtime.

Feature plugin Shadow configurations already exclude `coreProvidedModules()`, the entire
Core runtime dependency closure. That closure now includes the new project libraries,
preserving single classloader ownership.

## Verification

Baseline: commit `494761a3b9796dd487a92c66048785af64556bd5`, built before moving files
with `./gradlew :plugin:core:shadowJar`.

Passed:

- `./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar` (including
  `tools:architecture-test:compileTestKotlin`).
- Shadow builds for items, combat, match, stats, economy and persistence.
- Git blob comparison: all 1,422 original main Kotlin sources and Core resources
  are byte-for-byte unchanged at their new paths.
- Old/new Core jars: **19,502 unique class entries each; zero added, removed, or
  duplicate class entries**. Expanded `plugin.yml` bytes match exactly.
- All six sibling plugin jars contain **zero** `com/astarworks/astera/core/*.class`
  entries; all retain their plugin descriptors.
- Direct project dependencies match the import graph; `git diff --check` passes.

Sorted class lists are available locally as `build/folia-t1-old-core-classes.txt`
and `build/folia-t1-new-core-classes.txt`. Both have SHA-256:

```text
07ca3abd49ade9e340a96a6d65c90499909b2c74bfdf8c35d2f60b909bccd511
```

No test task, Konsist execution, E2E run, live container operation, or push was
performed. Runtime behavior was not exercised; validation is compilation, source
identity, descriptor identity, and jar class ownership. Kotlin module metadata
and internal method mangling can differ after separate compilation; byte-identical
jars are not claimed. Independent feature distribution remains future work.

See [ADR-0265](../adr/0265-core-kernel-and-feature-library-split.md).
