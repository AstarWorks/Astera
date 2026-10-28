package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyToolBay
import com.astarworks.astera.core.domain.lobby.LobbyBlockPos
import com.astarworks.astera.core.domain.lobby.LobbyToolBayPlan
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Block access of the lobby world, called on the owning region thread of the block. States are block-data strings. */
public interface IToolBayWorld {
    public fun stateAt(x: Int, y: Int, z: Int): String
    public fun setState(x: Int, y: Int, z: Int, state: String)
}

/**
 * Journal + idle timer of the tool bay (MAP-A6). The first state seen at a position is the one that is restored,
 * so repeated edits of the same block still come back as the generated block. Restores run per chunk on that
 * chunk's region.
 */
public class LobbyToolBayService(
    override val world: WorldId,
    override val plan: LobbyToolBayPlan,
    private val blocks: IToolBayWorld,
    private val scheduler: IScheduler,
) : ILobbyToolBay {
    private val lock = Any()
    private val original = LinkedHashMap<LobbyBlockPos, String>()
    private var lastChange = 0L

    override fun contains(inWorld: WorldId?, x: Int, y: Int, z: Int): Boolean =
        (inWorld == null || inWorld == world) && plan.region.contains(x + 0.5, y + 0.5, z + 0.5)

    override fun editPolicy(inWorld: WorldId?, x: Int, y: Int, z: Int): Boolean? {
        if (inWorld != null && inWorld != world) return null
        if (kotlin.math.abs(x) >= LOBBY_SLOT_HALF || kotlin.math.abs(z) >= LOBBY_SLOT_HALF) return null
        return contains(inWorld, x, y, z)
    }

    override fun remember(inWorld: WorldId?, x: Int, y: Int, z: Int) {
        if (!contains(inWorld, x, y, z)) return
        val state = blocks.stateAt(x, y, z)
        synchronized(lock) {
            original.putIfAbsent(LobbyBlockPos(x, y, z), state)
            lastChange = scheduler.currentTick()
        }
    }

    /** Number of journaled blocks awaiting restore. */
    public fun pending(): Int = synchronized(lock) { original.size }

    /** Periodic check; restores when the bay has been idle long enough. */
    public fun tick() {
        val due = synchronized(lock) { original.isNotEmpty() && scheduler.currentTick() - lastChange >= plan.idleResetTicks }
        if (due) reset()
    }

    override fun reset(): Int {
        val snapshot = synchronized(lock) { LinkedHashMap(original).also { original.clear() } }
        snapshot.entries.groupBy { (it.key.x shr 4) to (it.key.z shr 4) }.forEach { (chunk, entries) ->
            scheduler.run(ExecutionScope.Region(world, chunk.first, chunk.second), Duration.ZERO) {
                for ((pos, state) in entries) {
                    if (blocks.stateAt(pos.x, pos.y, pos.z) != state) blocks.setState(pos.x, pos.y, pos.z, state)
                }
            }
        }
        return snapshot.size
    }

    /** Starts the idle check (every second). */
    public fun start(): IScheduler.TaskHandle =
        scheduler.repeat(ExecutionScope.Global, 1.seconds, 1.seconds) { tick() }

    public companion object {
        /** The lobby is pasted into slot zero of the boot world; arena slots lie beyond this. */
        public const val LOBBY_SLOT_HALF: Int = 768
    }
}
