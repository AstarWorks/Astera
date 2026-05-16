# Astera — Claude Code workspace guide

This file is loaded by Claude Code when working inside `Astera/`. Read it
before generating code or making infrastructure changes.

## Project ground truth

- **README.md** — what Astera is, status, quick start
- **docs/INDEX.md** — full documentation index
- **docs/architecture/principles.md** — the rules below come from here
- **docs/adr/** — recorded decisions (immutable once Accepted)

## Non-negotiable rules (CI enforces all of these)

1. **Minecraft is an edge.** `domain` and `application` modules MUST NOT import
   `org.bukkit.*`, `io.papermc.*`, `org.spigotmc.*`, or any other server-vendor
   API. Konsist tests in `tools/architecture-test/` fail the build on violation.
2. **`adapter-minecraft-api` is vendor-neutral.** It defines `IMcServer`,
   `IMcPlayer`, etc., and binds application ports. Bukkit imports here = build fail.
3. **Only `adapter-minecraft-impl-paper`, `adapter-providers/*`, and
   `platform-paper-plugin` may import `org.bukkit.*`.**
4. **All external Paper plugins are soft-deps.** Astera must boot in vanilla
   Paper without WeaponMechanics / Oraxen / MythicMobs / ProtocolLib.
5. **Versions live in `gradle/libs.versions.toml`.** Do not hardcode versions
   in module `build.gradle.kts` files.
6. **No `kotlinx-serialization` annotations in `domain`.** Use a DTO in
   `application/config/` and convert to the domain type.

## Coding posture

- Kotlin 2.2 / JDK 25 toolchain. Kotlin emits JVM_24 bytecode (2.2 ceiling);
  Java targets JVM_25 for Paper 26 variant resolution. See `gradle.properties`
  for the `kotlin.jvm.target.validation.mode=warning` rationale.
- Tests: JUnit 5 + AssertJ. Domain tests must run without any Paper harness.
- Prefer constructor injection over Koin annotations in Phase 1; the wiring is
  small enough that manual is more readable. Koin annotations land when the
  Phase 2 use-case count exceeds ~5.
- Add an ADR before making a non-obvious cross-cutting decision (DI swap, ORM
  swap, MC vendor swap). One ADR per decision; never edit a merged ADR.

## Where things live

| Concern | Module |
|---|---|
| Pure game rules / entities / value types | `plugin/domain/` |
| Use cases + outbound port definitions | `plugin/application/` |
| YAML config schema (DTOs) | `plugin/application/config/` |
| i18n lookup logic | `plugin/application/i18n/` |
| Vendor-neutral Minecraft abstractions | `plugin/adapter/minecraft-api/` |
| Paper-specific implementations | `plugin/adapter/minecraft-impl-paper/` |
| Postgres / Redis implementations | `plugin/adapter/persistence-postgres/`, `messaging-redis/` |
| External Paper plugin adaptors | `plugin/adapter/providers/{name}/` |
| Plugin entrypoint + DI wiring | `plugin/platform-paper-plugin/` |
| User-editable content | `content/{weapons,skills,stages,languages}/` |
| k8s manifests (public) | `deploy/` |
| k8s overlays + secrets (private) | `private/` (submodule) |
| Architecture tests | `tools/architecture-test/` |

## When you touch something

- **Adding a weapon:** drop a YAML into `content/weapons/`. No Kotlin needed.
  See `docs/contributing/add-a-weapon-yaml.md`.
- **Adding a use case:** define ports in `application/port/outbound/` first,
  then implement use case in `application/usecase/`, then wire in
  `platform-paper-plugin/AsteraPlugin.kt`. Konsist will catch direction
  violations.
- **Touching k8s:** edit `deploy/` for public base; `private/infra/overlays/`
  for env-specific. ArgoCD picks both up via the App-of-Apps in
  `deploy/apps/argocd-apps.yaml`.
- **Changing dependency versions:** edit `gradle/libs.versions.toml`. That's it.

## What to use

- `make help` lists every common operation (build / docker / kind / argocd).
- `./gradlew check` runs full verification including Konsist.

## AI delegation policy (Phase 1)

- **Code generation / review**: Claude Code may freely propose, but a human
  approves merges. No auto-merge.
- **Generative AI for assets** (textures, sounds, models): **Deferred to Phase 6**
  per ADR-0010. Do not introduce image / audio generation pipelines yet.
- **Tests + docs**: Claude Code may write these autonomously; human review
  optional for trivial changes.

## Sibling AstarWorks repos

| Path | Purpose |
|---|---|
| `/IdeaProjects/AstarWorks` | Company hub (sales, team, strategy) |
| `/IdeaProjects/AstarManagement` | Main product (Spring Boot + Nuxt 4). Package convention `com.astarworks.astarmanagement.*` — Astera mirrors with `com.astarworks.astera.*`. |
| `/IdeaProjects/HomePage` | Public homepage (Nuxt 3) |

When Astera needs persistence design, **check AstarManagement's Exposed setup
first** to stay consistent.
