package com.astarworks.astera.core.application.profiling

/**
 * Marks a function as a profiling sample point (Wave 5u, ADR-0087).
 *
 * Methods annotated with [Profiled] are intended to be wrapped by
 * [ProfiledRunner.run] (or [ProfiledRunner.runSuspending] for `suspend`
 * functions). The annotation itself does NOT auto-instrument — Astera
 * intentionally avoids the Spring AspectJ / Quasar bytecode-rewrite path:
 *
 *  - **No AOP framework**: Spring AOP is irrelevant (no Spring context) and
 *    AspectJ load-time weaving requires a JVM agent which Paper plugin
 *    classloaders cannot install hospitably.
 *  - **Explicit wrap**: production callsites read `ProfiledRunner.run(category)
 *    { realWork() }` — fully visible at the callsite, trivially testable,
 *    no surprise classloader behaviour.
 *
 * The annotation stays in the source so Konsist can enforce
 * "@Profiled methods live in application/ or adapter/, never domain/"
 * (rule added in Wave 5u; see ArchitectureTest §"Wave 5u Konsist rules").
 * It is also the human-discoverable mark: a code search for `@Profiled`
 * surfaces every profiled hot-path for tuning sessions.
 *
 * [category] groups samples for the flamegraph aggregation pass. Suggested
 * vocabulary: `combat.damage`, `match.lifecycle`, `world.block`,
 * `economy.shop`, `gui.render`.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Profiled(val category: String = "default")
