package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import java.awt.image.BufferedImage

/** Hand-pixelled, deterministic 9x9 symbols in 9x12 cells. No system fonts or external assets. */
internal object StatusEffectArt {
    private val beneficial = setOf("charge", "dash", "evasive", "poise", "regen")
    private val patterns = mapOf(
        "bleed" to "...#.../..###../..###../.#####./.#####./..###../.......",
        "burn" to "...#.../..##.#./.#.###./.#####./##.####/###.###/.#####.",
        "charge" to "....##./...##../..####./...##../..##.../.##..../.......",
        "dash" to "#..#.../.#..#../..#..#./...#..#/..#..#./.#..#../#..#...",
        "evasive" to "..###../.#...#./#..#..#/#.###.#/#..#..#/.#...#./..###..",
        "freeze" to "#..#..#/.#.#.#./..###../#######/..###../.#.#.#./#..#..#",
        "poise" to "...#.../..###../.#.#.#./#..#..#/...#.../..###../.#####.",
        "poison" to ".#####./#######/##.#.##/#######/.##.##./..###../..#.#..",
        "regen" to "..###../..###../#######/#######/#######/..###../..###..",
        "reloading" to ".####../#....#./#...###/#....#./.####../...#.../..###..",
        "rupture" to "..#.#../...#.../..#.#../.#...#./..#.#../...#.../..#.#..",
        "shock" to "....##./...##../..###../.####../...#.../..#..../.#.....",
        "sinking" to "#.....#/.#...#./..#.#../...#.../......./#######/.#####.",
        "tremor" to ".#...#./#.#.#.#/...#.../.#...#./#.#.#.#/...#.../.......",
        "wet" to "...#.../..###../..###../.#####./#.#####/#.#####/.#####.",
        "silence" to "#.....#/.#####./..###../#######/..###../.#####./#.....#",
    )
    private val colors = mapOf(
        "bleed" to 0xFFE45C76.toInt(),
        "burn" to 0xFFFFA23A.toInt(),
        "charge" to 0xFFFFDF58.toInt(),
        "dash" to 0xFF86EADD.toInt(),
        "evasive" to 0xFFD8DFFF.toInt(),
        "freeze" to 0xFFA0ECFF.toInt(),
        "poise" to 0xFFE3CFFA.toInt(),
        "poison" to 0xFFB7E65B.toInt(),
        "regen" to 0xFF8FFFAB.toInt(),
        "reloading" to 0xFFFFD28C.toInt(),
        "rupture" to 0xFFFF8CBA.toInt(),
        "shock" to 0xFFFFFF70.toInt(),
        "sinking" to 0xFF98AFFF.toInt(),
        "tremor" to 0xFFE4C694.toInt(),
        "wet" to 0xFF65BEFF.toInt(),
        "silence" to 0xFFE1B7FF.toInt(),
    )
    private val digits = listOf(
        "111/101/101/101/111",
        "010/110/010/010/111",
        "111/001/111/100/111",
        "111/001/111/001/111",
        "101/101/111/001/001",
        "111/100/111/001/111",
        "111/100/111/101/111",
        "111/001/010/010/010",
        "111/101/111/101/111",
        "111/101/111/001/111",
    )

    fun atlas(): BufferedImage {
        val image = BufferedImage(16 * 9, 3 * 12, BufferedImage.TYPE_INT_ARGB)
        StatusEffectHudGlyphs.IDS.forEachIndexed { index, id ->
            val ox = index * 9
            val frame = if (id in beneficial) 0xFF56CB86.toInt() else 0xFFE76370.toInt()
            for (y in 0..8) for (x in 0..8) {
                image.setRGB(ox + x, y + 1, if (x == 0 || x == 8 || y == 0 || y == 8) frame else 0xFF18212D.toInt())
            }
            patterns.getValue(id).split('/').forEachIndexed { y, row ->
                row.forEachIndexed { x, c -> if (c == '#') image.setRGB(ox + x + 1, y + 2, colors.getValue(id)) }
            }
        }
        for (row in 1..2) for (digit in 0..9) {
            val oy = row * 12 + if (row == 1) 7 else 0
            val color = if (row == 1) 0xFFFFFFFF.toInt() else 0xFFFFD166.toInt()
            digits[digit].split('/').forEachIndexed { y, line ->
                line.forEachIndexed { x, c -> if (c == '1') image.setRGB(digit * 9 + x, oy + y, color) }
            }
            // Alpha-1 marker fixes every digit's visible width at 3, including narrow 1.
            image.setRGB(digit * 9 + 2, row * 12 + 5, 0x01000000)
        }
        return image
    }

    /** 8 columns in stable glyph order; 8x nearest-neighbour magnification, including sample seconds/badges. */
    fun preview(): BufferedImage {
        val atlas = atlas()
        val sheet = BufferedImage(8 * 18 * 8, 2 * 16 * 8, BufferedImage.TYPE_INT_ARGB)
        for (index in StatusEffectHudGlyphs.IDS.indices) {
            val ox = (index % 8) * 18
            val oy = (index / 8) * 16
            for (y in 0 until 12) for (x in 0 until 13) {
                val color = if (x < 9) atlas.getRGB(index * 9 + x, y) else if (x == 9) 0 else {
                    // Example: two stacks at the top, four seconds at the bottom.
                    val numberY = if (y < 6) 24 + y else 12 + y
                    atlas.getRGB((if (y < 6) 2 else 4) * 9 + x - 10, numberY)
                }
                for (dy in 0..7) for (dx in 0..7) sheet.setRGB((ox + x + 2) * 8 + dx, (oy + y + 2) * 8 + dy, color)
            }
        }
        return sheet
    }
}
