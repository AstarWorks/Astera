package com.astarworks.astera.core.domain.stage

/** Full cube materials shared by terrain and authored parts. No vendor types or runtime I/O. */
public enum class ArenaTheme(public val palette: Map<String, String>) {
    TEMPERATE(materials("grass_block", "dirt", "stone", "stone_bricks", "oak_planks", "oak_log", "oak_leaves", "glowstone")),
    FROST(materials("snow_block", "packed_ice", "deepslate", "deepslate_bricks", "spruce_planks", "spruce_log", "spruce_leaves", "sea_lantern")),
    DESERT(materials("sandstone", "cut_sandstone", "terracotta", "smooth_sandstone", "acacia_planks", "acacia_log", "azalea_leaves", "shroomlight")),
    VOLCANIC(materials("basalt", "blackstone", "deepslate", "polished_blackstone_bricks", "crimson_planks", "crimson_stem", "nether_wart_block", "shroomlight")),
}

private fun materials(ground: String, soil: String, rock: String, wall: String, deck: String, log: String, leaves: String, light: String): Map<String, String> =
    mapOf("ground" to ground, "soil" to soil, "rock" to rock, "wall" to wall, "deck" to deck, "log" to log, "leaves" to leaves, "light" to light)
        .mapValues { "minecraft:${it.value}" }

/** Ordered inclusive cuboids: later boxes replace earlier ones; air explicitly carves openings. */
public data class ArenaStructureBox(val min: ArenaBlockPos, val max: ArenaBlockPos, val block: String) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "structure box min must precede max" }
        require(listOf(min.x, min.y, min.z, max.x, max.y, max.z).all { it in -128..128 }) { "structure coordinates exceed ±128" }
        require((max.x - min.x + 1L) * (max.y - min.y + 1) * (max.z - min.z + 1) <= 100_000) { "structure box exceeds 100000 blocks" }
        require(block.matches(Regex("(?:@[a-z][a-z0-9_-]*|minecraft:[a-z0-9_]+)"))) { "structure blocks must be full block IDs or @palette slots" }
    }
}

public data class ArenaStructurePart(val id: String, val boxes: List<ArenaStructureBox>) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]*"))) { "invalid structure id" }
        require(boxes.isNotEmpty() && boxes.size <= 512) { "structure needs 1..512 boxes" }
    }
}

/** Rotation in clockwise quarter turns around local (0,0); y is relative to the stage surface. */
public data class ArenaStructurePlacement(
    val part: String, val x: Int, val y: Int, val z: Int,
    val rotation: Int = 0, val palette: Map<String, String> = emptyMap(),
) {
    init {
        require(rotation in listOf(0, 90, 180, 270)) { "structure rotation must be 0/90/180/270" }
        require(palette.values.all { it.matches(Regex("minecraft:[a-z0-9_]+")) }) { "palette requires full block IDs" }
    }

    public fun transform(local: ArenaBlockPos, surfaceY: Int): ArenaBlockPos {
        val (rx, rz) = when (rotation) {
            90 -> -local.z to local.x
            180 -> -local.x to -local.z
            270 -> local.z to -local.x
            else -> local.x to local.z
        }
        return ArenaBlockPos(x + rx, surfaceY + y + local.y, z + rz)
    }
}
