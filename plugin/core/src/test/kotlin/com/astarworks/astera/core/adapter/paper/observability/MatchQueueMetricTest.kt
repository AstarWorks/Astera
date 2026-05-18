package com.astarworks.astera.core.adapter.paper.observability

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end contract for [MatchQueueMetric]:
 *  - Publishes through the real [InMemoryDomainEventBus] so subscription
 *    wiring is exercised (no mock event bus).
 *  - After 2× [MatchStarted] + 1× [MatchEnded], the gauge reads 1.
 *  - The gauge floors at 0 if [MatchEnded] outruns [MatchStarted].
 *  - [MatchQueueMetric.stop] cancels both subscriptions.
 */
class MatchQueueMetricTest {

    @Test
    fun `2 starts and 1 end yield active=1 in the scrape output`() {
        val bus = InMemoryDomainEventBus()
        val registry = MicrometerMetricsRegistry()
        val metric = MatchQueueMetric(bus, registry)

        metric.start()
        bus.publish(matchStarted("m-1"))
        bus.publish(matchStarted("m-2"))
        bus.publish(matchEnded("m-1"))

        assertThat(metric.current).isEqualTo(1)

        val scrape = registry.scrape()
        assertThat(scrape).contains(MatchQueueMetric.GAUGE_NAME)
        // Prometheus exposition for a gauge of value 1: `astera_match_active 1.0`
        // (Micrometer strips the `_total` suffix from gauges — that suffix is
        // reserved for counters per Prometheus naming conventions.)
        assertThat(scrape).containsPattern("${MatchQueueMetric.GAUGE_NAME} 1\\.0")
    }

    @Test
    fun `gauge floors at zero when ends outrun starts`() {
        val bus = InMemoryDomainEventBus()
        val registry = MicrometerMetricsRegistry()
        val metric = MatchQueueMetric(bus, registry)

        metric.start()
        bus.publish(matchEnded("m-rogue"))
        bus.publish(matchEnded("m-rogue-2"))

        assertThat(metric.current).isEqualTo(0)
        assertThat(registry.scrape()).containsPattern("${MatchQueueMetric.GAUGE_NAME} 0\\.0")
    }

    @Test
    fun `stop cancels subscriptions so further events are ignored`() {
        val bus = InMemoryDomainEventBus()
        val registry = MicrometerMetricsRegistry()
        val metric = MatchQueueMetric(bus, registry)

        metric.start()
        bus.publish(matchStarted("m-1"))
        assertThat(metric.current).isEqualTo(1)

        metric.stop()
        bus.publish(matchStarted("m-2"))
        bus.publish(matchEnded("m-1"))

        // Subscriptions are cancelled; the counter must not move.
        assertThat(metric.current).isEqualTo(1)
    }

    @Test
    fun `start twice fails fast`() {
        val bus = InMemoryDomainEventBus()
        val registry = MicrometerMetricsRegistry()
        val metric = MatchQueueMetric(bus, registry)

        metric.start()
        val ex = runCatching { metric.start() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }

    private fun matchStarted(id: String): MatchStarted = MatchStarted(
        matchId = MatchId(id),
        stageId = StageId("test-stage"),
        atTick = 0L,
    )

    private fun matchEnded(id: String): MatchEnded = MatchEnded(
        matchId = MatchId(id),
        winningTeam = null,
        atTick = 0L,
        reason = EndReason.AdminEnded,
    )
}
