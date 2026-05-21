package com.astarworks.astera.combat.testfixtures.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Recording fake for [IFireSpreadController] (Wave 5m / ADR-0088).
 *
 * Tracks per-world toggle state in memory and records every
 * [spreadFire] call. [spreadFire] returns the configured
 * [defaultIgnited] count so tests can vary it.
 */
public class FakeFireSpreadController(
    initial: Map<WorldId, Boolean> = emptyMap(),
    public var defaultIgnited: Int = 0,
) : IFireSpreadController {

    public data class SpreadCall(
        public val world: WorldId,
        public val source: Vec3,
        public val radius: Int,
    )

    private val state: MutableMap<WorldId, Boolean> = LinkedHashMap(initial)
    public val spreadCalls: MutableList<SpreadCall> = mutableListOf()

    override fun isFireSpreadEnabled(world: WorldId): Boolean = state.getOrPut(world) { true }

    override fun setFireSpread(world: WorldId, enabled: Boolean) {
        state[world] = enabled
    }

    override fun spreadFire(world: WorldId, source: Vec3, radius: Int): Int {
        spreadCalls += SpreadCall(world, source, radius)
        return defaultIgnited
    }
}
