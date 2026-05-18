package com.astarworks.astera.core.adapter.paper.observability

import com.astarworks.astera.core.application.port.outbound.observability.IMetricCounter
import com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory

/**
 * Micrometer-backed [IMetricsRegistry] adapter using the Prometheus exposition
 * registry (Micrometer 1.14+ — package relocated from
 * `io.micrometer.prometheus` to `io.micrometer.prometheusmetrics`).
 *
 * The underlying [delegate] is exposed `public` so Phase C / orchestrator can
 * bind JVM binders (`JvmMemoryMetrics`, `JvmGcMetrics`, `ProcessorMetrics`,
 * etc.) at plugin enable time without re-implementing them through the port.
 */
public class MicrometerMetricsRegistry(
    public val delegate: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) : IMetricsRegistry {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Secondary access as the vendor-neutral [MeterRegistry] for JVM binders. */
    public val meterRegistry: MeterRegistry get() = delegate

    override fun counter(name: String, vararg tags: Pair<String, String>): IMetricCounter {
        val counter = Counter.builder(name)
            .tags(tags.toTagList())
            .register(delegate)
        return CounterAdapter(counter)
    }

    override fun gauge(name: String, supplier: () -> Number, vararg tags: Pair<String, String>) {
        Gauge.builder(name) { supplier().toDouble() }
            .tags(tags.toTagList())
            .register(delegate)
    }

    override fun scrape(): String = delegate.scrape()

    init {
        log.debug("MicrometerMetricsRegistry initialised (delegate={})", delegate.javaClass.simpleName)
    }

    private class CounterAdapter(private val counter: Counter) : IMetricCounter {
        override fun increment(amount: Double) {
            counter.increment(amount)
        }
    }

    private companion object {
        fun Array<out Pair<String, String>>.toTagList(): List<Tag> =
            map { (k, v) -> Tag.of(k, v) }
    }
}
