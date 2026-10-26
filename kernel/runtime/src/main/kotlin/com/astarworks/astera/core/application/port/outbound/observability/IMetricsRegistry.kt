package com.astarworks.astera.core.application.port.outbound.observability

/**
 * Outbound port: typed counter / gauge registry + Prometheus scrape.
 *
 * Wave _ops (Phase 1.5 ops): single port covering Micrometer + Prometheus
 * exposition. Adapters live in `core.adapter.paper.observability`.
 *
 * silly-knuth §5: [scrape] cannot fail — Prometheus text exposition is a
 * pure string materialisation of in-memory meters. No `Result<T, E>`.
 */
public interface IMetricsRegistry {

    /**
     * Returns a counter identified by [name] + [tags]. Counters monotonically
     * increase; calling this twice with the same identity returns the same
     * counter (Micrometer dedupes by name+tags).
     */
    public fun counter(name: String, vararg tags: Pair<String, String>): IMetricCounter

    /**
     * Registers a gauge identified by [name] + [tags] whose value is read
     * from [supplier] at each scrape. Idempotent: a second call with the
     * same identity replaces the previous supplier semantics per Micrometer
     * gauge contract.
     */
    public fun gauge(name: String, supplier: () -> Number, vararg tags: Pair<String, String>)

    /** Prometheus exposition format string (`# HELP …\n# TYPE …\n…`). */
    public fun scrape(): String
}

/**
 * Vendor-neutral counter handle. Application code never touches Micrometer
 * types directly.
 */
public interface IMetricCounter {
    public fun increment(amount: Double = 1.0)
}
