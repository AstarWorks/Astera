package com.astarworks.astera.testfixtures.vanilla

import com.astarworks.astera.core.application.port.outbound.vanilla.IVanillaFeatureSuppressor
import com.astarworks.astera.core.domain.vanilla.VanillaFeature

/**
 * Single-threaded recording fake of [IVanillaFeatureSuppressor] for tests
 * (Wave 5server-harden / ADR-0123).
 *
 * Adds [suppressCalls] / [restoreCalls] counters for assertions that the
 * production InMemory impl doesn't expose (it's policy-free state, not a
 * call recorder).
 */
public class FakeVanillaFeatureSuppressor : IVanillaFeatureSuppressor {

    private val state: MutableSet<VanillaFeature> = mutableSetOf()
    public val suppressCalls: MutableList<VanillaFeature> = mutableListOf()
    public val restoreCalls: MutableList<VanillaFeature> = mutableListOf()

    override fun suppress(feature: VanillaFeature) {
        suppressCalls += feature
        state += feature
    }

    override fun restore(feature: VanillaFeature) {
        restoreCalls += feature
        state -= feature
    }

    override fun isSuppressed(feature: VanillaFeature): Boolean = feature in state

    override fun suppressed(): Set<VanillaFeature> = state.toSet()

    public fun clear() {
        state.clear()
        suppressCalls.clear()
        restoreCalls.clear()
    }
}
