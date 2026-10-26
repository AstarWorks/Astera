package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/** One local phase per active chunk. Folia runs these on the chunk's current owning region. */
public class PaperCombatPhase(private val scheduler: IScheduler) : AutoCloseable {
    private class Batch(val nextStatusTick: Long) {
        val projectiles = ConcurrentSkipListSet<UUID>()
        val statuses = ConcurrentSkipListSet<UUID>()
        @Volatile var handle: IScheduler.TaskHandle? = null
        var statusDue = nextStatusTick
    }
    private val startTick = scheduler.currentTick()
    private val batches = ConcurrentHashMap<ExecutionScope.Region, Batch>()
    private val projectileOwners = ConcurrentHashMap<UUID, ExecutionScope.Region>()
    private val statusOwners = ConcurrentHashMap<UUID, ExecutionScope.Region>()
    @Volatile private var closed = false
    public var tickProjectiles: (List<UUID>) -> Unit = {}
    public var tickStatuses: (List<PlayerId>) -> Unit = {}

    public fun projectile(id: UUID, world: WorldId, at: Vec3) {
        val key = regionScope(world, at)
        val old = projectileOwners.put(id, key)
        if (old != key && old != null) batches[old]?.projectiles?.remove(id)
        batches.compute(key) { _, batch -> (batch ?: newBatch(key)).also { it.projectiles.add(id) } }
    }
    public fun removeProjectile(id: UUID) { projectileOwners.remove(id)?.let { batches[it]?.projectiles?.remove(id) } }
    public fun status(id: UUID, world: WorldId, at: Vec3) {
        val key = regionScope(world, at)
        val old = statusOwners.put(id, key)
        if (old != key && old != null) batches[old]?.statuses?.remove(id)
        batches.compute(key) { _, batch -> (batch ?: newBatch(key)).also { it.statuses.add(id) } }
    }
    public fun entityMoved(id: UUID, world: WorldId, at: Vec3) {
        if (statusOwners.containsKey(id)) status(id, world, at)
    }
    public fun removeStatus(id: UUID) { statusOwners.remove(id)?.let { batches[it]?.statuses?.remove(id) } }

    private fun newBatch(key: ExecutionScope.Region): Batch {
        check(!closed)
        val now = scheduler.currentTick()
        val next = startTick + ((now - startTick) / 20 + 1) * 20
        return Batch(next).also { batch ->
            batch.handle = scheduler.runRepeatingTicks(key, 1, 1) {
                ExecutionCosts.measure("combat.phase", "all") {
                    com.astarworks.astera.core.application.service.observability.CombatTickBudget.enter(scheduler.currentTick(), key)
                    val shots = batch.projectiles.filter { projectileOwners[it] == key }
                    if (shots.isNotEmpty()) tickProjectiles(shots)
                    if (scheduler.currentTick() >= batch.statusDue) {
                        batch.statusDue += 20
                        val players = batch.statuses.filter { statusOwners[it] == key }.map(::PlayerId)
                        if (players.isNotEmpty()) tickStatuses(players)
                    }
                    com.astarworks.astera.core.application.service.observability.CombatTickBudget.overrun(scheduler.currentTick())
                    batches.computeIfPresent(key) { _, current ->
                        if (current === batch && current.projectiles.isEmpty() && current.statuses.isEmpty()) {
                            current.handle?.cancel()
                            null
                        } else current
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        batches.values.forEach { it.handle?.cancel() }
        batches.clear(); projectileOwners.clear(); statusOwners.clear()
    }
}
