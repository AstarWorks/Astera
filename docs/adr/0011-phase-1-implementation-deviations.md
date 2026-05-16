---
status: Accepted
date: 2026-05-17
deciders: ryuzu
---

# ADR-0011: Phase 1 implementation deviations from plan §3

## Status

Accepted

## Context

While implementing the Gradle multi-module scaffold (plan §9) and the
weapon-thin-slice (plan §10 Step 3), several build-environment and
ecosystem constraints forced small deviations from the originally planned
tooling. Recording them so future readers (human or AI) understand why
the build looks the way it does.

## Decisions

### 1. Detekt is **disabled** in Phase 1

The plan §3.1 promised "Detekt custom rule" for forbidden-import enforcement,
but Detekt 1.23.7 (the latest 1.x line) ships with an embedded Kotlin
compiler that cannot parse JDK 25 (`JavaVersion.parse("25.0.1")` throws).
Detekt 2.x is not yet GA at the time of writing.

**Resolution:** disable Detekt task entirely in `astera.kotlin-common`
convention plugin. Architectural enforcement is handled exclusively by
Konsist tests in `:tools:architecture-test`. Detekt config stays at
`config/detekt/detekt.yml` so re-enabling is a one-line change once a
JDK-25-aware version ships.

### 2. Kotlin emits **JVM_24** bytecode; Java targets **JVM_25**

Kotlin 2.2.20's `JvmTarget` enum stops at `JVM_24`. Paper 26.x publishes
artifacts marked as JVM 25, so Gradle variant resolution refuses to pair a
JVM 24-targeting consumer with the Paper API.

**Resolution:**
- `kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_24`
- `java.targetCompatibility = JavaVersion.VERSION_25`
- `kotlin.jvm.target.validation.mode=warning` in `gradle.properties`

Both targets are runtime-compatible (JVM 24 bytecode runs on JDK 25). The
asymmetry is purely about Gradle's variant matcher.

Revert to a single `JVM_25` target once Kotlin supports it.

### 3. Configuration cache is **disabled**

KSP (used for Koin annotation processing) is not yet config-cache compatible
under Gradle 9.5.

**Resolution:** `org.gradle.configuration-cache=false` in `gradle.properties`.

### 4. `plugin.yml` instead of `paper-plugin.yml`

The plan §3.1 specified `paper-plugin.yml`. However, the Paper plugin format
does **not** support classic `commands:` declarations — command registration
must go through Brigadier + Lifecycle API. For Phase 1 with a single
`/astera` command, the cost of wiring Brigadier outweighs the cleaner
soft-dep syntax of `paper-plugin.yml`.

**Resolution:** use traditional `plugin.yml` with `commands:` and
`softdepend:` sections. Migration to `paper-plugin.yml` + Brigadier is a
Phase 2 task once we have a command tree.

### 5. Konsist test is **force-rerun every invocation**

Konsist scans `.kt` files in *other* Gradle modules. Gradle's task input
tracking only sees the architecture-test module's own source, so the test
would cache as UP-TO-DATE even after a domain module added a forbidden
import.

**Resolution:** `tasks.named<Test>("test") { outputs.upToDateWhen { false } }`
in `tools/architecture-test/build.gradle.kts`. Scanning is fast (<1s for
the current code volume); the cost is acceptable.

### 6. `exposed-migration` artifact dropped from version catalog

JetBrains never published an `exposed-migration` JAR for Exposed 1.1.1 —
migration utilities are part of `exposed-core` itself.

**Resolution:** removed from `libs.versions.toml`. Flyway is still
declared as a fallback option but not pulled in by default. Phase 1
relies on Exposed `SchemaUtils` for ad-hoc schema management; Flyway
gets wired when production deployments require explicit migration
versioning.

### 7. Manual constructor wiring instead of Koin in `AsteraPlugin`

The plan said "Koin module bind" in `platform-paper-plugin`. With only
two use cases (`GiveWeaponUseCase`, `FireWeaponUseCase`) and a handful of
adapter instances, manual wiring is more readable than annotation-driven
DI.

**Resolution:** `AsteraPlugin.onEnable` constructs the graph by hand.
Koin annotations + `@Module` setup lands when the use-case count exceeds
~5 (Phase 2).

## Consequences

### Positive
- Phase 1 build works on the actual toolchain available in 2026-05
- All compromises are documented and have clear re-evaluation criteria
- Architecture enforcement (the load-bearing requirement) still works via
  Konsist — the negative test verifies it catches violations

### Negative / Trade-offs
- Less coverage of code style than Detekt would provide (Konsist only covers
  architecture)
- KSP no-config-cache slows cold builds slightly

### Mitigations
- Track Detekt 2.x release and Kotlin JVM_25 support; revisit ~quarterly
- ktfmt or Spotless could be added in a follow-up if style drift becomes
  visible

## References

- [[adr/0001-clean-architecture-with-mc-as-edge]]
- [[adr/0003-kotlin-jdk25-paper-26]]
- 計画書 §9 (Gradle 多モジュール実装プラン)
- `buildSrc/src/main/kotlin/astera.kotlin-common.gradle.kts`
- `gradle.properties`
