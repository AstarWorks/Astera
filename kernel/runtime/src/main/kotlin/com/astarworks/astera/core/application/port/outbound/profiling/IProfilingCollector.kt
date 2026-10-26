package com.astarworks.astera.core.application.port.outbound.profiling

/**
 * Outbound port: profiling sample collector (Wave 5u, ADR-0087).
 *
 * Accepts per-method timing samples from
 * [com.astarworks.astera.core.application.profiling.ProfiledRunner.run] (the
 * compile-time partner of the [com.astarworks.astera.core.application.profiling.Profiled]
 * annotation). Default impl
 * ([com.astarworks.astera.core.application.profiling.InMemoryProfilingCollector])
 * accumulates samples into a `(category, methodName) → durationNanos`
 * histogram and renders to Brendan Gregg's `flamegraph.pl` collapsed format
 * on demand.
 *
 * silly-knuth §5: `record` is fire-and-forget — profiling cannot fail.
 * `export` may return [ProfilingSnapshot.empty] when no samples have been
 * collected (a degenerate but legitimate result, not an error).
 */
public interface IProfilingCollector {

    /** Append a sample. Called from inside `ProfiledRunner.run`'s finally block. */
    public fun record(category: String, methodName: String, durationNanos: Long)

    /**
     * Materialise the current sample set into a snapshot suitable for
     * Brendan Gregg's `flamegraph.pl` collapsed format:
     *
     * ```
     * category;methodName <duration-sum-in-nanos>
     * ```
     *
     * Lines are sorted by descending duration so visual inspection surfaces
     * the hot path first.
     */
    public fun export(): ProfilingSnapshot

    /** Drop all samples. Used by `/astera profiling export --reset` for a fresh window. */
    public fun reset()
}

/**
 * Aggregated profiling samples ready for flamegraph rendering.
 *
 * # Format contract
 *
 * `flamegraph.pl` (Brendan Gregg) accepts:
 *
 * ```
 * <stack-frame-1>;<stack-frame-2>;<...> <integer-count-or-time>
 * ```
 *
 * Astera renders one frame deep (`<category>;<method>`) per row — flame
 * width = total time spent in that method across samples. Multi-frame call
 * stacks are deferred to Phase 2 spark integration (idea-observability-depth.md §A).
 */
public data class ProfilingSnapshot(
    public val samples: List<ProfilingSample>,
) {
    /** Brendan Gregg `flamegraph.pl` collapsed format string. */
    public fun toFlamegraphFormat(): String =
        samples
            .sortedByDescending { it.totalNanos }
            .joinToString(separator = "\n") { sample ->
                "${sample.category};${sample.methodName} ${sample.totalNanos}"
            }

    public companion object {
        public val empty: ProfilingSnapshot = ProfilingSnapshot(emptyList())
    }
}

public data class ProfilingSample(
    public val category: String,
    public val methodName: String,
    /** Sum of durations across every recorded invocation, nanoseconds. */
    public val totalNanos: Long,
    /** Number of invocations contributing to [totalNanos]. */
    public val invocationCount: Long,
)
