package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory binding for [IFireSpreadController] (Wave 5m, ADR-0088).
 *
 * Tracks per-world enabled state in a [LinkedHashMap] (default `true`,
 * matching vanilla Paper's default for new worlds). [spreadFire] always
 * returns `0` — no world available to ignite. Wave 6a's
 * `PaperFireSpreadController` overlays this with the live world.
 */
public class InMemoryFireSpreadController(
    initialState: Map<WorldId, Boolean> = emptyMap(),
) : IFireSpreadController {

    private val state: MutableMap<WorldId, Boolean> = LinkedHashMap(initialState)

    override fun isFireSpreadEnabled(world: WorldId): Boolean =
        state.getOrPut(world) { true }

    override fun setFireSpread(world: WorldId, enabled: Boolean) {
        state[world] = enabled
    }

    override fun spreadFire(world: WorldId, source: Vec3, radius: Int): Int = 0
}
