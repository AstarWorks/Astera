package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Sparse, transient damage shared by terrain and cover. Full and broken blocks occupy no entry. */
public class BlockHpStore {
    public data class Hit(val damage: Int, val lastHitTick: Long)
    private val worlds = mutableMapOf<WorldId, MutableMap<Vec3, Hit>>()

    public fun worlds(): Set<WorldId> = worlds.keys.toSet()
    public fun damage(world: WorldId, pos: Vec3): Int = worlds[world]?.get(pos)?.damage ?: 0
    public fun snapshot(world: WorldId): Map<Vec3, Hit> = worlds[world]?.toMap() ?: emptyMap()

    public fun hit(world: WorldId, pos: Vec3, maxHp: Int, amount: Int, tick: Long): Int {
        require(maxHp > 0) { "maxHp must be positive" }
        if (amount <= 0) return (maxHp - damage(world, pos)).coerceAtLeast(0)
        val damage = (damage(world, pos).toLong() + amount.coerceAtLeast(0)).coerceAtMost(maxHp.toLong()).toInt()
        if (damage <= 0 || damage >= maxHp) clear(world, pos)
        else worlds.getOrPut(world) { mutableMapOf() }[pos] = Hit(damage, tick)
        return (maxHp - damage).coerceAtLeast(0)
    }

    public fun clear(world: WorldId, pos: Vec3) {
        worlds[world]?.let { cells ->
            cells.remove(pos)
            if (cells.isEmpty()) worlds.remove(world)
        }
    }

    public fun clearWorld(world: WorldId): List<Vec3> = worlds.remove(world)?.keys?.toList() ?: emptyList()

    public fun expire(world: WorldId, tick: Long, idleTicks: Long): List<Vec3> {
        val expired = worlds[world]?.filterValues { tick - it.lastHitTick >= idleTicks }?.keys?.toList() ?: emptyList()
        expired.forEach { clear(world, it) }
        return expired
    }
}
