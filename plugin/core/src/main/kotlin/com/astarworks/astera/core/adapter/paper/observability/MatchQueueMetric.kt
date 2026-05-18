package com.astarworks.astera.core.adapter.paper.observability

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges the in-process domain event bus to a Prometheus gauge counting
 * currently-active matches.
 *
 * Subscribes to [MatchStarted] / [MatchEnded] on [start] and exposes the
 * running count as `astera_match_active` (Prometheus exposition name; the
 * `_total` suffix is reserved for monotonic counters and would be stripped
 * by Micrometer). The KEDA ScaledObject delivered by Worker α reads this
 * metric to drive game-pod replicas.
 *
 * The gauge is registered once at [start] and floors at 0 (a spurious
 * `MatchEnded` from a crash-recovery scenario must not push the gauge
 * negative — Prometheus counters/gauges accept negatives but a negative
 * "active matches" reading would break KEDA scale-from-zero logic).
 */
public class MatchQueueMetric(
    private val eventBus: IDomainEventBus,
    private val registry: IMetricsRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val activeMatches = AtomicInteger(0)

    private var startedSubscription: IDomainEventBus.Subscription? = null
    private var endedSubscription: IDomainEventBus.Subscription? = null

    /** Current snapshot — exposed for tests and diagnostic logging. */
    public val current: Int
        get() = activeMatches.get()

    public fun start() {
        check(startedSubscription == null && endedSubscription == null) {
            "MatchQueueMetric.start() called twice without intervening stop()"
        }

        startedSubscription = eventBus.subscribe(MatchStarted::class) {
            val now = activeMatches.incrementAndGet()
            log.debug("MatchStarted received — active={}", now)
        }
        endedSubscription = eventBus.subscribe(MatchEnded::class) {
            val now = activeMatches.updateAndGet { previous -> (previous - 1).coerceAtLeast(0) }
            log.debug("MatchEnded received — active={}", now)
        }

        registry.gauge(name = GAUGE_NAME, supplier = { activeMatches.get() })
        log.info("MatchQueueMetric registered — gauge={}", GAUGE_NAME)
    }

    public fun stop() {
        startedSubscription?.cancel()
        endedSubscription?.cancel()
        startedSubscription = null
        endedSubscription = null
    }

    public companion object {
        /**
         * Prometheus gauge name; consumed by the KEDA ScaledObject.
         *
         * No `_total` suffix: Prometheus convention reserves that suffix for
         * monotonic counters, and Micrometer's `PrometheusMeterRegistry`
         * strips it from gauges automatically (resulting in a silent rename
         * if we add it). Keep this constant in sync with
         * `deploy/keda/scaledobject-game.yaml` query field.
         */
        public const val GAUGE_NAME: String = "astera_match_active"
    }
}
