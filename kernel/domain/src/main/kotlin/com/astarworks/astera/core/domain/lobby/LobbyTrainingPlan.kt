package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3

/** Axis-aligned box in world coordinates (min inclusive, max exclusive). */
public data class LobbyRegion(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "region min must not exceed max" }
    }

    public fun contains(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ

    public fun contains(at: Vec3): Boolean = contains(at.x, at.y, at.z)
}

/** What a training dummy is for; drives where it stands and whether it moves. */
public enum class TrainingDummyKind { RANGE, MELEE, MOVING }

/**
 * Straight back-and-forth path of a moving target. [positionAt] is a pure triangle wave of the
 * tick, so the server and tests agree on where the dummy is without any stored state.
 */
public data class TrainingTrack(
    val fromX: Double,
    val fromZ: Double,
    val toX: Double,
    val toZ: Double,
    val blocksPerTick: Double,
) {
    init {
        require(blocksPerTick > 0.0) { "blocksPerTick must be > 0" }
    }

    private val length: Double = kotlin.math.hypot(toX - fromX, toZ - fromZ)

    /** (x, z) at [tick]: from -> to -> from -> ... */
    public fun positionAt(tick: Long): Pair<Double, Double> {
        if (length <= 0.0) return fromX to fromZ
        val travelled = (tick.coerceAtLeast(0L) * blocksPerTick) % (2 * length)
        val t = (if (travelled <= length) travelled else 2 * length - travelled) / length
        return (fromX + (toX - fromX) * t) to (fromZ + (toZ - fromZ) * t)
    }
}

/**
 * One training dummy. [x]/[y]/[z] are the feet position (block centre); for a [TrainingDummyKind.MOVING]
 * dummy they are the track start. [distance] is the firing distance in blocks for range dummies.
 */
public data class TrainingDummySpot(
    val id: String,
    val kind: TrainingDummyKind,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val distance: Int? = null,
    val track: TrainingTrack? = null,
) {
    public val position: Vec3 get() = Vec3(x, y, z)
}

/** One weapon-rack item frame: the air cell (x, y, z) it hangs in and the side it faces (`east` ...). */
public data class TrainingRackSlot(val index: Int, val x: Int, val y: Int, val z: Int, val facing: String)

/**
 * The lobby training range (F3-11): where it is, where players arrive, which dummies stand where and
 * where the weapon-rack frames hang. A pure function of the lobby centre and surface Y
 * ([LobbyTrainingLayout.plan]); shared by the island generator, the lobby wiring and Combat, which
 * spawns the dummies.
 */
public data class LobbyTrainingPlan(
    val region: LobbyRegion,
    val spawn: Vec3,
    val spawnYaw: Float,
    val dummies: List<TrainingDummySpot>,
    val rackSlots: List<TrainingRackSlot>,
)
