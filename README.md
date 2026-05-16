# Astera

> 100% AI-developed Minecraft server with **siege warfare PvP**, **community-driven UGC**, and **metaverse-style "place" design**.
> Official AstarWorks project.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-Java%2026.1.x-green.svg)
![Paper](https://img.shields.io/badge/Paper-26.1.x-orange.svg)
![JDK](https://img.shields.io/badge/JDK-25-red.svg)
![Status](https://img.shields.io/badge/status-Phase%201-yellow.svg)

---

## What is Astera?

Astera is an experiment in running an entire Minecraft server **— code, content, infrastructure, operations — through AI delegation.** Three pillars:

| Pillar | What | Phase |
|---|---|---|
| **Siege Warfare PvP** | Large-scale asymmetric attack/defense (core destruction, structural raid) | Phase 3 |
| **Community UGC** | Players submit weapons / skins / maps / sounds via YAML, Discord, or Web | Phase 4 |
| **Metaverse Place** | Persistent worlds with plots, social layer, events | Phase 5 |

Phase 1 (now) is the **foundation**: a loosely-coupled Clean Architecture plugin where Minecraft is just one of several possible delivery edges, plus GitOps k8s manifests. Game content is intentionally minimal until the structural skeleton is solid.

## Architecture in one breath

```
domain (pure Kotlin, no Minecraft)
  └─ application (use cases + ports)
       └─ adapter-minecraft-api (vendor-neutral)
            ├─ adapter-minecraft-impl-paper (Paper-specific)
            ├─ adapter-persistence-postgres
            ├─ adapter-messaging-redis
            └─ adapter-providers/{weaponmechanics,oraxen,mythicmobs}
                 └─ platform-paper-plugin (entrypoint)
```

Dependency direction is **enforced at build time by Konsist tests** — domain cannot import Bukkit, and the vendor-neutral MC adapter cannot leak Paper-specific types. Adding Folia / Spigot / Velocity support means adding a sibling `adapter-minecraft-impl-*` module; the inner layers stay untouched.

Why: see [`docs/adr/0001-clean-architecture-with-mc-as-edge.md`](docs/adr/0001-clean-architecture-with-mc-as-edge.md) and [`docs/adr/0002-vendor-neutral-mc-adapter-layer.md`](docs/adr/0002-vendor-neutral-mc-adapter-layer.md).

## Quick start

> "5-minute setup" with `docker compose up` lands in **Phase 1 Step 4**. Until then, the only thing that runs is `./gradlew check`.

```bash
git clone https://github.com/AstarWorks/Astera.git
cd Astera
./gradlew check
./gradlew :plugin:platform-paper-plugin:shadowJar
# Output: plugin/platform-paper-plugin/build/libs/astera-paper-*.jar
```

## Tech stack

- **Kotlin 2.2** / **JDK 25** / **Paper 26.1.x**
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
- [docs/contributing/add-a-weapon-yaml.md](docs/contributing/add-a-weapon-yaml.md) — add a weapon **without writing Kotlin**
- [docs/roadmap.md](docs/roadmap.md) — Phase plan

## Contributing

Three entry points (more drop as later phases ship):

1. **YAML only** — drop a file in `content/weapons/*.yaml`, open a PR. No Kotlin required. ([Guide](docs/contributing/add-a-weapon-yaml.md))
2. **Kotlin** — for adapters, game modes, infrastructure. ([Guide](docs/contributing/add-a-weapon-kotlin.md), Phase 1+)
3. **Discord / Web** — submit without git or GitHub. *Coming in Phase 4.*

## Lineage

Astera reuses architectural patterns from the closed [`Y-RyuZU/RyuZUTechnicalMagic`](https://github.com/Y-RyuZU/RyuZUTechnicalMagic) experiment — but rebuilds the codebase from scratch with stricter Clean Architecture, vendor-neutral MC abstraction, and Paper 26 / JDK 25 baseline. See [`docs/adr/0001`](docs/adr/0001-clean-architecture-with-mc-as-edge.md) for the rationale.

## License

MIT. See [LICENSE](LICENSE).

## Status

**Phase 1 in progress.** The Gradle multi-module skeleton, architecture enforcement, and documentation scaffold are in place. Game functionality, k8s deployment, and CI/CD land in subsequent Phase 1 steps.
