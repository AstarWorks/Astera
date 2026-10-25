package com.astarworks.astera.core.application.service.instance

import com.astarworks.astera.core.domain.stage.StageId

/** Global owns mutations. Immutable snapshots allow region/worker reads (ADR-0255). */
public class SlotAllocator(
    private val minX: Int = 0,
    private val minZ: Int = 0,
    private val columns: Int = 32,
    private val rows: Int = 32,
) {
    public enum class State { Building, Ready, Running, Resetting }
    public data class Slot(val index: Int, val stage: StageId, val x: Int, val z: Int, val state: State)
    @Volatile private var slots: Map<Int, Slot> = mapOf(-1 to Slot(-1, StageId("lobby"), 0, 0, State.Building))

    init {
        require(columns > 0 && rows > 0 && columns.toLong() * rows <= 100_000)
        require(minX.toLong() * PITCH > -29_000_000 && (minX.toLong() + columns) * PITCH < 29_000_000)
        require(minZ.toLong() * PITCH > -29_000_000 && (minZ.toLong() + rows) * PITCH < 29_000_000)
    }

    public fun snapshot(): List<Slot> = slots.values.toList()

    /** The origin is the single lobby (index -1), reserved even on game-only backends. */
    public fun allocate(stage: StageId): Slot? {
        val index = (0 until columns * rows).firstOrNull {
            it !in slots && (minX + it % columns != 0 || minZ + it / columns != 0)
        } ?: return null
        val slot = Slot(index, stage, (minX + index % columns) * PITCH, (minZ + index / columns) * PITCH, State.Building)
        slots = slots + (index to slot)
        return slot
    }

    public fun state(index: Int, state: State) {
        val slot = slots[index] ?: return
        slots = slots + (index to slot.copy(state = state))
    }

    public fun free(index: Int) { slots = slots - index }

    public companion object { public const val PITCH: Int = 2048 }
}
