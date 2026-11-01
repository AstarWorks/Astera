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
 * ADR-0318 star blocks (barrel / chest / crafting table pay a star when broken) through the [BotHost] seam with the shipped
 * [BotBrain]: an idle bot detours to a barrel 6 blocks away and digs it, ignores one 30 blocks away (outside the scan
 * radius) and, with an enemy engaging, fights instead of digging. Not run by the agent; the manager's window runs it.
 */
class BotStarBlockScenarioTest {

    private class Foe(override val id: Int, override val x: Double, override val y: Double, override val z: Double, val name: String) : Seen {
        override val uuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
        override val type = "PLAYER"
    }

    private class Dig(val bx: Int, val by: Int, val bz: Int, val botX: Double, val botZ: Double, val holdMs: Long, val slot: Int)

    /**
     * [blocks] are barrels; each dig breaks one. [paying] = the server pays a star (plays `astera:star/pickup`) per break; false = the
     * block bucket is empty. [hotbarNames] fills slots 0.. (slot 0 is the sword).
     */
    private class BlockHost(
        blocks: Set<Triple<Int, Int, Int>>,
        private val foe: Foe? = null,
        private val paying: Boolean = true,
        private val hotbarNames: List<String> = listOf("Knight Sword"),
    ) : BotHost {
        constructor(blockAt: Triple<Int, Int, Int>, foe: Foe? = null) : this(setOf(blockAt), foe)
        private val standing = blocks.toMutableSet()
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
        private fun isBarrel(x: Int, y: Int, z: Int) = Triple(x, y, z) in standing
        override val terrain = object : TerrainView {
            override fun isSolid(x: Int, y: Int, z: Int): Boolean? =
                if (x !in -60..60 || z !in -60..60) null else y <= 63 || isBarrel(x, y, z)
            override fun knows(x: Double, z: Double) = x in -60.0..60.0 && z in -60.0..60.0
            override fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int, maxDrop: Int): Double? = if (knows(x, z)) 64.0 else null
            override fun blockName(x: Int, y: Int, z: Int): String? = when {
                x !in -60..60 || z !in -60..60 -> null
                isBarrel(x, y, z) -> "minecraft:barrel"
                y <= 63 -> "minecraft:stone"
                else -> "minecraft:air"
            }
        }
        override val entities: Map<Int, Seen> get() = foe?.let { mapOf(it.id to it) } ?: emptyMap()
        override val entityFlags: Map<Int, Int> = emptyMap()
        override val entityTextsById: Map<Int, List<String>> = emptyMap()
        override val playerNames: Map<UUID, String> get() = foe?.let { mapOf(it.uuid to it.name) } ?: emptyMap()
        override val actionBars: List<HostLine> = emptyList()
        override val screens: List<HostScreen> = emptyList()
        override val bossBarTitles: List<String> = emptyList()
        override val sounds = ArrayList<String>()
        override val damageNumbers: List<String> = emptyList()
        override var lift = 0.0
        val digs = ArrayList<Dig>()

        override fun now() = clock
        override fun note(text: String) {}
        override fun isSpectator(player: UUID) = false
        override fun teamOf(player: String) = if (player == name) "red" else "blue"
        override fun healthOf(entityId: Int): Float? = null
        override fun hotbar() = List(9) { if (it < hotbarNames.size) it + 1 else -1 }
        override fun hotbarName(slot: Int) = hotbarNames.getOrElse(slot) { "" }
        override fun hotbarLore(slot: Int) = if (slot == 0) "[LMB] Slash" else ""
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
        override fun dig(bx: Int, by: Int, bz: Int, holdMs: Long) {
            digs += Dig(bx, by, bz, x, z, holdMs, selectedSlot)
            if (standing.remove(Triple(bx, by, bz)) && paying) sounds += "astera:star/pickup"
        }
        override fun digAsync(bx: Int, by: Int, bz: Int, holdMs: Long) = dig(bx, by, bz, holdMs)
        override fun closeWindow(id: Int) {}
        override fun nudge() {}
        var onMove: () -> Unit = {}
        override fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) { x = nx; y = ny; z = nz; yawDeg = yaw; pitchDeg = pitch; onMove() }
    }

    private class CacheStage(private val cache: Triple<Double, Double, Double>) : ObjectiveSource {
        override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? = null
        override fun cachePoints() = listOf(ObjectiveSource.CachePoint(cache.first, cache.second, cache.third, 45_000L))
    }

    private class Stage(private val spots: List<Pair<Double, Double>>) : ObjectiveSource {
        override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? = null
        override fun starPoints() = spots
    }

    private fun brain(host: BotHost, spots: List<Pair<Double, Double>> = emptyList()) =
        BotBrain(host, BotSkill(aimErrorDeg = 0f), Stage(spots), chooseNormaGoal = true, rnd = Random(11))
            .also { it.mode = BotBrain.Mode.FIGHT }

    @Test
    fun `idle bot walks to a barrel 6 blocks away and digs it`() {
        val host = BlockHost(Triple(6, 64, 0))
        val brain = brain(host)
        repeat(400) {
            host.clock += 50
            brain.tick()
        }
        assertThat(host.digs).`as`("the bot issues a dig on the barrel").isNotEmpty
        val first = host.digs.first()
        assertThat(Triple(first.bx, first.by, first.bz)).isEqualTo(Triple(6, 64, 0))
        assertThat(hypot(first.botX - 6.5, first.botZ - 0.5)).`as`("it walked up to the barrel first").isLessThanOrEqualTo(4.5)
        assertThat(host.digs.size).`as`("a broken (air) block is not dug again").isEqualTo(1)
    }

    @Test
    fun `a barrel 30 blocks away is outside the scan radius and ignored`() {
        val host = BlockHost(Triple(30, 64, 0))
        // The stage's only star spot lies the other way, so the idle bot does not roam towards the barrel.
        val brain = brain(host, listOf(-45.0 to 0.0))
        var nearest = Double.MAX_VALUE
        repeat(400) {
            host.clock += 50
            brain.tick()
            nearest = minOf(nearest, hypot(host.x - 30.5, host.z - 0.5))
        }
        assertThat(nearest).`as`("the bot never came within the scan radius of the barrel").isGreaterThan(12.0)
        assertThat(host.digs).`as`("no dig on the far barrel").isEmpty()
    }

    @Test
    fun `an engaging enemy wins over the barrel`() {
        val host = BlockHost(Triple(6, 64, 0), Foe(2, 0.0, 64.0, -7.0, "blue-foe"))
        val brain = brain(host)
        repeat(200) {
            host.clock += 50
            brain.tick()
        }
        assertThat(brain.ticksWithEnemy.get()).`as`("the bot is engaged with the enemy").isGreaterThan(50)
        assertThat(host.digs).`as`("it fights instead of digging the barrel").isEmpty()
    }

    @Test
    fun `an empty block bucket stops the bot after two unpaid breaks`() {
        val barrels = setOf(Triple(6, 64, 0), Triple(0, 64, 6), Triple(-6, 64, 0), Triple(0, 64, -6))
        val dry = BlockHost(barrels, paying = false)
        val dryBrain = brain(dry)
        repeat(2000) { dry.clock += 50; dryBrain.tick() }
        assertThat(dry.digs).`as`("two broken blocks without a star, then the bot gives up the rest").hasSize(2)
        assertThat(dryBrain.starBlocksDry).isTrue()

        val paid = BlockHost(barrels, paying = true)
        val paidBrain = brain(paid)
        repeat(2000) { paid.clock += 50; paidBrain.tick() }
        assertThat(paid.digs).`as`("every break pays a star, so every block is taken").hasSize(4)
        assertThat(paidBrain.starBlocksDry).isFalse()
    }

    @Test
    fun `dig hold follows the vanilla break time of the tool in hand`() {
        // hardness 2.5 wood: ticks = ceil(75 / speed), +3 ticks margin, 50 ms each. Bare hand speed 1, stone 4, iron 6, diamond 8.
        val expected = listOf(null to 3_900L, "Stone Axe" to 1_100L, "Iron Axe" to 800L, "Diamond Axe" to 650L)
        for ((axe, hold) in expected) {
            val host = BlockHost(setOf(Triple(6, 64, 0)), hotbarNames = listOfNotNull("Knight Sword", axe))
            val brain = brain(host)
            repeat(400) { host.clock += 50; brain.tick() }
            assertThat(host.digs).`as`("dig with ${axe ?: "bare hand"}").hasSize(1)
            assertThat(host.digs.single().holdMs).`as`("hold with ${axe ?: "bare hand"}").isEqualTo(hold)
            if (axe != null) assertThat(host.digs.single().slot).`as`("the axe is in hand").isEqualTo(1)
        }
    }

    @Test
    fun `a cache that pays nothing is retried and pays once it has tokens`() {
        val host = BlockHost(emptySet())
        val cache = Triple(8.0, 64.0, 0.0)
        val payAt = host.clock + 25_000L
        var visits = 0
        var wasNear = false
        var paidAt: Long? = null
        host.onMove = {
            val near = hypot(host.x - cache.first, host.z - cache.third) <= 3.0
            if (near && !wasNear) visits++
            wasNear = near
            // The server tries every 5 s while the bot stands in the open radius; an empty bucket pays nothing, so only a later visit is paid.
            if (near && host.clock >= payAt && paidAt == null) { host.sounds += "astera:star/pickup"; paidAt = host.clock }
        }
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), CacheStage(cache), chooseNormaGoal = true, rnd = Random(11))
            .also { it.mode = BotBrain.Mode.FIGHT }
        repeat(1_200) { host.clock += 50; brain.tick() }
        assertThat(visits).`as`("the unpaid cache is walked to again, not written off for 45 s").isGreaterThanOrEqualTo(2)
        assertThat(paidAt).`as`("a retry after the cache had tokens is paid").isNotNull
    }
}
