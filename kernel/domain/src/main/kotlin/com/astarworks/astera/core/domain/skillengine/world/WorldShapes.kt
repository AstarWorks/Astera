package com.astarworks.astera.core.domain.skillengine.world

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.WorldOp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One block position. */
public data class BlockCell(val x: Int, val y: Int, val z: Int) {
    /** Squared distance from the cell's integer CORNER to [p]: v1 sorts shape cells by `(cell - centre).lengthSquared()` on floored corners (WorldMechanics.kt:233, :431). */
    public fun distanceSquaredTo(p: Vec3): Double {
        val dx = x - p.x
        val dy = y - p.y
        val dz = z - p.z
        return dx * dx + dy * dy + dz * dz
    }

    public companion object {
        public fun containing(p: Vec3): BlockCell = BlockCell(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
    }
}

/**
 * Where a shape is anchored and which way it faces. [look] is the caster's look direction; builds and boxes follow
 * its dominant horizontal axis.
 */
public class WorldAim(public val origin: Vec3, look: Vec3) {
    public val look: Vec3 = if (look.lengthSquared() > 0.0) look.normalize() else Vec3(1.0, 0.0, 0.0)

    /** Look flattened to the dominant horizontal axis: (+-1,0) or (0,+-1). */
    public val forward: Pair<Int, Int> =
        if (abs(this.look.x) >= abs(this.look.z)) (if (this.look.x >= 0) 1 else -1) to 0 else 0 to (if (this.look.z >= 0) 1 else -1)
    public val right: Pair<Int, Int> = -forward.second to forward.first
}

/** Pure block-set generators for break_area / place_shape. Output is capped at [MAX_SHAPE_BLOCKS] cells. */
public object WorldShapes {
    public const val MAX_SHAPE_BLOCKS: Int = 512

    /** Centre used to order a break so it ripples outward. */
    public fun breakCentre(op: WorldOp.BreakArea, aim: WorldAim): Vec3 =
        if (op.shape == WorldOp.AreaShape.SPHERE) aim.origin + aim.look * op.length else aim.origin

    public fun breakCells(op: WorldOp.BreakArea, aim: WorldAim): List<BlockCell> {
        val out = LinkedHashSet<BlockCell>()
        when (op.shape) {
            // RAY: the executor has already marched to the struck cover, so the aim origin IS the ball centre.
            // v1 CoverBlockService.coverCellsAround (:249-258): every cell whose box is within r of the ball centre (axisGap), r capped at 6.
            WorldOp.AreaShape.RAY -> {
                val c = aim.origin
                val r = op.radius.coerceAtMost(6.0)
                fun gap(v: Double, cell: Int): Double = when {
                    v < cell -> cell - v
                    v > cell + 1 -> v - (cell + 1)
                    else -> 0.0
                }
                for (x in floor(c.x - r).toInt()..floor(c.x + r).toInt()) for (y in floor(c.y - r).toInt()..floor(c.y + r).toInt()) for (z in floor(c.z - r).toInt()..floor(c.z + r).toInt()) {
                    val dx = gap(c.x, x); val dy = gap(c.y, y); val dz = gap(c.z, z)
                    if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) <= r) out += BlockCell(x, y, z)
                }
            }
            WorldOp.AreaShape.SPHERE -> {
                val r = op.radius
                val n = ceil(r).toInt()
                val base = BlockCell.containing(breakCentre(op, aim))
                for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) out += BlockCell(base.x + dx, base.y + dy, base.z + dz)
                }
            }
            WorldOp.AreaShape.LINE -> {
                val half = op.width / 2.0
                val n = ceil(half).toInt()
                var t = 0.5
                while (t <= op.length) {
                    val base = BlockCell.containing(aim.origin + aim.look * t)
                    for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                        if (dx * dx + dy * dy + dz * dz <= half * half) out += BlockCell(base.x + dx, base.y + dy, base.z + dz)
                    }
                    t += 0.5
                }
            }
            WorldOp.AreaShape.BOX -> {
                val feet = BlockCell.containing(aim.origin)
                val w = op.width.toInt().coerceAtLeast(1)
                for (a in 1..ceil(op.length).toInt().coerceAtLeast(1)) for (b in -(w / 2)..(w - 1) / 2) for (c in 0 until op.height.toInt()) {
                    out += BlockCell(
                        feet.x + aim.forward.first * a + aim.right.first * b,
                        feet.y + c,
                        feet.z + aim.forward.second * a + aim.right.second * b,
                    )
                }
            }
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }

    public fun buildCells(op: WorldOp.PlaceShape, aim: WorldAim): List<BlockCell> {
        val feet = BlockCell.containing(aim.origin)
        fun at(ahead: Int, side: Int, up: Int) = BlockCell(
            feet.x + aim.forward.first * ahead + aim.right.first * side,
            feet.y + up,
            feet.z + aim.forward.second * ahead + aim.right.second * side,
        )
        val out = ArrayList<BlockCell>()
        val sideRange = -(op.width / 2)..(op.width - 1) / 2
        when (op.shape) {
            WorldOp.BuildShape.WALL -> for (side in sideRange) for (up in 0 until op.height) out += at(2, side, up)
            WorldOp.BuildShape.BRIDGE -> for (ahead in 1..op.length) for (side in sideRange) out += at(ahead, side, -1)
            WorldOp.BuildShape.PILLAR -> for (up in 0 until op.height) out += at(2, 0, up)
            WorldOp.BuildShape.PLATFORM -> for (ahead in -(op.length / 2)..(op.length - 1) / 2) for (side in sideRange) out += at(ahead, side, -1)
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }
}

/** What a skill may touch, independent of the platform. */
public object WorldBlockRules {
    private val AIR_LIKE: Set<String> = setOf(
        "air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass", "fern", "snow", "fire",
        "light", "vine", "glow_lichen",
    )

    /** Never removed, never placed: unbreakable, technical, or holding data that a revert would lose. */
    private val PROTECTED: Set<String> = setOf(
        "bedrock", "barrier", "light", "structure_void", "structure_block", "jigsaw", "command_block",
        "chain_command_block", "repeating_command_block", "end_portal", "end_portal_frame", "end_gateway",
        "nether_portal", "spawner", "trial_spawner", "vault", "chest", "trapped_chest", "ender_chest", "barrel",
        "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser", "beacon", "lectern",
        "brewing_stand", "jukebox", "crafter",
    )

    // v1 WorldMechanics.kt:687-690.
    private val SOIL_WORDS: List<String> = listOf("dirt", "grass_block", "sand", "gravel", "clay", "mud", "snow", "podzol", "mycelium", "soul_soil", "farmland")
    private val WOOD_WORDS: List<String> = listOf("plank", "log", "wood", "stem", "hyphae", "leaves", "fence", "door", "bookshelf", "scaffolding", "bamboo", "wool", "hay_block", "carpet")
    private val HARD: Set<String> = setOf("obsidian", "crying_obsidian", "respawn_anchor", "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil")
    private val HARDEST: Set<String> = setOf("reinforced_deepslate", "netherite_block", "ancient_debris")

    /**
     * v1 WorldMechanics.breakable (WorldMechanics.kt:457-472), 1:1. The class decides what a tool is for (shovel = soil, axe = wood,
     * pickaxe = everything else; a null [toolClass] accepts any block), and [power] must reach the block's hardness tier
     * (hardest 3, hard 2, everything else 1). Namespaces are ignored like v1's `BlockType.identifier`.
     */
    public fun breakable(blockKey: String, power: Double, toolClass: String?): Boolean {
        val id = idOf(blockKey).second
        val kind = when {
            id.contains("sandstone") || id.contains("mud_brick") -> "PICKAXE"
            SOIL_WORDS.any { it in id } -> "SHOVEL"
            WOOD_WORDS.any { it in id } -> "AXE"
            else -> "PICKAXE"
        }
        if (toolClass != null && toolClass != kind) return false
        val hardness = when {
            id in HARDEST -> 3
            id in HARD -> 2
            else -> 1
        }
        return hardness <= power
    }

    public fun normalize(key: String): String = if (':' in key) key.lowercase() else "minecraft:${key.lowercase()}"

    private fun idOf(key: String): Pair<String, String> {
        val k = normalize(key)
        return k.substringBefore(':') to k.substringAfter(':')
    }

    public fun isAir(key: String?): Boolean {
        if (key == null) return true
        val (ns, id) = idOf(key)
        return ns == "minecraft" && id.endsWith("air")
    }

    public fun isReplaceable(key: String?): Boolean {
        if (key == null) return true
        val (ns, id) = idOf(key)
        return ns == "minecraft" && id in AIR_LIKE
    }

    /** Non-vanilla blocks carry their own durability, so a skill neither removes nor overwrites them. */
    public fun isProtected(key: String): Boolean {
        val (ns, id) = idOf(key)
        return ns != "minecraft" || id in PROTECTED || id.endsWith("shulker_box") || id.endsWith("_sign") || id.endsWith("_bed")
    }
}
