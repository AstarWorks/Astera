package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * SKL-14 R1: block displays are re-written every tick like v1 and live exactly as many ticks as v1. The expected numbers are
 * read from the v1 reference dump (`d41b82f1e`, `gossamer-katana-def` / `gossamer-katana-s1`): spawn and removal ticks of every
 * block display, and the per-tick scale / rotation the client is told (`VfxPlayer.advanceEffect`: update at the tick, removal at
 * `age >= despawnTick`, VfxPlayer.kt:391-500). Real shipped content through the real cast runtime and the display gateway.
 */
class BlockDisplayV1ParityScenarioTest {

    private class Ev(val tick: Int, val id: Int, val left: List<Double>?, val scale: List<Double>?)

    private class Blocks(lines: List<String>) {
        val spawns = ArrayList<Pair<Int, String>>()
        val removes = ArrayList<Int>()
        private val ids = HashSet<Int>()
        private val last = HashMap<Pair<Int, Int>, Ev>()

        init {
            for (l in lines) {
                val t = Regex("""^\{"t":(\d+),""").find(l)?.groupValues?.get(1)?.toInt() ?: continue
                val id = Regex(""""id":(\d+)""").find(l)?.groupValues?.get(1)?.toInt() ?: continue
                when {
                    l.contains("\"e\":\"spawn\"") && l.contains("\"kind\":\"block\"") -> {
                        ids += id
                        spawns += t to Regex(""""block":"([a-z_]+)"""").find(l)!!.groupValues[1]
                    }
                    l.contains("\"e\":\"remove\"") && id in ids -> removes += t
                    l.contains("\"e\":\"write\"") && id in ids -> last[t to id] = Ev(t, id, arr(l, "left"), arr(l, "scale"))
                }
            }
        }

        private fun arr(l: String, key: String) = Regex(""""$key":\[([^\]]*)\]""").find(l)?.groupValues?.get(1)?.split(',')?.map { it.toDouble() }

        /** The state the client ends the tick with (the last write of that tick). */
        fun at(tick: Int, id: Int): Ev = requireNotNull(last[tick to id]) { "block $id has no write at tick $tick" }
    }

    @Test
    fun `gossamer-katana-def blocks scale every tick and vanish at the v1 tick`() {
        val b = Blocks(VanillaDump.dump("gossamer-katana-def"))
        assertThat(b.spawns).containsOnly(0 to "cyan_stained_glass").hasSize(5)
        assertThat(b.removes.sorted()).containsExactly(10, 10, 10, 10, 10)
        // v1 update stream of the first glass pane (scale x; y and z follow), one value per tick
        val expectX = mapOf(0 to 0.084, 1 to 0.42, 2 to 0.41343749999999996, 3 to 0.39375, 4 to 0.36093749999999997, 5 to 0.315)
        for ((tick, x) in expectX) assertThat(b.at(tick, 0).scale!![0]).describedAs("scale.x at $tick").isCloseTo(x, Offset.offset(2e-5))
        assertThat(b.at(2, 0).scale!![2]).isCloseTo(0.2953125, Offset.offset(2e-5))
    }

    @Test
    fun `gossamer-katana-s1 blades keep v1 spawn removal and per-tick rotation`() {
        val b = Blocks(VanillaDump.dump("gossamer-katana-s1"))
        assertThat(b.spawns.sortedBy { it.first }.map { it.first }).containsExactly(0, 0, 0, 10, 10, 10, 22, 22, 22, 39, 39, 39)
        assertThat(b.removes.sorted()).containsExactly(22, 22, 22, 29, 29, 29, 34, 34, 34, 45, 73, 75)
        // the first blade (iron_block): v1 spawn rotation, then the swing rotation sent at ticks 27 and 28
        val first = b.at(0, 0)
        listOf(-0.7618479715070196, -0.08007344843395217, 0.06718960104802223, 0.6392663519046992)
            .forEachIndexed { i, v -> assertThat(first.left!![i]).isCloseTo(v, Offset.offset(2e-5)) }
        listOf(-0.7266892660178348, -0.07637811958058026, 0.07136233237802539, 0.6789672385479021)
            .forEachIndexed { i, v -> assertThat(b.at(27, 0).left!![i]).describedAs("left at 27").isCloseTo(v, Offset.offset(2e-5)) }
        listOf(-0.6092446553091818, -0.0640341935859722, 0.08261853112281423, 0.7860628157749859)
            .forEachIndexed { i, v -> assertThat(b.at(28, 0).left!![i]).describedAs("left at 28").isCloseTo(v, Offset.offset(2e-5)) }
    }
}
