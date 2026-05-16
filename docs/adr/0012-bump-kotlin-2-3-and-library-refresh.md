---
status: Accepted
date: 2026-05-17
deciders: ryuzu
supersedes: [adr/0011-phase-1-implementation-deviations#decision-2]
---

# ADR-0012: Bump to Kotlin 2.3 + drop JVM_24 fallback + library refresh

## Status

Accepted.

Supersedes Decision 2 of [[adr/0011-phase-1-implementation-deviations]] (the asymmetric
Kotlin JVM_24 / Java VERSION_25 workaround). Other ADR-0011 decisions remain in force.

## Context

Phase 1 shipped on Kotlin 2.2.20, which capped `JvmTarget` at `JVM_24` while Paper
26.x required consumers at JVM 25. ADR-0011 documented the asymmetric workaround
plus `kotlin.jvm.target.validation.mode=warning` in `gradle.properties`.

[Kotlin 2.3.0 (released 2025-12)](https://blog.jetbrains.com/kotlin/2025/12/kotlin-2-3-0-released/)
added native JVM target 25 support. Plus, several other Astera dependencies have
shipped new stable releases since Phase 1 close.

The user asked (2026-05-17) for a fresh "research the latest stable for every
library" pass before doing the core-foundation polish in §11.

## Decision

Bump all dependencies to their latest stable (no RC / Beta / Alpha / Milestone)
as of 2026-05-17. The full matrix lives in `gradle/libs.versions.toml`; the
headline changes:

| Library | Phase 1 end | After this ADR | Reason |
|---|---|---|---|
| Kotlin | 2.2.20 | **2.3.21** | JVM 25 native target |
| KSP | 2.2.20-2.0.4 | **2.3.8** | New KSP2 numbering for Kotlin 2.3.x |
| Koin core | 4.0.0 | **4.2.1** | Routine |
| Koin annotations | 2.0.0 | **2.3.1** | Stays on 2.x stream; ksp-compiler 4.x not published |
| kaml | 0.66.0 | **0.104.0** | Big jump, API verified compatible |
| kotlinx-coroutines | 1.10.1 | **1.11.0** | Routine |
| SLF4J | 2.0.16 | **2.0.18** | Stable line (2.1.x still alpha) |
| Logback | 1.5.12 | **1.5.32** | Routine |
| PostgreSQL JDBC | 42.7.5 | **42.7.11** | Routine |
| Exposed | 1.1.1 | **1.3.0** | Astera independent of AstarManagement's 1.1.1 |
| Flyway | 11.1.0 | **12.6.1** | Major bump |
| Lettuce | 6.5.3 | **7.5.2.RELEASE** | Major bump |
| JUnit Jupiter | 5.11.4 | **6.0.3** | Major bump (6.1 still RC) |
| junit-platform-launcher | 1.11.4 | **6.0.3** | Aligned with Jupiter 6 |
| AssertJ | 3.27.0 | **3.27.7** | Patch (4.0 still milestone) |
| Testcontainers | 1.20.4 | **2.0.5** | Major bump |
| MockK | 1.13.13 | **1.14.9** | Routine |
| Konsist | 0.17.0 | **0.17.3** | Patch |
| Detekt | 1.23.7 | **1.23.8** | Patch (still disabled awaiting 2.x) |
| Shadow | 8.3.6 | **9.4.1** | Gradle 9 native |

Concurrent changes:

1. `buildSrc/.../astera.kotlin-common.gradle.kts`:
   `compilerOptions.jvmTarget = JvmTarget.JVM_25` (was `JVM_24`).
2. `gradle.properties`:
   `kotlin.jvm.target.validation.mode=error` (was `warning`) — Kotlin and Java now agree.

Detekt stays disabled (1.23.x still cannot parse JDK 25; ADR-0011 Decision 1 unchanged).
Konsist remains the load-bearing architecture gate.

## Consequences

### Positive

- Symmetric JVM target. The asymmetric workaround documented in ADR-0011 is gone.
- Latest-stable across the board reduces the upgrade-debt window before Phase 2 mid.
- `./gradlew check` passed cleanly with **zero source-code changes** required from
  any module (kaml, JUnit 6, Konsist, Koin, Shadow are all backward compatible
  for Astera's current usage).
- shadowJar still produces a valid plugin jar (~24.6 MB).

### Negative / Trade-offs

- Astera Exposed (1.3.0) diverges from AstarManagement Exposed (1.1.1). Documented
  in `docs/adr/0005-postgres-not-mongo.md` follow-up note.
- Some dependencies (Lettuce 7, Testcontainers 2, JUnit 6, Flyway 12) crossed major
  versions; surface for breakage exists once they're actually used (Phase 2 mid+).
  Currently they are version-pinned but not exercised.

### Mitigations

- `./gradlew check` is the safety net: the Konsist + unit-test suite would catch
  any compile-time regression from a future code change that touches these libs.
- When Phase 2 mid begins to use Lettuce / Testcontainers / Flyway in earnest,
  re-verify their major-version API at that point.

## Alternatives Considered

- **Keep Phase 1 versions**: stable but accrues upgrade debt and forfeits Kotlin
  2.3's native JVM 25 (which directly simplifies the build config).
- **Bump to Kotlin 2.4.0-RC**: tempting (already on Maven Central) but RC quality
  is a regression risk. Wait for 2.4.0 final.

## References

- [Kotlin 2.3.0 release notes](https://kotlinlang.org/docs/whatsnew23.html)
- [Kotlin 2.3.0 Released](https://blog.jetbrains.com/kotlin/2025/12/kotlin-2-3-0-released/)
- [[adr/0003-kotlin-jdk25-paper-26]]
- [[adr/0011-phase-1-implementation-deviations]]
- 計画書 §11.0 (library refresh matrix)
- `gradle/libs.versions.toml`
- `buildSrc/src/main/kotlin/astera.kotlin-common.gradle.kts`
- `gradle.properties`
