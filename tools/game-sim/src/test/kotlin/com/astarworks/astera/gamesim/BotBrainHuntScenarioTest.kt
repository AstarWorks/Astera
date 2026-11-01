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
import kotlin.math.atan2

/**
 * BotBrain's hunt wiring end to end through the [BotHost] seam (ADR-0316): a bot with a pistol on an island with a wall
 * in front of it. A nearer enemy stands behind the wall, a farther one in the open and walking sideways. The brain must
 * (1) fight the visible enemy, never the walled-off one, (2) fire with lead (yaw ahead of the enemy's current
 * position, in its walking direction). The host is a minimal in-test fake because no sim-backed BotHost exists on
 * this branch yet; the brain code under test is the shipped one.
 */
class BotBrainHuntScenarioTest {

    private class FakeSeen(override val id: Int, override val x: Double, override val y: Double, override val z: Double, val name: String) : Seen {
        override val uuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
        override val type = "PLAYER"
    }

    private class Shot(val yaw: Float, val botX: Double, val botZ: Double, val farX: Double, val farZ: Double)

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
                if (y <= 63) return true
                return z == 3 && x in -3..3 && y in 64..68 // the wall
            }
            override fun knows(x: Double, z: Double) = x in -50.0..50.0 && z in -50.0..50.0
            override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? = if (knows(x, z)) 64.0 else null
        }
        val near = FakeSeen(2, 0.0, 64.0, 6.0, "blue-near")
        var far = FakeSeen(3, 20.0, 64.0, 10.0, "blue-far")
        override val entities: Map<Int, Seen> get() = mapOf(2 to near, 3 to far)
        override val entityFlags: Map<Int, Int> = emptyMap()
        override val entityTextsById: Map<Int, List<String>> = emptyMap()
        override val playerNames: Map<UUID, String> get() = mapOf(near.uuid to near.name, far.uuid to far.name)
        override val actionBars: List<HostLine> = emptyList()
        override val screens: List<HostScreen> = emptyList()
        override val bossBarTitles: List<String> = emptyList()
        override val sounds: List<String> = emptyList()
        override val damageNumbers: List<String> = emptyList()
        override var lift = 0.0
        val shots = ArrayList<Shot>()

        override fun now() = clock
        override fun note(text: String) {}
        override fun isSpectator(player: UUID) = false
        override fun teamOf(player: String) = if (player == name) "red" else "blue"
        override fun hotbar() = listOf(1, -1, -1, -1, -1, -1, -1, -1, -1)
        override fun hotbarName(slot: Int) = "Pistol"
        override fun hotbarLore(slot: Int) = "[RMB] Shoot | Reload with Shift+Q"
        override fun carriedStacks() = emptyList<Pair<String, Int>>()
        override fun command(cmd: String) {}
        override fun selectHotbar(slot: Int) { selectedSlot = slot }
        override fun useItem(yaw: Float, pitch: Float) { shots += Shot(yaw, x, z, far.x, far.z) }
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
    fun `bot picks the visible enemy over a nearer walled-off one and fires with lead`() {
        val host = FakeHost()
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), chooseNormaGoal = false, rnd = Random(7))
        brain.mode = BotBrain.Mode.FIGHT
        repeat(120) {
            host.clock += 50
            // the open enemy strafes sideways (+x) at 0.2 blocks/tick
            host.far = FakeSeen(3, host.far.x + 0.2, 64.0, host.far.z, host.far.name)
            brain.tick()
        }

        assertThat(brain.shots.get()).`as`("the bot shoots at the visible enemy").isGreaterThan(3)
        assertThat(host.shots).allSatisfy { s ->
            // Straight ahead (yaw 0) would be the walled-off enemy at (0, 6); the open one is to the side (yaw about -60 to -70).
            assertThat(s.yaw).`as`("aims at the enemy in the open, not at the nearer one behind the wall").isLessThan(-30f)
        }
        val late = host.shots.drop(2)
        assertThat(late).isNotEmpty
        assertThat(late).allSatisfy { s ->
            val direct = Math.toDegrees(atan2(-(s.farX - s.botX), s.farZ - s.botZ)).toFloat()
            assertThat(s.yaw).`as`("yaw leads the sideways-walking enemy (more negative than a direct aim)").isLessThan(direct - 0.3f)
        }
    }
}
