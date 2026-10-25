package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.application.port.outbound.IStarDropWorld
import com.astarworks.astera.match.domain.norma.NormaGoal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [IStarDropWorld] for the simulator: star drops are plain records. Like the host's vanilla item pickup, a player
 * standing within [PICKUP_REACH] of a drop (checked every tick by [pickupSweep] and on every move) attempts to pick
 * it up once its pickup delay is over; there is no other way to collect.
 */
public class SimStarDropWorld : IStarDropWorld {
    public data class Drop(val handle: String, val world: WorldId, var position: Vec3, var amount: Int, val readyAtTick: Long)

    private val drops: MutableMap<String, Drop> = ConcurrentHashMap()
    private var listener: ((String, PlayerId) -> Unit)? = null
    private var tick: Long = 0L

    /** Star drops currently lying in the world. */
    public fun lying(): List<Drop> = drops.values.toList()

    override fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String {
        val handle = UUID.randomUUID().toString()
        drops[handle] = Drop(handle, world, position, amount, tick + pickupDelayTicks)
        return handle
    }

    override fun locate(handle: String): Vec3? = drops[handle]?.position

    override fun relabel(handle: String, amount: Int) {
        drops[handle]?.amount = amount
    }

    override fun remove(handle: String) {
        drops.remove(handle)
    }

    override fun take(handle: String, player: PlayerId) {
        drops.remove(handle)
    }

    override fun onPickupAttempt(listener: (handle: String, player: PlayerId) -> Unit) {
        this.listener = listener
    }

    /** One tick of vanilla pickup: every player in reach of a ready drop attempts to pick it up. */
    public fun pickupSweep(currentTick: Long, players: Collection<SimEntity>) {
        tick = currentTick
        for (drop in drops.values.toList()) {
            if (currentTick < drop.readyAtTick) continue
            for (p in players) {
                if (p.kind != SimEntityKind.PLAYER || p.world != drop.world) continue
                val dx = p.position.x - drop.position.x
                val dz = p.position.z - drop.position.z
                val dy = drop.position.y - p.position.y
                if (dx * dx + dz * dz <= PICKUP_REACH * PICKUP_REACH && dy > -1.0 && dy < 2.0) {
                    listener?.invoke(drop.handle, p.id)
                    break
                }
            }
        }
    }

    public companion object {
        /** Horizontal reach of the vanilla pickup box (player half-width 0.3 + 1.0 expansion). */
        public const val PICKUP_REACH: Double = 1.3
    }
}

/** [INormaPresentation] for the simulator: records what a player would have seen. */
public class SimNormaPresentation : INormaPresentation {
    public data class LevelUp(val player: PlayerId, val audience: List<PlayerId>, val level: Int, val maxLevel: Int)
    public data class Choice(val player: PlayerId, val goal: NormaGoal, val target: Int)

    public val levelUps: MutableList<LevelUp> = CopyOnWriteArrayList()
    public val opened: MutableList<PlayerId> = CopyOnWriteArrayList()
    public val picked: MutableList<Choice> = CopyOnWriteArrayList()
    public val defaulted: MutableList<Choice> = CopyOnWriteArrayList()

    override fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int) {
        levelUps += LevelUp(player, audience, level, maxLevel)
    }

    override fun choiceOpened(player: PlayerId) {
        opened += player
    }

    override fun choicePicked(player: PlayerId, goal: NormaGoal, target: Int) {
        picked += Choice(player, goal, target)
    }

    override fun choiceDefaulted(player: PlayerId, goal: NormaGoal, target: Int) {
        defaulted += Choice(player, goal, target)
    }
}
