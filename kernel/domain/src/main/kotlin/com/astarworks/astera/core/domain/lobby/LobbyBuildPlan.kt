package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlin.math.floor

/** Integer block coordinate inside the lobby world. */
public data class LobbyBlockPos(val x: Int, val y: Int, val z: Int)

/**
 * A sign the adapter must write text onto after placing [state] (a `*_sign` /
 * `*_wall_sign` block-data string). [lines] are i18n keys, `null` = blank line.
 */
public data class LobbySign(
    val pos: LobbyBlockPos,
    val state: String,
    val lines: List<MessageKey?>,
    /** Placeholders for rendering [lines] (e.g. `lane`). */
    val placeholders: Map<String, String> = emptyMap(),
)

/** Where an NPC should stand (feet position, block centre) and which way it faces. */
public data class LobbyNpcSpot(val x: Double, val y: Double, val z: Double, val yaw: Float)

/**
 * Pure description of the sky-island lobby: every block placement as a
 * vendor-neutral block-data string (`minecraft:oak_stairs[facing=north]`),
 * plus the landmarks game code needs (spawn, NPC stands, queue pads).
 *
 * Absent coordinates are air. The plan is produced by [LobbyIslandGenerator]
 * and is a pure function of its inputs, so it can be unit-tested and rendered
 * by tooling with no Minecraft on the classpath (ADR-0180).
 */
public class LobbyBuildPlan(
    public val blocks: Map<LobbyBlockPos, String>,
    public val signs: List<LobbySign>,
    public val spawn: Vec3,
    public val spawnYaw: Float,
    public val surfaceY: Int,
    /** Recommended stand per NPC id (matches `content/lobby-npcs/<id>.yaml`). */
    public val npcSpots: Map<String, LobbyNpcSpot>,
    /** Centre of each queue lane pad, keyed by lane id. */
    public val lanePads: Map<String, Vec3>,
    /** The training range landmarks (F3-11); blocks are part of [blocks]. */
    public val training: LobbyTrainingPlan,
) {
    public val minX: Int = blocks.keys.minOf { it.x }
    public val maxX: Int = blocks.keys.maxOf { it.x }
    public val minY: Int = blocks.keys.minOf { it.y }
    public val maxY: Int = blocks.keys.maxOf { it.y }
    public val minZ: Int = blocks.keys.minOf { it.z }
    public val maxZ: Int = blocks.keys.maxOf { it.z }

    /** Block-data string at the coordinate, `null` for air. */
    public fun stateAt(x: Int, y: Int, z: Int): String? = blocks[LobbyBlockPos(x, y, z)]

    /** A player can stand on top of this block. */
    public fun isSupport(state: String?): Boolean {
        if (state == null) return false
        return !isBodyPassable(state) && !isLiquid(state) && !isTall(state)
    }

    /** A player body can occupy this cell. */
    public fun isBodyPassable(state: String?): Boolean {
        if (state == null) return true
        val name = nameOf(state)
        return name in PASSABLE || name.endsWith("_carpet") || name.endsWith("_sign") || name.endsWith("_banner")
    }

    /** Feet at (x, y, z) stand on a solid block with two free cells above. */
    public fun isWalkable(x: Int, y: Int, z: Int): Boolean =
        isSupport(stateAt(x, y - 1, z)) && isBodyPassable(stateAt(x, y, z)) && isBodyPassable(stateAt(x, y + 1, z))

    /** The feet position (floored to its block) is walkable. */
    public fun isWalkable(at: Vec3): Boolean =
        isWalkable(floor(at.x).toInt(), floor(at.y).toInt(), floor(at.z).toInt())

    private fun isLiquid(state: String): Boolean = nameOf(state).let { it == "water" || it == "lava" }

    private fun isTall(state: String): Boolean {
        val name = nameOf(state)
        return name.endsWith("_fence") || name.endsWith("_wall") || name.endsWith("_fence_gate") || name == "lantern"
    }

    public companion object {
        private val PASSABLE: Set<String> = setOf(
            "air", "short_grass", "tall_grass", "fern", "dandelion", "poppy", "azure_bluet", "cornflower",
            "allium", "oxeye_daisy", "pink_tulip", "red_tulip", "orange_tulip", "white_tulip", "blue_orchid",
            "lily_of_the_valley", "pink_petals", "vine", "torch", "light",
        )

        internal fun nameOf(state: String): String = state.substringAfter(':').substringBefore('[')
    }
}
