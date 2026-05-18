# Coding posture

## Toolchain

- **Kotlin 2.2** / **JDK 25** / **Paper 26.1.x**.
- Kotlin emits JVM_24 bytecode (the Kotlin 2.2 compiler ceiling). Java
  compiles to JVM_25 so Paper 26 Maven variants resolve correctly. See
  `gradle.properties` — `kotlin.jvm.target.validation.mode=warning` is set
  intentionally to allow this mismatch.
- Gradle 9.5.1 + version catalog (`gradle/libs.versions.toml`) is the **only**
  place version strings live. Konsist will not catch hardcoded versions in
  `build.gradle.kts`, but the orchestrator + `astera-reviewer` subagent will.

## Dependency injection

- **Phase 1: manual constructor injection.** Wiring is small enough that
  explicit is readable. Each plugin's `platform/Astera<Plugin>Plugin.kt` wires
  use cases by hand.
- **Koin annotations land later** — defer until a single plugin's use-case
  count crosses ~5 or a wave introduces dynamic graph construction. Decision
  per ADR-0004 + ADR-0022.

## Cross-plugin coordination

- Core's **`ServiceRegistry`** for port impls (priority-based registration).
- Core's **`IDomainEventBus`** for fire-and-forget cross-plugin events. The
  sealed `DomainEvent` hierarchy lives in `core.domain.event`.
- Never reach into a sibling plugin's package directly. Konsist will fail the
  build.

## Tests

- **JUnit 5 + AssertJ.** Domain tests must run without any Paper harness —
  if a test needs `Bukkit.getServer()`, the production code is in the wrong
  layer.
- Cross-plugin fakes live in `plugin:test-fixtures`. Feature-specific fakes
  live inside that feature's `src/test/`.
- Konsist tests (`tools:architecture-test`) are part of `./gradlew check`.

## ADR discipline

- Add an ADR **before** a non-obvious cross-cutting decision (DI swap, ORM
  swap, MC vendor swap, new plugin split). Don't ship the change without one.
- One ADR per decision. Never edit a merged ADR (status `Accepted`); supersede
  with a new one instead.
- Use the `adr-new` skill to scaffold from `docs/adr/0000-template.md`.

## Application API surface

- **No raw JVM throwables in application API.** Return `Result<T, E>` with a
  sealed error hierarchy. Konsist enforces this across every plugin's
  `*.application.*` package (see `ArchitectureTest.kt`:
  `application layers must not import raw JVM throwable types`).
- Use `IBroadcaster` / `IMessageRenderer` / `IDomainEventBus` — never raw
  `Bukkit.broadcastMessage(...)` from application/.
