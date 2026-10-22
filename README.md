# Astera

> 100% AI-developed Minecraft server with **siege warfare PvP**, **community-driven UGC**, and **metaverse-style "place" design**.
> Official AstarWorks project.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-Java%2026.3-green.svg)
![Paper](https://img.shields.io/badge/Paper-26.3-orange.svg)
![JDK](https://img.shields.io/badge/JDK-25-red.svg)
![Status](https://img.shields.io/badge/status-core%20vision%20playable-green.svg)

---

## What is Astera?

Astera is an experiment in running an entire Minecraft server **— code, content, infrastructure, operations — through AI delegation.** Three pillars:

| Pillar | What | Phase |
|---|---|---|
| **Siege Warfare PvP** | Large-scale asymmetric attack/defense (core destruction, structural raid) | Phase 3 |
| **Community UGC** | Players submit weapons / skins / maps / sounds via YAML, Discord, or Web | Phase 4 |
| **Metaverse Place** | Persistent worlds with plots, social layer, events | Phase 5 |

Current focus is the **core vision** (2026-10): a game system (queue, match, modes), a lightweight weapon system, a skill system, and a tidy shop / vault / inventory. Phase 4+ (UGC, web admin, marketplace, gacha, cosmetics, metaverse, generative AI, ops automation) is **out of scope** for now; see [`docs/plans/2026-10-core-vision-backlog.md`](docs/plans/2026-10-core-vision-backlog.md).

## What's playable

A Docker server boots all 7 plugins and a player can: queue from the lobby, get auto-balanced into a match (Annihilation pillars, Break Core, Carry TNT, Capture Wool, Steal Jewelry, Battle Loyal), fight with a 10-weapon roster (pistol / rifle / shotgun, knight-sword / greatsword / dagger, frost / fire / thunder / verdant staff) with ammo, mana and stamina pools, hit players and mobs with skills and statuses, buy gear from the lobby shop, and keep items in a personal vault. Everything is also covered headlessly by the `:tools:game-sim` scenario tests (ADR-0174). Step by step: [`docs/contributing/playtest.md`](docs/contributing/playtest.md).

## Architecture in one breath

Astera ships as **7 independent Paper plugins** (ADR-0022), wired via Core's `ServiceRegistry` + central `IDomainEventBus`. Server admins pick the subset they need — the minimum is just **Core + Items** for item authoring and test fire.

```
plugin:core         (mandatory base — shared domain, ServiceRegistry, IDomainEventBus)
  ├─ plugin:items        (depend Core)                       — 鯖民最小単位
  ├─ plugin:combat       (depend Core, softdepend Items)
  ├─ plugin:match        (depend Core, softdepend Combat)
  ├─ plugin:stats        (depend Core, softdepend Items/Combat/Match)
  ├─ plugin:economy      (depend Core, softdepend Stats/Match)
  └─ plugin:persistence  (depend Core, softdepend all — Postgres impl override)
```

Inside every plugin the package layout is `domain` → `application` → `adapter.{api,paper}` → `platform`. **Konsist tests** (`tools/architecture-test/`) enforce both the per-plugin direction (domain cannot import Bukkit) and the cross-plugin sibling-isolation matrix at build time — see `ArchitectureTest.kt` for the live rule set, including i18n key parity and throwable-free application APIs. Adding Folia / Velocity support means adding a sibling `*.adapter.<vendor>.*` package family; the inner layers stay untouched.

Why: see [`docs/adr/0022-multi-plugin-portable-architecture.md`](docs/adr/0022-multi-plugin-portable-architecture.md), [`docs/adr/0001-clean-architecture-with-mc-as-edge.md`](docs/adr/0001-clean-architecture-with-mc-as-edge.md), and [`docs/adr/0002-vendor-neutral-mc-adapter-layer.md`](docs/adr/0002-vendor-neutral-mc-adapter-layer.md).

## Quick start

5-minute setup with Docker Compose — full instructions in [`docker/README.md`](docker/README.md).

```bash
# Build all 7 plugin jars (or pick a subset, e.g. :plugin:core :plugin:items for 鯖民最小)
./gradlew shadowJar
cp docker/.env.example docker/.env
docker compose -f docker/docker-compose.yml up -d
# Connect Minecraft Java client (26.3) to localhost:25565
/astera items give <yourName> knight-sword
```

Just want to compile and run unit + architecture tests? `./gradlew check`.

## Tech stack

- **Kotlin 2.2** / **JDK 25** / **Paper 26.3**
- **Gradle 9.5.1** + version catalog (single source of truth for dependencies)
- **Koin** (DI, single — no Dagger/Spring stack)
- **kaml** (YAML config)
- **PostgreSQL** + **Exposed** (persistence; aligned with AstarManagement)
- **Redis** (messaging / cache)
- **ArgoCD** + **Kustomize** (GitOps k8s deployment, based on [Re-DIVERSE_infrastructure](https://github.com/Re-DIVERSE/Re-DIVERSE_infrastructure))
- **Konsist** (architecture dependency-direction enforcement)

## Documentation

Everything lives in [`docs/`](docs/). Start from the index:

- **[docs/INDEX.md](docs/INDEX.md)** — table of contents
- [docs/architecture/overview.md](docs/architecture/overview.md) — system overview
- [docs/architecture/principles.md](docs/architecture/principles.md) — Clean Arch / SOLID / Minecraft-edge principles
- [docs/adr/](docs/adr/) — Architecture Decision Records (why we chose what we chose)
- [docs/contributing/getting-started.md](docs/contributing/getting-started.md) — how to contribute
- [docs/contributing/playtest.md](docs/contributing/playtest.md) — boot the server and play a match
- [docs/contributing/add-an-item-yaml.md](docs/contributing/add-an-item-yaml.md) / [add-a-skill-yaml.md](docs/contributing/add-a-skill-yaml.md) — add a weapon or skill **without writing Kotlin**
- [docs/plans/2026-10-core-vision-backlog.md](docs/plans/2026-10-core-vision-backlog.md) — current scope and work packages
- [docs/roadmap.md](docs/roadmap.md) — Phase plan

## Contributing

Three entry points (more drop as later phases ship):

1. **YAML only** — drop a file in `content/items/*.yaml` or `content/skills/*.yaml`, open a PR. No Kotlin required. `./gradlew contentLint` (part of `check`) validates it against `content/schemas/`. ([Item guide](docs/contributing/add-an-item-yaml.md), [Skill guide](docs/contributing/add-a-skill-yaml.md))
2. **Kotlin** — for adapters, game modes, infrastructure. ([Getting started](docs/contributing/getting-started.md))
3. **Discord / Web** — submit without git or GitHub. *Coming in Phase 4.*

## Lineage

Astera reuses architectural patterns from the closed [`Y-RyuZU/RyuZUTechnicalMagic`](https://github.com/Y-RyuZU/RyuZUTechnicalMagic) experiment — but rebuilds the codebase from scratch with stricter Clean Architecture, vendor-neutral MC abstraction, and Paper 26 / JDK 25 baseline. See [`docs/adr/0001`](docs/adr/0001-clean-architecture-with-mc-as-edge.md) for the rationale.

## License

MIT. See [LICENSE](LICENSE).

## Status

**Core vision playable (2026-10).** Landed: host-neutral wiring and the headless `:tools:game-sim` simulator (ADR-0174), input triggers and the weapon roster, resource pools, melee / projectile / area skills with mechanics batches A and B, Annihilation pillars (ADR-0175) and the siege objective modes (ADR-0176), queue-to-match flow, per-team spawns and stage-to-world binding, the personal vault, the lobby shop, and Postgres persistence. Content is validated by `contentLint` and schemas are generated from the Kotlin DTOs (`:tools:schema-gen`). Phase 4+ is out of scope until the core vision is stable.
