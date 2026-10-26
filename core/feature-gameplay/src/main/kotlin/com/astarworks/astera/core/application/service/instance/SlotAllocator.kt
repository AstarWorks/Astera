package com.astarworks.astera.core.application.service.instance

import com.astarworks.astera.core.domain.stage.StageId

/** Global owns mutations. Immutable snapshots allow region/worker reads (ADR-0255). */
public class SlotAllocator(
    private val instances: GameInstanceRegistry,
    private val minX: Int = 0,
    private val minZ: Int = 0,
    private val columns: Int = 32,
    private val rows: Int = 32,
) {
    public enum class State { Building, Ready, Running, Resetting }
    public data class Slot(val instanceId: com.astarworks.astera.core.domain.game.InstanceId, val index: Int, val stage: StageId, val x: Int, val z: Int, val state: State)
    @Volatile private var slots: Map<Int, Slot> = mapOf(-1 to Slot(com.astarworks.astera.core.domain.game.InstanceId("lobby"), -1, StageId("lobby"), 0, 0, State.Building))

    init {
        require(columns > 0 && rows > 0 && columns.toLong() * rows <= 100_000)
        require(minX.toLong() * PITCH > -29_000_000 && (minX.toLong() + columns) * PITCH < 29_000_000)
        require(minZ.toLong() * PITCH > -29_000_000 && (minZ.toLong() + rows) * PITCH < 29_000_000)
    }

    /** Explicit Global bootstrap: construction itself never writes the instance registry. */
    public fun initializeLobby() {
        val id = com.astarworks.astera.core.domain.game.InstanceId("lobby")
        if (instances.find(id) == null) instances.allocate(id, -1, 0L)
    }

    public fun snapshot(): List<Slot> = slots.values.toList()

    /** The origin is the single lobby (index -1), reserved even on game-only backends. */
    public fun allocate(stage: StageId): Slot? {
        val index = (0 until columns * rows).firstOrNull {
            it !in slots && (minX + it % columns != 0 || minZ + it / columns != 0)
        } ?: return null
        val id = newInstance(index)
        val slot = Slot(id, index, stage, (minX + index % columns) * PITCH, (minZ + index / columns) * PITCH, State.Building)
        slots = slots + (index to slot)
        return slot
    }

    public fun state(index: Int, state: State) {
        val slot = slots[index] ?: return
        when (state) {
            State.Building -> instances.building(slot.instanceId)
            State.Ready -> instances.ready(slot.instanceId)
            State.Running -> instances.running(slot.instanceId)
            State.Resetting -> instances.resetting(slot.instanceId)
        }
        slots = slots + (index to slot.copy(state = state))
    }

    /** A successfully repasted warm slot starts a new run, with a fresh identity (never reuses a MatchId). */
    public fun reallocate(index: Int): Slot {
        val previous = checkNotNull(slots[index])
        check(previous.state == State.Resetting)
        instances.free(previous.instanceId)
        return previous.copy(instanceId = newInstance(index), state = State.Building).also { slots = slots + (index to it) }
    }

    private fun newInstance(index: Int): com.astarworks.astera.core.domain.game.InstanceId {
        val uuid = java.util.UUID.randomUUID()
        val id = com.astarworks.astera.core.domain.game.InstanceId("slot-$index-$uuid")
        instances.allocate(id, index, com.astarworks.astera.core.application.port.outbound.castSeed(0L, uuid, 0L))
        return id
    }

    public fun free(index: Int) {
        slots[index]?.let { instances.free(it.instanceId) }
        slots = slots - index
    }

    public companion object { public const val PITCH: Int = 2048 }
}
