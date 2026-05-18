# Astera — Claude Code workspace guide

This file is loaded by Claude Code when working inside `Astera/`. Read it
before generating code or making infrastructure changes.

## Project ground truth

- **README.md** — what Astera is, status, quick start
- **docs/INDEX.md** — full documentation index
- **docs/architecture/principles.md** — the rules below come from here
- **docs/adr/** — recorded decisions (immutable once Accepted)

## Session bootstrap (read these first when triggered with a "Wave X" prompt)

The roadmap is split across 2 in-repo docs + per-wave handoff docs outside the repo:

1. **`docs/IMPLEMENTATION-ROADMAP.md`** — 12-tier leverage-ordered master roadmap,
   phase-by-phase, cumulative parity milestones. Single source of truth for
   ordering.
2. **`docs/RTM-PARITY-TRACKER.md`** — feature × state × wave-assignment matrix.
   Single source of truth for "what's left".
3. **`/home/node/.claude/plans/home-node-claude-plans-radius-roadmap-handoff.md`**
   — session-level entry point. Lists every wave's trigger phrase and points to
   the per-wave handoff doc.
4. **`/home/node/.claude/plans/wave-{N}{x}-handoff.md`** — execution detail for
   that specific wave (Phase A→B→C plan, worker territory, scope, completion
   criteria, ADR number). **Read this before spawning any worker.**

When the user says `Wave 5a (Combat depth) を実装して` (or similar), the
expected flow is:

1. Read `wave-5a-handoff.md` (or whichever wave was triggered) end-to-end.
2. Cross-reference `IMPLEMENTATION-ROADMAP.md` for the wave's row and any
   cross-wave dependencies.
3. Run Phase A (me solo: dep prep, port skeletons, mkdir, TaskCreate).
4. Spawn Phase B workers in parallel per the handoff's worker table.
5. Phase C (me): wire plugins, write the ADR, `./gradlew check`, update
   `RTM-PARITY-TRACKER.md`, single commit.

## Wave-execution conventions (apply to every wave session)

These are not rules CI enforces — they are session protocol. Violating them
has burned past sessions, so treat them as durable.

- **Single commit per wave.** No commit splitting unless the wave handoff doc
  explicitly says to split (e.g., Wave 6 may be 6a + 6b).
- **`push` is never done by Claude.** The user pushes manually after review.
- **Worker禁則** (workers spawned via `Agent` in Phase B must not):
  - Run `./gradlew` (gradle daemon contention with the integrator)
  - Touch another worker's conflict region (create new dirs in Phase A)
  - Modify `platform/AsteraXxxPlugin.kt` wiring (Phase C / me only)
  - Write or edit ADRs (Phase C / me only)
  - Run `git commit` / `git push` / `git branch` / `git checkout`
- **Existing untracked files are off-limits** unless the wave explicitly owns
  them. Specifically do not touch:
  - `tools/texture-gen/` and `.claude/skills/generate-textures/` (asset track)
  - `tools/studio/`, `tools/model-gen/`, `.claude/skills/build-model/`,
    `content/models/`
  - `.playwright-mcp/`
  - Top-level PNG screenshots (`studio-*.png`, `amethyst-*.png`,
    `wave2-*.png`, `wave3-*.png`)
  - `docs/adr/0017-adopt-datadriven-contents-pattern.md` /
    `docs/adr/0018-ai-asset-creation-policy.md` (separate landing track)
- **ADR numbers are pre-allocated** in `IMPLEMENTATION-ROADMAP.md` §"ADR
  allocation per wave". Use that number; do not pick a fresh one.
- **Konsist must stay green.** New rules may be added; existing rules must not
  be relaxed. Run `./gradlew check` before committing.

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
