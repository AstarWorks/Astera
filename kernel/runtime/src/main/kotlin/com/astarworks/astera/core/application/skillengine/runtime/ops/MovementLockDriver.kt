package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import java.util.UUID

/**
 * Runtime of [com.astarworks.astera.core.domain.skillengine.op.MovementOp.Lock]: holds entities in place during
 * windup / cast phases. Locks end by their tick count, by `unlock`, or when the cast ends or is cancelled
 * ([cancel]); several locks on one entity apply the strictest and the gateway is released once none remain.
 * The runner calls [tick] once per server tick.
 */
public class MovementLockDriver(private val movement: ISkillMovementGateway) {
    private class Lock(val cast: CastId, val entity: UUID, val speed: Double, var remaining: Int?)

    private val active: MutableList<Lock> = ArrayList()

    public fun lock(cast: CastId, entity: UUID, speed: Double, ticks: Int?) {
        if (!movement.isAlive(entity)) return
        active += Lock(cast, entity, speed.coerceIn(0.0, 1.0), ticks?.coerceAtLeast(1))
        apply(entity)
    }

    /** Releases the locks [cast] placed on [entity]. */
    public fun unlock(cast: CastId, entity: UUID) {
        release { it.cast == cast && it.entity == entity }
    }

    public fun tick() {
        val expired = HashSet<UUID>()
        for (l in active.toList()) {
            if (!movement.isAlive(l.entity)) {
                active -= l
                continue
            }
            val left = l.remaining ?: continue
            l.remaining = left - 1
            if (left - 1 <= 0) {
                active -= l
                expired += l.entity
            }
        }
        expired.forEach(::apply)
    }

    /** Cast ended or cancelled. */
    public fun cancel(cast: CastId) {
        release { it.cast == cast }
    }

    /** Plugin disable. */
    public fun shutdown() {
        release { true }
    }

    public fun isLocked(entity: UUID): Boolean = active.any { it.entity == entity }

    private fun release(match: (Lock) -> Boolean) {
        val gone = active.filter(match)
        active.removeAll(gone.toSet())
        gone.map { it.entity }.toSet().forEach(::apply)
    }

    private fun apply(entity: UUID) {
        val mine = active.filter { it.entity == entity }
        if (mine.isEmpty()) {
            movement.unlockMovement(entity)
            return
        }
        movement.lockMovement(entity, mine.minOf { it.speed })
    }
}
