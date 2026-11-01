package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.application.port.outbound.IStarDropWorld
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.match.domain.norma.NormaUnlock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [IStarDropWorld] for the simulator: star drops are plain records. Like the host's vanilla item pickup, a player
 * standing within [PICKUP_REACH] of a drop (checked every tick by [pickupSweep] and on every move) attempts to pick
 * it up once its pickup delay is over; there is no other way to collect.
 */
public class SimStarDropWorld(private val world: SimWorld) : IStarDropWorld {
    public data class Drop(val handle: String, val world: WorldId, var position: Vec3, var amount: Int, val readyAtTick: Long, val velocity: Vec3? = null)

    private val drops: MutableMap<String, Drop> = ConcurrentHashMap()
    private var listener: ((String, PlayerId) -> Unit)? = null
    private var tick: Long = 0L

    /** Star drops currently lying in the world. */
    public fun lying(): List<Drop> = drops.values.toList()

    override fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String {
        val handle = this.world.nextUuid().toString()
        drops[handle] = Drop(handle, world, position, amount, tick + pickupDelayTicks, velocity)
        return handle
    }

    override fun executionScope(handle: String): ExecutionScope = ExecutionScope.Global

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
    public data class LevelUp(val player: PlayerId, val audience: List<PlayerId>, val level: Int, val maxLevel: Int, val unlocked: List<NormaUnlock>)

    public val levelUps: MutableList<LevelUp> = CopyOnWriteArrayList()

    public val locked: MutableList<Triple<PlayerId, Int, Rarity>> = CopyOnWriteArrayList()

    override fun levelUp(player: PlayerId, audience: List<PlayerId>, level: Int, maxLevel: Int, unlocked: List<NormaUnlock>) {
        levelUps += LevelUp(player, audience, level, maxLevel, unlocked)
    }

    override fun locked(player: PlayerId, required: Int, rarity: Rarity) {
        locked += Triple(player, required, rarity)
    }
}

/** [IMatchPlayerPositions] for the simulator with Paper's rule: a spectating player cannot pick anything up. */
public class SimMatchPlayerPositions(
    private val lookup: () -> IPlayerLocationLookup?,
    private val spectator: ISpectator,
) : IMatchPlayerPositions {
    override fun positionOf(playerId: PlayerId, includeDead: Boolean): PlayerLocation? {
        if (!includeDead && spectator.isSpectating(playerId)) return null
        return lookup()?.locate(playerId)
    }
}

/** Ore node blocks of the simulated world: the current material per cell, in the order they were set. */
public class SimOreNodeWorld(
    /** The sim terrain the node blocks are placed into, as `PaperOreNodeWorld` sets them on the real world (null: bookkeeping only). */
    private val terrain: SimWorld? = null,
    private val delivery: SimItemDelivery? = null,
    private val tick: () -> Long = { 0L },
) : com.astarworks.astera.match.application.port.outbound.IOreNodeWorld {
    private val blocks = ConcurrentHashMap<Triple<Int, Int, Int>, String>()
    private val oreMaterial = ConcurrentHashMap<Triple<Int, Int, Int>, String>()

    /** A node that was mined: its cell, the ore it held, who mined it (null when the drops reached nobody) and what they got. */
    public data class Mined(val tick: Long, val x: Int, val y: Int, val z: Int, val material: String, val miner: PlayerId?, val items: Int)

    /** Every node mined so far, in order. The miner is the player the node's drops were delivered to in that tick. */
    public val mined: MutableList<Mined> = CopyOnWriteArrayList()

    override fun setBlock(world: WorldId, pos: Vec3, material: String) {
        val cell = Triple(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt())
        val before = blocks.put(cell, material)
        terrain?.placeBlock(world, pos, com.astarworks.astera.core.domain.world.BlockType(material.lowercase().let { if (':' in it) it else "minecraft:$it" }))
        val ore = oreMaterial.putIfAbsent(cell, material) ?: material
        // OreNodeService hands the drops out first, then swaps the ore for its placeholder: that swap is the mining.
        if (before != null && before == ore && material != ore) {
            val now = tick()
            val gives = delivery?.drainGives(now).orEmpty()
            val miner = gives.firstOrNull()?.player
            mined += Mined(now, cell.first, cell.second, cell.third, ore, miner, gives.filter { it.player == miner }.sumOf { it.amount })
        }
    }

    private val cracks = ConcurrentHashMap<Triple<Int, Int, Int>, Pair<Int, Int>>()

    override fun hit(world: WorldId, pos: Vec3, hp: Int, maxHp: Int) {
        cracks[Triple(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt())] = hp to maxHp
    }

    override fun clearCrack(world: WorldId, pos: Vec3) {
        cracks.remove(Triple(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt()))
    }

    /** Cells whose mining burst (debris particles + break sound) was shown, as (x, y, z, ore material). */
    public val bursts: MutableList<List<Any>> = java.util.concurrent.CopyOnWriteArrayList()

    override fun mined(world: WorldId, pos: Vec3, material: String) {
        bursts += listOf(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt(), material)
    }

    /** Cells a refused swing (tool too weak or wrong) puffed at, in order (x, y, z). */
    public val refusals: MutableList<Triple<Int, Int, Int>> = java.util.concurrent.CopyOnWriteArrayList()

    override fun refused(world: WorldId, pos: Vec3) {
        refusals += Triple(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt())
    }

    /** The (hp, maxHp) crack now shown at [x],[y],[z], or null when none. */
    public fun crackAt(x: Int, y: Int, z: Int): Pair<Int, Int>? = cracks[Triple(x, y, z)]

    /** The material now at [x],[y],[z], or null when nothing was ever set there. */
    public fun materialAt(x: Int, y: Int, z: Int): String? = blocks[Triple(x, y, z)]
}

/** Items a `drop_item` generator dropped into the simulated world, as (item id, amount). */
public class SimGeneratorItemDrop : com.astarworks.astera.match.application.port.outbound.IGeneratorItemDrop {
    public val drops: MutableList<Pair<String, Int>> = CopyOnWriteArrayList()

    override fun drop(world: WorldId, position: Vec3, itemId: String, amount: Int): Boolean {
        drops += itemId to amount
        return true
    }
}

/** Armor a simulated player "wears": scenarios put an Astera item id into a slot with [wear]. */
public class SimWornItems : com.astarworks.astera.combat.application.port.outbound.IWornItems {
    private val worn = ConcurrentHashMap<PlayerId, MutableMap<com.astarworks.astera.combat.domain.armor.ArmorSlot, com.astarworks.astera.core.domain.item.ItemId>>()

    public fun wear(player: PlayerId, slot: com.astarworks.astera.combat.domain.armor.ArmorSlot, item: String) {
        worn.getOrPut(player) { ConcurrentHashMap() }[slot] = com.astarworks.astera.core.domain.item.ItemId(item)
    }

    override fun wornItems(playerId: PlayerId): Map<com.astarworks.astera.combat.domain.armor.ArmorSlot, com.astarworks.astera.core.domain.item.ItemId> =
        worn[playerId]?.toMap() ?: emptyMap()
}

/** Held item and crosshair of simulated diggers: a scenario sets [held] and [aimAt] the way a player picks a tool and looks at a block. */
public class SimOreDigSight : com.astarworks.astera.match.application.port.outbound.IOreDigSight {
    public val held: MutableMap<PlayerId, com.astarworks.astera.core.domain.item.ItemId> = ConcurrentHashMap()
    private val aim = ConcurrentHashMap<PlayerId, Triple<Int, Int, Int>>()

    public fun aimAt(player: PlayerId, x: Int, y: Int, z: Int) { aim[player] = Triple(x, y, z) }
    public fun lookAway(player: PlayerId) { aim.remove(player) }

    override fun heldItem(player: PlayerId): com.astarworks.astera.core.domain.item.ItemId? = held[player]

    override fun aimsAt(player: PlayerId, world: WorldId, pos: Vec3): Boolean =
        aim[player] == Triple(kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.y).toInt(), kotlin.math.floor(pos.z).toInt())
}
