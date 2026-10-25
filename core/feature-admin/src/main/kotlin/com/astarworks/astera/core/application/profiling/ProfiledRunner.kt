package com.astarworks.astera.core.application.profiling

import com.astarworks.astera.core.application.port.outbound.profiling.IProfilingCollector

/**
 * Compile-time partner to the [Profiled] annotation (Wave 5u, ADR-0087).
 *
 * Production sites wrap profiled blocks with `ProfiledRunner.run(collector,
 * "combat.damage", "ApplyDamageUseCase.execute") { … }`. The runner is a
 * pure Kotlin construct — no reflection, no proxy, no AOP framework. The
 * `@Profiled` annotation exists as a discoverability mark and a Konsist
 * enforcement hook (rule "@Profiled annotated method must be in
 * application or adapter layer").
 *
 * # Why not Spring AOP / AspectJ / kapt
 *
 *  - **Spring AOP** assumes a Spring `ApplicationContext`; Astera has none.
 *  - **AspectJ load-time weaving** requires a JVM agent flag, which Paper
 *    plugin classloaders cannot install with reasonable ergonomics — every
 *    operator would have to edit start-server.sh.
 *  - **Kotlin compiler plugin** (kapt / KSP rewrite) would tie us to a
 *    Kotlin version more tightly than the rest of the codebase. The Wave
 *    5u footprint doesn't justify that lock-in.
 *
 * Explicit wrap wins on every axis except keystroke count, and the
 * keystroke-count loss is purely at hot-path callsites that are written
 * once and changed rarely.
 */
public object ProfiledRunner {

    /**
     * Times [block] and records the duration into [collector] under the
     * given [category] + [methodName]. Returns the block's value. If the
     * block throws, the duration is still recorded so error paths show up
     * in the flamegraph too.
     */
    public inline fun <T> run(
        collector: IProfilingCollector,
        category: String,
        methodName: String,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsed = System.nanoTime() - start
            collector.record(category, methodName, elapsed)
        }
    }
}
