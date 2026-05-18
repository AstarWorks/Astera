package com.astarworks.astera.core.adapter.paper.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [MicrometerMetricsRegistry] contract:
 *  - `counter().increment()` shows up in the Prometheus exposition output.
 *  - `gauge()` with a supplier is rendered to the exposition output.
 *  - The [MicrometerMetricsRegistry.delegate] is exposed so Phase C can bind
 *    JVM binders into it.
 */
class MicrometerMetricsRegistryTest {

    @Test
    fun `counter increment appears in scrape output`() {
        val registry = MicrometerMetricsRegistry()

        val counter = registry.counter("astera_test_counter")
        counter.increment()
        counter.increment(4.0)

        val scrape = registry.scrape()
        assertThat(scrape).contains("astera_test_counter")
        // Prometheus exposition renders counters with a `_total` suffix when
        // emitted via Micrometer's Prometheus formatter.
        assertThat(scrape).containsPattern("astera_test_counter(_total)? 5\\.0")
    }

    @Test
    fun `gauge with constant supplier is exposed`() {
        val registry = MicrometerMetricsRegistry()

        registry.gauge("astera_test_gauge", supplier = { 42 })

        val scrape = registry.scrape()
        assertThat(scrape).contains("astera_test_gauge")
        assertThat(scrape).contains("42")
    }

    @Test
    fun `gauge supplier is re-read on each scrape`() {
        val registry = MicrometerMetricsRegistry()
        var value = 1.0
        registry.gauge("astera_dynamic_gauge", supplier = { value })

        val first = registry.scrape()
        assertThat(first).containsPattern("astera_dynamic_gauge.*1\\.0")

        value = 7.0
        val second = registry.scrape()
        assertThat(second).containsPattern("astera_dynamic_gauge.*7\\.0")
    }

    @Test
    fun `tags are propagated into the exposition output`() {
        val registry = MicrometerMetricsRegistry()
        registry.counter("astera_tagged_counter", "kind" to "test", "scope" to "unit").increment()

        val scrape = registry.scrape()
        assertThat(scrape).contains("astera_tagged_counter")
        assertThat(scrape).contains("kind=\"test\"")
        assertThat(scrape).contains("scope=\"unit\"")
    }

    @Test
    fun `delegate is exposed for JVM binder registration`() {
        val registry = MicrometerMetricsRegistry()
        // The Phase C orchestrator will bind JvmMemoryMetrics / JvmGcMetrics
        // into delegate. We just confirm the surface exists and is non-null.
        assertThat(registry.delegate).isNotNull
        assertThat(registry.meterRegistry).isSameAs(registry.delegate)
    }
}
