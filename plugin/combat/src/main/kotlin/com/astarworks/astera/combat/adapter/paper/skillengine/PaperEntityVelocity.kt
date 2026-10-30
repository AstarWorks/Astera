package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.geometry.Vec3
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real velocity (blocks per tick) of any entity on Paper. Server-side `Player#getVelocity` stays zero while the client walks, so a
 * player is read as its position delta per tick between two sampled ticks; every other entity answers `getVelocity`.
 * The first sample of a player (or one more than [MAX_AGE] ticks after the last) has no history and answers ZERO: one tick of lag.
 * Asking again within the same tick repeats the answer. Shared by the live-value source and the movement gateway. Main thread only.
 */
public class PaperEntityVelocity(private val scheduler: IScheduler) {
    private class Sample(var tick: Long, var pos: Vec3, var velocity: Vec3)

    private val samples = ConcurrentHashMap<UUID, Sample>()

    public fun of(e: Entity): Vec3 {
        if (e !is Player) return e.velocity.let { Vec3(it.x, it.y, it.z) }
        val now = Vec3(e.location.x, e.location.y, e.location.z)
        val tick = scheduler.currentTick()
        if (samples.size > 256) samples.entries.removeIf { tick - it.value.tick > MAX_AGE }
        val s = samples[e.uniqueId]
        if (s == null || tick - s.tick > MAX_AGE) {
            samples[e.uniqueId] = Sample(tick, now, Vec3.ZERO)
            return Vec3.ZERO
        }
        if (tick == s.tick) return s.velocity
        val dt = (tick - s.tick).toDouble()
        s.velocity = Vec3((now.x - s.pos.x) / dt, (now.y - s.pos.y) / dt, (now.z - s.pos.z) / dt)
        s.pos = now
        s.tick = tick
        return s.velocity
    }

    private companion object {
        const val MAX_AGE = 20L
    }
}
