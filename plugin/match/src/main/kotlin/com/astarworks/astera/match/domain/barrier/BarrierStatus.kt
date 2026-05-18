package com.astarworks.astera.match.domain.barrier

/**
 * Lifecycle state for a [Barrier] (Wave 5d).
 *
 * Driven by `BarrierStatusScheduler`: a freshly created barrier starts
 * [Active], may transition to [Disabled] when a use case (e.g. anomaly,
 * sabotage skill) suspends it, and ends in [Destroyed] when its underlying
 * block aggregate breaks. The sealed shape leaves room for richer state
 * (e.g. Recharging, Compromised) without revising the gateway port.
 */
public sealed class BarrierStatus {
    public data object Active : BarrierStatus()
    public data object Disabled : BarrierStatus()
    public data object Destroyed : BarrierStatus()
}
