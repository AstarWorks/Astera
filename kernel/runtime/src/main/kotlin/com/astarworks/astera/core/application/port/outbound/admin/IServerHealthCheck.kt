package com.astarworks.astera.core.application.port.outbound.admin

import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Health state report for graceful shutdown + readiness
 * probes (kind / k8s).
 *
 * Returns a structured [HealthReport] aggregated from multiple [HealthIndicator]
 * sources (plugins register their own indicator at `onEnable`). The aggregator
 * is read-only: it never mutates state; it never blocks on slow dependencies.
 * Each indicator is invoked with a small per-indicator timeout (default 1s);
 * if it does not return in time the indicator is marked [HealthState.Degraded].
 *
 * Kubernetes mapping (Wave _ops / Phase 1.5):
 *   - `/healthz` → snapshot.overall == Healthy
 *   - `/readyz`  → snapshot.overall != Down (Healthy ∪ Degraded)
 *
 * Graceful shutdown ordering (Wave 5w drives this):
 *   1. Flip overall → Down (stops k8s sending new traffic).
 *   2. Drain in-flight matches (Wave 5w `IServerLifecycleListener`).
 *   3. Flush reward outbox.
 *   4. Close DB.
 *
 * The port is intentionally minimal — Phase 1 PoC; richer dependency graph
 * (load shedding, per-region health) lands in Phase 2 alongside spark
 * adapter (idea-observability-depth.md).
 */
public interface IServerHealthCheck {

    /** Register a named indicator. Returns a handle for later removal. */
    public fun register(name: String, indicator: HealthIndicator): IndicatorRegistration

    /**
     * Probe every indicator and return the aggregated snapshot. The overall
     * state is the **worst** indicator (Down > Degraded > Healthy).
     */
    public fun snapshot(): HealthReport

    /** Handle for un-registering a [HealthIndicator]. */
    public interface IndicatorRegistration {
        public fun unregister()
    }
}

/**
 * Synchronous health probe. Phase 1 stays synchronous — indicators are
 * expected to do cheap in-memory checks (queue length, connection liveness
 * flag) and not block on network I/O. Phase 2 may add an `awaitHealth`
 * suspending variant if expensive probes appear.
 */
public fun interface HealthIndicator {
    public fun probe(): HealthState
}

/** State of a single indicator. */
public enum class HealthState {
    Healthy,
    Degraded,
    Down,
}

/**
 * Aggregated snapshot. [individuals] is the raw per-indicator map (handy for
 * `/astera health` admin command and Web UI).
 */
public data class HealthReport(
    val overall: HealthState,
    val individuals: Map<String, HealthState>,
    val sampledAt: Instant,
) {
    public companion object {
        public fun aggregate(
            individuals: Map<String, HealthState>,
            sampledAt: Instant = Instant.now(),
        ): HealthReport {
            val overall = when {
                individuals.isEmpty() -> HealthState.Healthy
                individuals.values.any { it == HealthState.Down } -> HealthState.Down
                individuals.values.any { it == HealthState.Degraded } -> HealthState.Degraded
                else -> HealthState.Healthy
            }
            return HealthReport(overall, individuals, sampledAt)
        }
    }
}
