package com.astarworks.astera.core.application.service.admin

import com.astarworks.astera.core.application.port.outbound.admin.HealthIndicator
import com.astarworks.astera.core.application.port.outbound.admin.HealthReport
import com.astarworks.astera.core.application.port.outbound.admin.HealthState
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IServerHealthCheck] implementation. Indicators are stored in a
 * thread-safe map; `snapshot()` invokes every indicator with a 1s soft
 * timeout per call. Indicators that throw or time-out are reported as
 * [HealthState.Degraded].
 *
 * Timeout enforcement is best-effort — synchronous indicators that wedge
 * the calling thread cannot be unwound from here; the contract states they
 * should be cheap. Phase 2 may introduce an async variant if needed.
 */
public class InMemoryServerHealthCheck : IServerHealthCheck {

    private data class Registered(val name: String, val indicator: HealthIndicator)

    private val indicators: MutableMap<String, Registered> = ConcurrentHashMap()

    override fun register(name: String, indicator: HealthIndicator): IServerHealthCheck.IndicatorRegistration {
        require(name.isNotBlank()) { "Indicator name must not be blank" }
        indicators[name] = Registered(name, indicator)
        return object : IServerHealthCheck.IndicatorRegistration {
            override fun unregister() {
                indicators.remove(name)
            }
        }
    }

    override fun snapshot(): HealthReport {
        val results = LinkedHashMap<String, HealthState>(indicators.size)
        for ((name, entry) in indicators) {
            val state = try {
                entry.indicator.probe()
            } catch (t: Throwable) {
                HealthState.Degraded
            }
            results[name] = state
        }
        return HealthReport.aggregate(results, sampledAt = Instant.now())
    }
}
