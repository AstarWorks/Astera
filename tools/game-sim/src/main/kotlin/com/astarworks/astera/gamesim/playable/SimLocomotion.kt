package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.application.service.arena.VoidFallKiller
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Movement of one simulated player the way a protocol bot experiences it: the client is authoritative for position
 * (the server has no gravity), so the bot reports where it wants to stand and this class validates the step.
 *
 *  - horizontal step at most walk 0.23 / sprint 0.28 blocks per tick (plus a small slack);
 *  - the destination cell (feet and head) must be free; a step up of at most 1 block and a drop of at most 3 are fine,
 *    anything else is refused (the same stand rules as the PathFinder);
 *  - the floor pose ([floor]) stays on the ground while a jump arc is carried by [lift] (extra height reported to the
 *    server, as the real bot does); with lift 0 an unsupported pose starts a fall of 1 block per tick;
 *  - falling below the stage kill plane kills through [VoidFallKiller], after putting the body back on the last ground.
 */
class SimLocomotion(
    private val sim: GameSim,
    private val entity: SimEntity,
    private val terrain: SimTerrain,
    private val killer: VoidFallKiller,
) {
    /** Where the feet stand on the ground (the pose the bot reports as x/y/z); [entity] position = this + [lift]. */
    var floor: Vec3 = entity.position
        private set
    private var liftValue: Double = 0.0
    var lift: Double
        get() = liftValue
        set(value) {
            adoptExternalMoves()
            liftValue = value
            entity.position = floor + Vec3(0.0, value, 0.0)
            lastWritten = entity.position
        }
    var sprinting: Boolean = false
    var falling: Boolean = false
        private set
    var voidDeaths: Int = 0
        private set

    private var lastWritten: Vec3 = entity.position
    private var lastGround: Vec3 = entity.position

    /** A teleport from the game (match entry, respawn, a skill) moved the entity: take that as the new ground pose. */
    fun adoptExternalMoves() {
        if (entity.position != lastWritten) {
            floor = entity.position
            lastGround = floor
            falling = false
            liftValue = 0.0
            lastWritten = entity.position
        }
    }

    fun moveTo(nx: Double, ny: Double, nz: Double) {
        adoptExternalMoves()
        if (!sim.isAlive(entity.id)) return
        val maxStep = (if (sprinting) SPRINT else WALK) + SLACK
        var dx = nx - floor.x
        var dz = nz - floor.z
        val flat = hypot(dx, dz)
        if (flat > maxStep) {
            dx *= maxStep / flat
            dz *= maxStep / flat
        }
        val tx = floor.x + dx
        val tz = floor.z + dz
        val cx = floor(tx).toInt()
        val cz = floor(tz).toInt()
        if (falling) {
            // In the air only the horizontal part is honoured, and never through a wall.
            val fy = floor(floor.y + 0.05).toInt()
            if (terrain.isOccupied(cx, fy, cz) || terrain.isOccupied(cx, fy + 1, cz)) return
            apply(tx, floor.y, tz)
            return
        }
        // Where the pose ends up vertically: the reported height, kept within the stand rules of the current level.
        var ty = ny
        if (ty > floor.y + MAX_STEP_UP) ty = floor.y + MAX_STEP_UP
        if (ty < floor.y - MAX_DROP) ty = floor.y - MAX_DROP
        val fy = floor(ty + 0.05).toInt()
        if (terrain.isOccupied(cx, fy, cz) || terrain.isOccupied(cx, fy + 1, cz)) return
        apply(tx, ty, tz)
    }

    private fun apply(x: Double, y: Double, z: Double) {
        floor = Vec3(x, y, z)
        if (lift == 0.0 && supported(x, y, z)) {
            lastGround = floor
        } else if (lift == 0.0) {
            falling = true
        }
        publish()
    }

    private fun publish() {
        val to = floor + Vec3(0.0, lift, 0.0)
        sim.moveTo(entity, to)
        lastWritten = entity.position
    }

    private fun supported(x: Double, y: Double, z: Double): Boolean {
        val sy = floor(y + 0.05).toInt() - 1
        for (ox in CORNERS) for (oz in CORNERS) {
            if (terrain.isOccupied(floor(x + ox).toInt(), sy, floor(z + oz).toInt())) return true
        }
        return false
    }

    /** One tick of physics, after the brains and before the sim step: fall 1 block per tick, land, void-kill. */
    fun physicsTick() {
        adoptExternalMoves()
        if (!sim.isAlive(entity.id)) return
        if (!falling && lift == 0.0 && !supported(floor.x, floor.y, floor.z)) falling = true
        if (!falling) return
        var y = floor.y - 1.0
        // Landing: the first block row at or below the new height that carries the body.
        if (supported(floor.x, y, floor.z)) {
            falling = false
            y = floor(y + 0.05)
            floor = Vec3(floor.x, y, floor.z)
            lastGround = floor
            publish()
            return
        }
        floor = Vec3(floor.x, y, floor.z)
        if (y < terrain.killY) {
            // Like PaperArenaVoidListener: back to the edge it fell from, then the death through the health container.
            voidDeaths++
            floor = lastGround
            falling = false
            liftValue = 0.0
            publish()
            killer.kill(entity.id)
            return
        }
        publish()
    }

    companion object {
        const val WALK = 0.23
        const val SPRINT = 0.28
        const val SLACK = 0.05
        const val MAX_STEP_UP = 1.0
        const val MAX_DROP = 3.0
        private val CORNERS = doubleArrayOf(-0.3, 0.0, 0.3)
    }
}
