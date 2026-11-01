package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.bots.BotHost
import com.astarworks.astera.bots.HostLine
import com.astarworks.astera.bots.HostScreen
import com.astarworks.astera.bots.Seen
import com.astarworks.astera.bots.TerrainView
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The stage's generated island as a bot-runner [TerrainView]. The sim world itself carries no island blocks (only pillars and
 * ore nodes are placed), so the production [com.astarworks.astera.core.domain.stage.ArenaIslandGenerator] plan is the terrain:
 * the same blocks the live server places, in the same stage-local coordinates the spawn points and objectives use.
 */
internal class ArenaTerrain(private val plan: ArenaBuildPlan) : TerrainView {
    override fun isSolid(x: Int, y: Int, z: Int): Boolean? = plan.isSolid(x, y, z)

    override fun knows(x: Double, z: Double): Boolean =
        floor(x).toInt() in plan.minX..plan.maxX && floor(z).toInt() in plan.minZ..plan.maxZ

    override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val top = floor(feetY + 0.05).toInt() + maxStepUp
        val bottom = floor(feetY + 0.05).toInt() - maxDrop
        for (y in top downTo bottom) {
            if (!plan.isSolid(bx, y - 1, bz)) continue
            if (plan.isSolid(bx, y, bz) || plan.isSolid(bx, y + 1, bz)) continue
            return y.toDouble()
        }
        return null
    }
}

/**
 * Minimal [BotHost] over the human seat's [SimEntity] so the real bot-runner `Navigator` can walk it. Only position and
 * `moveTo` are meaningful (that is all `Navigator.follow` reads); every other member refuses, so a misuse fails loudly.
 */
internal class PlayerNavHost(
    private val sim: GameSim,
    private val entity: SimEntity,
    override val terrain: TerrainView,
) : BotHost {
    private fun unsupported(what: String): Nothing = error("PlayerNavHost does not support $what")

    override val name: String get() = entity.name
    override val joined: Boolean get() = true
    override val x: Double get() = entity.position.x
    override val y: Double get() = entity.position.y
    override val z: Double get() = entity.position.z
    override val yawDeg: Float get() = 0f
    override val pitchDeg: Float get() = 0f
    override val health: Float get() = (sim.hp(entity) ?: 0.0).toFloat()
    override val food: Int get() = 20
    override val spectating: Boolean get() = false
    override val selfEntityId: Int get() = unsupported("selfEntityId")
    override val selectedSlot: Int get() = unsupported("selectedSlot")
    override val entities: Map<Int, Seen> get() = emptyMap()
    override val entityFlags: Map<Int, Int> get() = emptyMap()
    override val entityTextsById: Map<Int, List<String>> get() = emptyMap()
    override val playerNames: Map<UUID, String> get() = emptyMap()
    override val actionBars: List<HostLine> get() = emptyList()
    override val screens: List<HostScreen> get() = emptyList()
    override val bossBarTitles: List<String> get() = emptyList()
    override val sounds: List<String> get() = emptyList()
    override val damageNumbers: List<String> get() = emptyList()
    override var lift: Double = 0.0

    override fun now(): Long = sim.tick * 50L
    override fun note(text: String) {}
    override fun isSpectator(player: UUID): Boolean = false
    override fun teamOf(player: String): String? = null
    override fun healthOf(entityId: Int): Float? = null
    override fun hotbar(): List<Int> = unsupported("hotbar")
    override fun hotbarName(slot: Int): String = unsupported("hotbarName")
    override fun hotbarLore(slot: Int): String = unsupported("hotbarLore")
    override fun carriedStacks(): List<Pair<String, Int>> = unsupported("carriedStacks")
    override fun command(cmd: String) = unsupported("command")
    override fun selectHotbar(slot: Int) = unsupported("selectHotbar")
    override fun useItem(yaw: Float, pitch: Float) = unsupported("useItem")
    override fun punch() = unsupported("punch")
    override fun attack(entityId: Int) = unsupported("attack")
    override fun interact(entityId: Int) = unsupported("interact")
    override fun sneak(on: Boolean) = unsupported("sneak")
    override fun setSprinting(on: Boolean) = unsupported("setSprinting")
    override fun respawn() = unsupported("respawn")
    override fun dropSelected(wholeStack: Boolean) = unsupported("dropSelected")
    override fun dig(bx: Int, by: Int, bz: Int, holdMs: Long) = unsupported("dig")
    override fun closeWindow(id: Int) = unsupported("closeWindow")
    override fun nudge() = unsupported("nudge")

    override fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) {
        sim.moveTo(entity, Vec3(nx, ny, nz))
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        entity.direction = Vec3(-sin(yawRad) * cos(pitchRad), -sin(pitchRad), cos(yawRad) * cos(pitchRad))
    }
}
