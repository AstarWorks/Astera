package com.astarworks.astera.core.application.service.instance

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IGameScopeResolver
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InstanceDirectory
import com.astarworks.astera.core.domain.game.EntityRef
import com.astarworks.astera.core.domain.game.GameScope
import com.astarworks.astera.core.domain.game.InstanceId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Slot bookkeeping is Global-owned; each instance lifecycle is owned by its worker strand. */
public class GameInstanceRegistry(
    private val scheduler: IScheduler,
    private val directory: InstanceDirectory,
) : IGameScopeResolver {
    private val instances = ConcurrentHashMap<InstanceId, GameInstance>()
    public fun find(id: InstanceId): GameInstance? = instances[id]
    public fun snapshot(): List<GameInstance> = instances.values.toList()

    public fun allocate(id: InstanceId, slot: Int?, seed: Long): GameInstance {
        check(scheduler.isOnScope(ExecutionScope.Global))
        val instance = GameInstance(id, slot, seed)
        check(instances.putIfAbsent(id, instance) == null) { "Instance already allocated: $id" }
        onStrand(id) { it.transition(GameInstance.State.Building) }
        return instance
    }

    public fun building(id: InstanceId) { onStrand(id) {
        if (it.state == GameInstance.State.Ready || it.state == GameInstance.State.Running) it.transition(GameInstance.State.Draining)
        if (it.state == GameInstance.State.Draining) it.transition(GameInstance.State.Resetting)
        if (it.state == GameInstance.State.Resetting) {
            it.transition(GameInstance.State.Free)
            it.transition(GameInstance.State.Allocating)
            it.transition(GameInstance.State.Building)
        }
    } }
    public fun ready(id: InstanceId) { onStrand(id) {
        if (it.state == GameInstance.State.Resetting) {
            it.transition(GameInstance.State.Free)
            it.transition(GameInstance.State.Allocating)
            it.transition(GameInstance.State.Building)
        }
        if (it.state != GameInstance.State.Ready) it.transition(GameInstance.State.Ready)
    } }
    public fun running(id: InstanceId) { onStrand(id) { if (it.state != GameInstance.State.Running) it.transition(GameInstance.State.Running) } }
    public fun draining(id: InstanceId) { onStrand(id) {
        if (it.state in setOf(GameInstance.State.Building, GameInstance.State.Ready, GameInstance.State.Running)) {
            it.transition(GameInstance.State.Draining)
        }
    } }
    public fun resetting(id: InstanceId) {
        draining(id)
        onStrand(id) { if (it.state == GameInstance.State.Draining) it.transition(GameInstance.State.Resetting) }
    }
    public fun free(id: InstanceId) {
        check(scheduler.isOnScope(ExecutionScope.Global))
        directory.removeInstance(id)
        resetting(id)
        onStrand(id) {
            it.transition(GameInstance.State.Free)
            instances.remove(id, it)
        }
    }

    private fun onStrand(id: InstanceId, block: (GameInstance) -> Unit) {
        scheduler.run(ExecutionScope.Instance(id), Duration.ZERO) { instances[id]?.let(block) }
    }

    override fun resolve(entity: EntityRef): GameScope =
        directory.find(entity.id)?.let { GameScope.Instance(it) } ?: GameScope.OpenWorld(entity.world)
}
