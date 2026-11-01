package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.bots.BotHost
import com.astarworks.astera.bots.BotSkill
import com.astarworks.astera.bots.HostLine
import com.astarworks.astera.bots.HostScreen
import com.astarworks.astera.bots.Seen
import com.astarworks.astera.bots.TerrainView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * BotBrain target choice with enemy HP (BotHost.healthOf): no wall, a healthy enemy nearer and a 0.1 HP enemy farther;
 * every shot must aim at the low-HP one.
 */
class BotBrainHuntHpScenarioTest {

    private class FakeSeen(override val id: Int, override val x: Double, override val y: Double, override val z: Double, val name: String) : Seen {
        override val uuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
        override val type = "PLAYER"
    }

    private class FakeHost : BotHost {
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
            override fun isSolid(x: Int, y: Int, z: Int): Boolean? {
                if (x !in -50..50 || z !in -50..50) return null
                return y <= 63
            }
            override fun knows(x: Double, z: Double) = x in -50.0..50.0 && z in -50.0..50.0
            override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? = if (knows(x, z)) 64.0 else null
        }
        val near = FakeSeen(2, 0.0, 64.0, 6.0, "blue-near")
        val far = FakeSeen(3, 12.0, 64.0, 10.0, "blue-far")
        override val entities: Map<Int, Seen> get() = mapOf(2 to near, 3 to far)
        override val entityFlags: Map<Int, Int> = emptyMap()
        override val entityTextsById: Map<Int, List<String>> = emptyMap()
        override val playerNames: Map<UUID, String> get() = mapOf(near.uuid to near.name, far.uuid to far.name)
        override val actionBars: List<HostLine> = emptyList()
        override val screens: List<HostScreen> = emptyList()
        override val bossBarTitles: List<String> = emptyList()
        override val sounds: List<String> = emptyList()
        // Every shot lands (the 0.1 HP enemy is a real, hittable target): without floating damage numbers the brain would
        // rightly drop it as an ineffective target after the 5 s grace (IneffectiveTargets) and fall back to the near one.
        override val damageNumbers: List<String> get() = shotYaws.map { "1" }
        override var lift = 0.0
        val shotYaws = ArrayList<Float>()
        /** Where the bot stood at each shot: it strafes and closes in, so the bearing of a target changes from shot to shot. */
        val shotFrom = ArrayList<Pair<Double, Double>>()

        override fun now() = clock
        override fun note(text: String) {}
        override fun isSpectator(player: UUID) = false
        override fun teamOf(player: String) = if (player == name) "red" else "blue"
        override fun healthOf(entityId: Int): Float? = if (entityId == 3) 0.1f else 1f
        override fun hotbar() = listOf(1, -1, -1, -1, -1, -1, -1, -1, -1)
        override fun hotbarName(slot: Int) = "Pistol"
        override fun hotbarLore(slot: Int) = "[RMB] Shoot | Reload with Shift+Q"
        override fun carriedStacks() = emptyList<Pair<String, Int>>()
        override fun command(cmd: String) {}
        override fun selectHotbar(slot: Int) { selectedSlot = slot }
        override fun useItem(yaw: Float, pitch: Float) { shotYaws += yaw; shotFrom += x to z }
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

    @Test
    fun `bot targets the low-hp enemy over a nearer healthy one`() {
        val host = FakeHost()
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), chooseNormaGoal = false, rnd = Random(7))
        brain.mode = BotBrain.Mode.FIGHT
        repeat(120) {
            host.clock += 50
            brain.tick()
        }

        assertThat(brain.shots.get()).`as`("the bot shoots").isGreaterThan(3)
        assertThat(host.shotYaws).isNotEmpty
        // The bot moves while it shoots, so judge each shot against the bearing (MC yaw) of both targets from where the bot stood:
        // every shot must be nearer to the 0.1 HP enemy's bearing than to the healthy near one's.
        fun bearing(from: Pair<Double, Double>, tx: Double, tz: Double) = Math.toDegrees(Math.atan2(-(tx - from.first), tz - from.second)).toFloat()
        fun gap(a: Float, b: Float) = Math.abs(((a - b + 540f) % 360f) - 180f)
        host.shotYaws.indices.forEach { i ->
            val from = host.shotFrom[i]
            val toLow = gap(host.shotYaws[i], bearing(from, host.far.x, host.far.z))
            val toNear = gap(host.shotYaws[i], bearing(from, host.near.x, host.near.z))
            assertThat(toLow).`as`("shot $i aims at the 0.1 HP enemy (off by $toLow deg), not the nearer healthy one (off by $toNear deg)").isLessThan(toNear)
        }
    }
}
