package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.bots.BotHost
import com.astarworks.astera.bots.BotSkill
import com.astarworks.astera.bots.HostLine
import com.astarworks.astera.bots.HostScreen
import com.astarworks.astera.bots.Objective
import com.astarworks.astera.bots.ObjectiveSource
import com.astarworks.astera.bots.Seen
import com.astarworks.astera.bots.TerrainView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID
import kotlin.math.hypot

/**
 * Star seeking after ADR-0318 (rotating spots, exploration caches), through the [BotHost] seam with the shipped [BotBrain]:
 * (1) a dropped star item that appears on the NEWLY rotated spot is fetched, and the bot does not go back to the spot it
 * used before; (2) an exploration cache that is on its respawn cooldown is skipped, a fresh one is visited.
 * The active spot is detected from what the client sees: the star ITEM entity lying on it.
 */
class BotStarSeekScenarioTest {

    private class StarItem(override val id: Int, override val x: Double, override val y: Double, override val z: Double) : Seen {
        override val uuid: UUID = UUID.nameUUIDFromBytes("star-$id".toByteArray())
        override val type = "ITEM"
    }

    private class Point(val x: Double, val y: Double, val z: Double, val respawnMs: Long = 90_000)

    private class FlatHost : BotHost {
        var clock = 1_000_000L
        override val name = "red-bot"
        override val joined = true
        override var x = 0.0
        override var y = 64.0
        override var z = 0.0
        override var yawDeg = 0f
        override var pitchDeg = 0f
        override val health = 20f
        override val food = 20
        override val spectating = false
        override val selfEntityId = 1
        override var selectedSlot = 0
        override val terrain = object : TerrainView {
            override fun isSolid(x: Int, y: Int, z: Int): Boolean? = if (x !in -120..120 || z !in -120..120) null else y <= 63
            override fun knows(x: Double, z: Double) = x in -120.0..120.0 && z in -120.0..120.0
            override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? = if (knows(x, z)) 64.0 else null
        }
        val stars = HashMap<Int, StarItem>()
        override val entities: Map<Int, Seen> get() = HashMap<Int, Seen>(stars)
        override val entityFlags: Map<Int, Int> = emptyMap()
        override val entityTextsById: Map<Int, List<String>> = emptyMap()
        override val playerNames: Map<UUID, String> = emptyMap()
        override val actionBars: List<HostLine> = emptyList()
        override val screens: List<HostScreen> = emptyList()
        override val bossBarTitles: List<String> = emptyList()
        override val sounds: List<String> = emptyList()
        override val damageNumbers: List<String> = emptyList()
        override var lift = 0.0

        override fun now() = clock
        override fun note(text: String) {}
        override fun isSpectator(player: UUID) = false
        override fun teamOf(player: String) = "red"
        override fun hotbar() = listOf(1, -1, -1, -1, -1, -1, -1, -1, -1)
        override fun hotbarName(slot: Int) = "Knight Sword"
        override fun hotbarLore(slot: Int) = "[LMB] Slash"
        override fun carriedStacks() = emptyList<Pair<String, Int>>()
        override fun command(cmd: String) {}
        override fun selectHotbar(slot: Int) { selectedSlot = slot }
        override fun useItem(yaw: Float, pitch: Float) {}
        override fun punch() {}
        override fun attack(entityId: Int) {}
        override fun interact(entityId: Int) {}
        override fun sneak(on: Boolean) {}
        override fun setSprinting(on: Boolean) {}
        override fun respawn() {}
        override fun dropSelected(wholeStack: Boolean) {}
        override fun dig(bx: Int, by: Int, bz: Int, holdMs: Long) {}
        override fun closeWindow(id: Int) {}
        override fun nudge() {}
        override fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) { x = nx; y = ny; z = nz; yawDeg = yaw; pitchDeg = pitch }
    }

    private class Stage(private val spots: List<Pair<Double, Double>>, private val caches: List<Point>) : ObjectiveSource {
        override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? = null
        override fun starPoints() = spots
        override fun cachePoints() = caches.map { ObjectiveSource.CachePoint(it.x, it.y, it.z, it.respawnMs) }
    }

    private fun dist(h: FlatHost, x: Double, z: Double) = hypot(h.x - x, h.z - z)

    @Test
    fun `bot fetches a dropped star on the newly rotated spot and does not return to the old one`() {
        val host = FlatHost()
        val oldSpot = 40.0 to 0.0
        val newSpot = -30.0 to 25.0
        val lastSpot = 0.0 to -45.0
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), Stage(listOf(oldSpot, newSpot, lastSpot), emptyList()), chooseNormaGoal = true, rnd = Random(3))
        brain.mode = BotBrain.Mode.FIGHT

        // The rotation has moved on: the only star on the map lies on the new spot (the old spot is empty).
        host.stars[10] = StarItem(10, newSpot.first, 64.0, newSpot.second)
        var reachedNew = false
        var wentToOld = false
        repeat(500) {
            host.clock += 50
            brain.tick()
            if (dist(host, newSpot.first, newSpot.second) < 2.0) reachedNew = true
            if (!reachedNew && dist(host, oldSpot.first, oldSpot.second) < 6.0) wentToOld = true
            if (reachedNew) host.stars.remove(10) // picked up
        }
        assertThat(reachedNew).`as`("the bot walks to the star on the newly rotated spot").isTrue()
        assertThat(wentToOld).`as`("the bot does not go to the previous, now empty, spot while a star lies elsewhere").isFalse()

        // The next pop appears on yet another spot: the bot follows it.
        host.stars[11] = StarItem(11, lastSpot.first, 64.0, lastSpot.second)
        var reachedLast = false
        repeat(500) {
            host.clock += 50
            brain.tick()
            if (dist(host, lastSpot.first, lastSpot.second) < 2.0) reachedLast = true
            if (reachedLast) host.stars.remove(11)
        }
        assertThat(reachedLast).`as`("the bot follows the next rotation to the third spot").isTrue()
    }

    @Test
    fun `bot visits a fresh exploration cache and skips one that is on respawn cooldown`() {
        val host = FlatHost()
        val near = Point(10.0, 64.0, 0.0)
        val far = Point(0.0, 64.0, 35.0)
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), Stage(listOf(70.0 to 70.0), listOf(near, far)), chooseNormaGoal = true, rnd = Random(5))
        brain.mode = BotBrain.Mode.FIGHT

        var nearEntries = 0
        var inNear = false
        var reachedFar = false
        // 1500 ticks x 50 ms = 75 s of game time, inside the 90 s cache respawn: a visited cache must not be walked to a second time.
        repeat(1500) {
            host.clock += 50
            brain.tick()
            val d = dist(host, near.x, near.z)
            if (d <= 1.6 && !inNear) { nearEntries++; inNear = true } else if (d > 4.0) inNear = false
            if (dist(host, far.x, far.z) <= 1.6) reachedFar = true
        }
        assertThat(nearEntries).`as`("the near cache is visited once and then skipped while it is on cooldown").isEqualTo(1)
        assertThat(reachedFar).`as`("the bot goes on to the cache that has not been opened").isTrue()
    }
}
