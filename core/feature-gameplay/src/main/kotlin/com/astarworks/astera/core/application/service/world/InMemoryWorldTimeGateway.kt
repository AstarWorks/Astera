package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.domain.world.WorldId

/**
 * In-memory [IWorldTimeGateway] used at priority 0 until the Paper adapter
 * (Wave 6b) overlays at priority 100.
 *
 * Tracks per-world time and freeze state in `ConcurrentHashMap`s so unit
 * tests can assert side-effects without a Paper server. Time is stored
 * modulo 24000 (vanilla day length).
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryWorldTimeGateway : IWorldTimeGateway {

    private val times: MutableMap<WorldId, Long> = java.util.concurrent.ConcurrentHashMap()
    private val frozen: MutableSet<WorldId> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )

    override fun setTime(world: WorldId, timeOfDay: Long) {
        val normalised = ((timeOfDay % DAY_LENGTH) + DAY_LENGTH) % DAY_LENGTH
        times[world] = normalised
    }

    override fun freezeTime(world: WorldId, frozen: Boolean) {
        if (frozen) this.frozen += world else this.frozen -= world
        // Ensure the world is tracked even if setTime was never called.
        times.putIfAbsent(world, 0L)
    }

    override fun getCurrentTime(world: WorldId): Long? = times[world]

    override fun isTimeFrozen(world: WorldId): Boolean = world in frozen

    public companion object {
        private const val DAY_LENGTH: Long = 24_000L
    }
}
