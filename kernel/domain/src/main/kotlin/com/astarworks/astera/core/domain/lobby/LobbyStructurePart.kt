package com.astarworks.astera.core.domain.lobby

/**
 * A small hand-authored building in a light text format (MAP-07 "structure parts"): a palette plus
 * layers. `layers[y][row][col]` is one palette char; row 0 is the north (back) edge, col 0 the west edge,
 * ' ' and '.' leave the cell alone. A palette value of `minecraft:air` also leaves it alone, so one shape can
 * be reused with parts of it switched off. Pure data, no I/O, so [LobbyIslandGenerator] stays deterministic.
 *
 * [place] stamps the part with its north-west corner at `(ox, oy, oz)`, turned `rotation` quarter turns
 * clockwise seen from above (`facing=` and `axis=` properties turn with it). A part turned an odd number of
 * times has its width and depth swapped.
 */
public class LobbyStructurePart(
    private val palette: Map<Char, String>,
    private val layers: List<List<String>>,
) {
    public val width: Int = layers.maxOf { layer -> layer.maxOf { it.length } }
    public val depth: Int = layers.maxOf { it.size }

    init {
        for (layer in layers) for (row in layer) for (ch in row) {
            require(ch == ' ' || ch == '.' || palette.containsKey(ch)) { "part palette has no entry for '$ch'" }
        }
    }

    public fun place(ox: Int, oy: Int, oz: Int, rotation: Int, put: (x: Int, y: Int, z: Int, state: String) -> Unit) {
        val r = ((rotation % 4) + 4) % 4
        layers.forEachIndexed { y, layer ->
            layer.forEachIndexed { v, row ->
                row.forEachIndexed { u, ch ->
                    if (ch == ' ' || ch == '.') return@forEachIndexed
                    val state = palette.getValue(ch)
                    if (state == AIR) return@forEachIndexed
                    val (x, z) = when (r) {
                        0 -> u to v
                        1 -> (depth - 1 - v) to u
                        2 -> (width - 1 - u) to (depth - 1 - v)
                        else -> v to (width - 1 - u)
                    }
                    put(ox + x, oy + y, oz + z, turn(state, r))
                }
            }
        }
    }

    private fun turn(state: String, quarters: Int): String {
        if (quarters == 0) return state
        var out = state
        FACING.find(out)?.let { m ->
            val i = COMPASS.indexOf(m.groupValues[1])
            out = out.replaceRange(m.groups[1]!!.range, COMPASS[(i + quarters) % 4])
        }
        if (quarters % 2 == 1) {
            if (out.contains("axis=x")) out = out.replace("axis=x", "axis=z") else if (out.contains("axis=z")) out = out.replace("axis=z", "axis=x")
        }
        return out
    }

    private companion object {
        const val AIR = "minecraft:air"
        val COMPASS = listOf("north", "east", "south", "west")
        val FACING = Regex("""facing=(north|east|south|west)""")
    }
}
