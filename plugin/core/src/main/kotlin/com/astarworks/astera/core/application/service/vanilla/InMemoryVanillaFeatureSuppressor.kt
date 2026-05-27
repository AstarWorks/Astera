package com.astarworks.astera.core.application.service.vanilla

import com.astarworks.astera.core.application.port.outbound.vanilla.IVanillaFeatureSuppressor
import com.astarworks.astera.core.domain.vanilla.VanillaFeature
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory state-only implementation of [IVanillaFeatureSuppressor]
 * (Wave 5server-harden / ADR-0123).
 *
 * Holds a thread-safe set of suppressed [VanillaFeature]s. The actual
 * Bukkit-side suppression (cancelling events, setting game rules) is the
 * Wave 6b `PaperVanillaFeatureSuppressor` adapter's job — that adapter
 * observes this register and reacts.
 *
 * The split keeps the port vendor-neutral (ADR-0002) and lets domain code
 * test against this default without bringing Paper into the test classpath.
 */
public class InMemoryVanillaFeatureSuppressor : IVanillaFeatureSuppressor {

    private val state: MutableSet<VanillaFeature> =
        Collections.newSetFromMap(ConcurrentHashMap<VanillaFeature, Boolean>())

    override fun suppress(feature: VanillaFeature) {
        state.add(feature)
    }

    override fun restore(feature: VanillaFeature) {
        state.remove(feature)
    }

    override fun isSuppressed(feature: VanillaFeature): Boolean = state.contains(feature)

    override fun suppressed(): Set<VanillaFeature> = state.toSet()
}
