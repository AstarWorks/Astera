package com.astarworks.astera.core.domain.lobby

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LobbyIslandGeneratorTest {

    private val lanes = listOf("norma", "annihilation", "break-core", "carry-tnt", "capture-wool")

    private val plan = LobbyIslandGenerator.generate(seed = 20261022L, centerX = 0, centerZ = 0, surfaceY = 64, lanes = lanes)

    @Test
    fun `generation is deterministic for the same seed`() {
        val again = LobbyIslandGenerator.generate(20261022L, 0, 0, 64, lanes)
        assertThat(again.blocks).isEqualTo(plan.blocks)
        assertThat(again.npcSpots).isEqualTo(plan.npcSpots)
    }

    @Test
    fun `a different seed changes the terrain`() {
        val other = LobbyIslandGenerator.generate(1L, 0, 0, 64, lanes)
        assertThat(other.blocks).isNotEqualTo(plan.blocks)
    }

    @Test
    fun `the build stays inside a sane footprint around the surface (the training island stretches it east)`() {
        assertThat(plan.maxX - plan.minX).isLessThan(220)
        assertThat(plan.maxZ - plan.minZ).isLessThan(130)
        assertThat(plan.maxY - plan.minY).isLessThan(80)
        assertThat(plan.blocks.size).isBetween(20_000, 200_000)
        // everything sits well above the void-rescue line used by content/worlds/lobby.yaml
        assertThat(plan.minY).isGreaterThan(19)
    }

    @Test
    fun `the plan is translation invariant in structure`() {
        val moved = LobbyIslandGenerator.generate(20261022L, 100, -40, 64, lanes)
        assertThat(moved.blocks.size).isNotEqualTo(0)
        assertThat(moved.maxX - moved.minX).isEqualTo(plan.maxX - plan.minX)
    }

    @Test
    fun `spawn is on solid ground with headroom`() {
        assertThat(plan.isWalkable(plan.spawn)).isTrue()
    }

    @Test
    fun `every recommended NPC stand is on a walkable block`() {
        assertThat(plan.npcSpots.keys).containsExactlyInAnyOrder(
            "shop-npc", "queue-npc", "travel-npc", "party-npc",
            "profile-npc", "settings-npc", "loadout-npc",
        )
        plan.npcSpots.forEach { (id, spot) ->
            assertThat(plan.isWalkable(com.astarworks.astera.core.domain.geometry.Vec3(spot.x, spot.y, spot.z)))
                .describedAs("NPC $id at $spot")
                .isTrue()
        }
    }

    @Test
    fun `one queue pad per lane, walkable, with a sign carrying its i18n keys`() {
        assertThat(plan.lanePads.keys).containsExactlyElementsOf(lanes)
        plan.lanePads.forEach { (lane, pos) ->
            assertThat(plan.isWalkable(pos)).describedAs("pad $lane").isTrue()
            val sign = plan.signs.single { s -> s.placeholders["lane"] == LobbyLanePadLayout.displayName(lane) }
            assertThat(sign.lines.first()?.value).isEqualTo("astera.lobby.sign.lane.title")
            assertThat(plan.stateAt(sign.pos.x, sign.pos.y, sign.pos.z)).isEqualTo(sign.state)
        }
    }

    @Test
    fun `every sign position holds its sign block and every i18n key is namespaced`() {
        plan.signs.forEach { sign ->
            assertThat(plan.stateAt(sign.pos.x, sign.pos.y, sign.pos.z)).isEqualTo(sign.state)
            assertThat(sign.lines).hasSize(4)
            sign.lines.filterNotNull().forEach { assertThat(it.value).startsWith("astera.lobby.sign.") }
        }
    }

    @Test
    fun `the islands are connected - every NPC stand and pad is reachable from spawn over walkable cells`() {
        val start = Triple(Math.floor(plan.spawn.x).toInt(), plan.surfaceY + 1, Math.floor(plan.spawn.z).toInt())
        val seen = HashSet<Triple<Int, Int, Int>>()
        val queue = ArrayDeque<Triple<Int, Int, Int>>().apply { add(start) }
        seen += start
        while (queue.isNotEmpty()) {
            val (x, y, z) = queue.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val next = Triple(x + dx, y, z + dz)
                if (next !in seen && plan.isWalkable(next.first, next.second, next.third)) {
                    seen += next
                    queue.add(next)
                }
            }
        }
        plan.npcSpots.forEach { (id, s) ->
            assertThat(seen).describedAs("NPC $id reachable").contains(Triple(Math.floor(s.x).toInt(), s.y.toInt(), Math.floor(s.z).toInt()))
        }
        plan.lanePads.forEach { (lane, p) ->
            assertThat(seen).describedAs("pad $lane reachable").contains(Triple(Math.floor(p.x).toInt(), p.y.toInt(), Math.floor(p.z).toInt()))
        }
    }

    @Test
    fun `only valid block data names are used and leaves never decay`() {
        val names = plan.blocks.values.map { it.substringAfter(':').substringBefore('[') }.toSet()
        assertThat(names).doesNotContain("air")
        plan.blocks.values.filter { it.endsWith("_leaves") || it.contains("_leaves[") }
            .forEach { assertThat(it).contains("persistent=true") }
    }

    @Test
    fun `pads are generated for any lane list, all reachable and distinct`() {
        for (n in listOf(0, 1, 3, 5, 6, 9, 12)) {
            val ids = (1..n).map { "lane-$it" }
            val p = LobbyIslandGenerator.generate(20261022L, 0, 0, 64, ids)
            assertThat(p.lanePads.keys).describedAs("n=$n").containsExactlyElementsOf(ids)
            assertThat(p.lanePads.values.toSet()).describedAs("n=$n distinct").hasSize(n)
            p.lanePads.forEach { (lane, pos) ->
                assertThat(p.isWalkable(pos)).describedAs("n=$n pad $lane").isTrue()
                assertThat(LobbyLanePadLayout.laneAt(p.lanePads, 64, pos)).isEqualTo(lane)
            }
            assertThat(p.signs.filter { it.placeholders.containsKey("lane") }).hasSize(n)
        }
    }
}
