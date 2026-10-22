package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.application.port.outbound.lobby.LobbyTrainingRange
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.floor

/** F3-11: the training island's geometry, region rules and connection to the rest of the lobby. */
class LobbyTrainingLayoutTest {

    private val lanes = listOf("norma", "annihilation", "break-core")
    private val plan = LobbyIslandGenerator.generate(20261022L, 0, 0, 64, lanes)
    private val training = plan.training

    @Test
    fun `the plan on the build equals the standalone layout`() {
        assertThat(training).isEqualTo(LobbyTrainingLayout.plan(0, 0, 64))
    }

    @Test
    fun `range dummies stand at 5 10 20 and 40 blocks from the firing line, one lane each`() {
        val range = training.dummies.filter { it.kind == TrainingDummyKind.RANGE }
        assertThat(range.map { it.distance }).containsExactly(5, 10, 20, 40)
        val shootX = training.spawn.x
        range.forEach { assertThat(it.x - shootX).isEqualTo(it.distance!!.toDouble()) }
        assertThat(range.map { it.z }.toSet()).hasSize(4) // a dummy in front must not shield the ones behind it
    }

    @Test
    fun `there is a melee pit with dummies and a moving target on a track`() {
        assertThat(training.dummies.count { it.kind == TrainingDummyKind.MELEE }).isGreaterThanOrEqualTo(3)
        val mover = training.dummies.single { it.kind == TrainingDummyKind.MOVING }
        assertThat(mover.track).isNotNull
        val track = mover.track!!
        assertThat(track.positionAt(0)).isEqualTo(track.fromX to track.fromZ)
        // ping-pong: after one full length it is at the far end, after two it is home again
        val length = track.toX - track.fromX
        val ticksOneWay = (length / track.blocksPerTick).toLong()
        assertThat(track.positionAt(ticksOneWay).first).isBetween(track.toX - 0.2, track.toX)
        assertThat(track.positionAt(ticksOneWay * 2).first).isBetween(track.fromX, track.fromX + 0.2)
        (0L..2000L step 7).forEach {
            val (x, _) = track.positionAt(it)
            assertThat(x).isBetween(track.fromX, track.toX)
        }
    }

    @Test
    fun `every dummy stands on solid ground with room for its body, and range lanes are clear`() {
        training.dummies.forEach { d ->
            val positions = if (d.track == null) listOf(d.x to d.z) else (0L..3000L step 50).map { d.track!!.positionAt(it) }
            positions.forEach { (x, z) ->
                val bx = floor(x).toInt()
                val bz = floor(z).toInt()
                val feet = floor(d.y).toInt()
                assertThat(plan.isSupport(plan.stateAt(bx, feet - 1, bz))).describedAs("${d.id} floor at $bx,$bz").isTrue()
                assertThat(plan.isBodyPassable(plan.stateAt(bx, feet, bz))).describedAs("${d.id} feet").isTrue()
                assertThat(plan.isBodyPassable(plan.stateAt(bx, feet + 1, bz))).describedAs("${d.id} head").isTrue()
            }
        }
        // the line of fire from the firing line to each range dummy is open at body height
        training.dummies.filter { it.kind == TrainingDummyKind.RANGE }.forEach { d ->
            val z = floor(d.z).toInt()
            for (x in floor(training.spawn.x).toInt() + 1 until floor(d.x).toInt()) {
                listOf(65, 66).forEach { y ->
                    assertThat(plan.stateAt(x, y, z)).describedAs("${d.id} lane at $x,$y").isNull()
                }
            }
        }
    }

    @Test
    fun `the arrival spot is reachable on foot from the main spawn over the bridge`() {
        val start = Triple(floor(plan.spawn.x).toInt(), 65, floor(plan.spawn.z).toInt())
        val seen = HashSet<Triple<Int, Int, Int>>().apply { add(start) }
        val queue = ArrayDeque<Triple<Int, Int, Int>>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val (x, y, z) = queue.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = Triple(x + dx, y, z + dz)
                if (n !in seen && plan.isWalkable(n.first, n.second, n.third)) {
                    seen += n
                    queue.add(n)
                }
            }
        }
        assertThat(plan.isWalkable(training.spawn)).isTrue()
        assertThat(seen).contains(Triple(floor(training.spawn.x).toInt(), 65, floor(training.spawn.z).toInt()))
        // and the weapon rack is reachable too (a walkway leaves the middle of the wall open)
        val rack = training.rackSlots.first()
        assertThat(seen).anyMatch { (x, _, z) -> x == rack.x && kotlin.math.abs(z - rack.z) <= 8 }
    }

    @Test
    fun `rack frames hang in free cells in front of a wall, three rows of twelve`() {
        assertThat(training.rackSlots).hasSize(LobbyTrainingLayout.RACK_CAPACITY)
        assertThat(LobbyTrainingLayout.RACK_CAPACITY).isEqualTo(36)
        assertThat(training.rackSlots.map { it.index }).isEqualTo((0 until 36).toList())
        training.rackSlots.forEach { slot ->
            assertThat(plan.stateAt(slot.x, slot.y, slot.z)).describedAs("frame cell ${slot.index}").isNull()
            assertThat(plan.stateAt(slot.x - 1, slot.y, slot.z)).describedAs("wall behind ${slot.index}").isEqualTo("minecraft:dark_oak_planks")
            assertThat(slot.facing).isEqualTo("east")
        }
    }

    @Test
    fun `the region covers the training island only - nothing else of the lobby is inside`() {
        val region = training.region
        assertThat(region.contains(training.spawn)).isTrue()
        training.dummies.forEach { assertThat(region.contains(it.position)).describedAs(it.id).isTrue() }
        training.rackSlots.forEach { assertThat(region.contains(it.x + 0.5, it.y + 0.5, it.z + 0.5)).isTrue() }
        // main spawn, plaza, market, queue pads and every NPC stand are outside
        assertThat(region.contains(plan.spawn)).isFalse()
        plan.lanePads.values.forEach { assertThat(region.contains(it)).isFalse() }
        plan.npcSpots.forEach { (id, s) -> assertThat(region.contains(s.x, s.y, s.z)).describedAs(id).isFalse() }
        // below the island and far above it are outside as well
        assertThat(region.contains(training.spawn.x, 20.0, training.spawn.z)).isFalse()
        assertThat(region.contains(training.spawn.x, 64.0 + LobbyTrainingLayout.REGION_HEIGHT + 1, training.spawn.z)).isFalse()
    }

    @Test
    fun `the range object answers contains by world as well as position`() {
        val range = LobbyTrainingRange(WorldId("lobby"), training)
        assertThat(range.contains(WorldId("lobby"), training.spawn)).isTrue()
        assertThat(range.contains(null, training.spawn)).isTrue()
        assertThat(range.contains(WorldId("world"), training.spawn)).isFalse()
        assertThat(range.contains(WorldId("lobby"), Vec3(0.5, 65.0, 6.5))).isFalse()
    }

    @Test
    fun `signs name every lane and the training stations, with i18n keys only`() {
        val trainingSigns = plan.signs.filter { s -> s.lines.any { it?.value?.startsWith("astera.lobby.sign.training.") == true } }
        assertThat(trainingSigns.flatMap { s -> s.lines.filterNotNull().map { it.value } }).contains(
            "astera.lobby.sign.training.range.title",
            "astera.lobby.sign.training.melee.title",
            "astera.lobby.sign.training.mover.title",
            "astera.lobby.sign.training.rack.title",
        )
        assertThat(trainingSigns.filter { it.placeholders.containsKey("distance") }.map { it.placeholders["distance"] })
            .containsExactly("5", "10", "20", "40")
        assertThat(plan.signs.any { s -> s.lines.any { it?.value == "astera.lobby.sign.dir.training.title" } }).isTrue()
    }
}
