package com.astarworks.astera.vfxpreview

/** Approximate average colour of a vanilla block / item, for flat shaded previews. */
data class Swatch(val rgb: Int, val translucent: Boolean)

object BlockColors {
    private val dyes = mapOf(
        "white" to 0xF9FFFE, "orange" to 0xF9801D, "magenta" to 0xC74EBD, "light_blue" to 0x3AB3DA,
        "yellow" to 0xFED83D, "lime" to 0x80C71F, "pink" to 0xF38BAA, "gray" to 0x474F52,
        "light_gray" to 0x9D9D97, "cyan" to 0x169C9C, "purple" to 0x8932B8, "blue" to 0x3C44AA,
        "brown" to 0x835432, "green" to 0x5E7C16, "red" to 0xB02E26, "black" to 0x1D1D21,
    )

    private val plain = mapOf(
        "glass" to 0xC8E6F0, "quartz_block" to 0xECE6DF, "gold_block" to 0xF9D74A, "iron_block" to 0xDCDCDC,
        "diamond_block" to 0x62EDE4, "emerald_block" to 0x2BD65C, "redstone_block" to 0xAB1B09,
        "netherite_block" to 0x413C42, "obsidian" to 0x150F24, "crying_obsidian" to 0x3A0E86,
        "amethyst_block" to 0x8561C6, "copper_block" to 0xC06B4F, "coal_block" to 0x101010,
        "lapis_block" to 0x1F4AA0, "glowstone" to 0xFFD27A, "sea_lantern" to 0xBFE4D6, "shroomlight" to 0xF59B50,
        "light" to 0xFFF4B0, "magma_block" to 0xA03A0E, "lava" to 0xF96C12, "water" to 0x3A5FE0,
        "ice" to 0x9CC4F5, "packed_ice" to 0x8DB4F0, "blue_ice" to 0x74A8F4, "snow_block" to 0xF8FEFE,
        "stone" to 0x7D7D7D, "bedrock" to 0x555555, "tnt" to 0xDB2E1D, "slime_block" to 0x76C25C,
        "honey_block" to 0xF1B02C, "end_rod" to 0xF2E8F7, "bone_block" to 0xE3DFC8, "sculk" to 0x0D2A32,
    )

    fun swatch(blockKey: String): Swatch {
        val key = blockKey.removePrefix("minecraft:").lowercase()
        plain[key]?.let { return Swatch(it, key == "glass" || key == "ice" || key == "water") }
        for ((dye, rgb) in dyes.entries.sortedByDescending { it.key.length }) {
            if (!key.startsWith("${dye}_")) continue
            val rest = key.removePrefix("${dye}_")
            return when {
                rest.startsWith("stained_glass") -> Swatch(rgb, true)
                rest.startsWith("terracotta") -> Swatch(darken(rgb, 0.68), false)
                else -> Swatch(rgb, false)
            }
        }
        if (key.contains("glass")) return Swatch(0xC8E6F0, true)
        if (key.contains("lamp") || key.contains("lantern") || key.contains("light")) return Swatch(0xFFE9A0, false)
        // Stable pseudo colour so unknown blocks are at least distinguishable.
        val h = key.hashCode()
        return Swatch(0x606060 or ((h and 0x3F) shl 16) or (((h shr 6) and 0x3F) shl 8) or ((h shr 12) and 0x3F), false)
    }

    /** Item material tint for the blade / label (`netherite_sword`, `diamond_axe`...). */
    fun itemRgb(itemKey: String): Int {
        val key = itemKey.removePrefix("minecraft:").lowercase()
        return when {
            key.startsWith("netherite") -> 0x6A5F70
            key.startsWith("diamond") -> 0x4AEDD9
            key.startsWith("golden") || key.startsWith("gold") -> 0xFCEE4B
            key.startsWith("iron") -> 0xD8D8D8
            key.startsWith("stone") -> 0x9A9A9A
            key.startsWith("wooden") -> 0xA27E49
            key.contains("trident") -> 0x36C4B0
            key.contains("bow") -> 0xB07B3F
            else -> 0xCFCFE0
        }
    }

    private fun darken(rgb: Int, f: Double): Int {
        val r = (((rgb shr 16) and 0xFF) * f).toInt()
        val g = (((rgb shr 8) and 0xFF) * f).toInt()
        val b = ((rgb and 0xFF) * f).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
