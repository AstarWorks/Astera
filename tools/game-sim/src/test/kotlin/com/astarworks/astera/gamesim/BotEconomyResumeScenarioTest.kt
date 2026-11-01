package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.bots.BotHost
import com.astarworks.astera.bots.BotSkill
import com.astarworks.astera.bots.EconomyPlanner
import com.astarworks.astera.bots.HostLine
import com.astarworks.astera.bots.HostScreen
import com.astarworks.astera.bots.Objective
import com.astarworks.astera.bots.ObjectiveSource
import com.astarworks.astera.bots.Seen
import com.astarworks.astera.bots.TerrainView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Random
import java.util.UUID
import kotlin.math.hypot

/**
 * The economy trip of the shipped [BotBrain] through the [BotHost] seam: a bot earns the ore of the next piece (the iron generator of
 * its base drops into the bag while it stands there), is not pulled away by a far star, picks up a star that is right on its way and
 * goes back to the generator, then walks to the shop and buys the one piece it can pay (the host plays the server-side buyer).
 */
class BotEconomyResumeScenarioTest {

    private class StarItem(override val id: Int, override val x: Double, override val y: Double, override val z: Double) : Seen {
        override val uuid: UUID = UUID.nameUUIDFromBytes("star-$id".toByteArray())
        override val type = "ITEM"
    }

    private val shop = 0.0 to -40.0
    private val generator = 40.0 to 0.0

    private class EcoHost : BotHost {
        var clock = 1_000_000L
        var iron = 0
        val owned = HashSet<String>()
        val stars = HashMap<Int, StarItem>()
        var dropAt = 0L
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
        override fun healthOf(entityId: Int): Float? = null
        override fun hotbar() = listOf(1, -1, -1, -1, -1, -1, -1, -1, -1)
        override fun hotbarName(slot: Int) = "Knight Sword"
        override fun hotbarLore(slot: Int) = "[LMB] Slash"
        override fun carriedStacks() = if (iron > 0) listOf("Iron Ore" to iron) else emptyList()
        override fun carriedItemIds(): Set<String> = owned
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

    private val buys = EconomyPlanner.parseShoppingList(Files.readString(GameSim.defaultContentDir().resolve("shops/match-shop.yaml")))

    private val stage = object : ObjectiveSource {
        override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? = null
        override fun starPoints() = listOf(0.0 to 60.0)
        override fun ownTeamAt(spawnX: Double, spawnZ: Double) = "red"
        override fun economySites(team: String) = EconomyPlanner.Sites(
            EconomyPlanner.Site(shop.first, 64.0, shop.second),
            listOf(EconomyPlanner.Site(generator.first, 64.0, generator.second, "ore-iron")),
            emptyList(),
        )
        override fun economyLadder() = buys
    }

    private fun dist(h: EcoHost, p: Pair<Double, Double>) = hypot(h.x - p.first, h.z - p.second)

    /** One 50 ms server step: generator drops reach a bot standing next to it, the buyer sells the first piece at the shop. */
    private fun EcoHost.world(price: Int) {
        if (dist(this, generator) <= 2.0 && clock - dropAt >= 400) { iron++; dropAt = clock }
        if (dist(this, shop) <= 4.5 && iron >= price && owned.add(buys.first().items.single())) iron -= price
    }

    @Test
    fun `a bot keeps collecting past a far star, buys the first piece the moment it can pay it, and does not wait for the whole tier`() {
        val host = EcoHost()
        val first = buys.first()
        val price = first.price.getValue("ore-iron")
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), stage, chooseNormaGoal = true, rnd = Random(5))
        brain.mode = BotBrain.Mode.FIGHT
        brain.tick() // the match clock starts here; the opening (first star before any economy detour) is over 90 s later
        host.clock += 95_000
        host.stars[10] = StarItem(10, -60.0, 64.0, 50.0) // far from the generator and from the bot: not a detour
        var reachedGenerator = false
        var wentToStar = false
        var boughtAtTick = -1
        repeat(2_400) { tick ->
            host.clock += 50
            brain.tick()
            host.world(price)
            if (dist(host, generator) <= 2.0) reachedGenerator = true
            if (!reachedGenerator && dist(host, -60.0 to 50.0) < 10.0) wentToStar = true
            if (boughtAtTick < 0 && first.items.single() in host.owned) boughtAtTick = tick
        }
        assertThat(reachedGenerator).`as`("the bot walks to its iron generator").isTrue()
        assertThat(wentToStar).`as`("a far star does not pull the bot off the economy trip").isFalse()
        assertThat(boughtAtTick).`as`("the first piece (%s iron) is bought, not the 22-iron tier", price).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `a bot that detours for a star on its way returns to the generator and still buys`() {
        val host = EcoHost()
        val first = buys.first()
        val price = first.price.getValue("ore-iron")
        val brain = BotBrain(host, BotSkill(aimErrorDeg = 0f), stage, chooseNormaGoal = true, rnd = Random(5))
        brain.mode = BotBrain.Mode.FIGHT
        host.stars[11] = StarItem(11, 4.0, 64.0, 2.0) // right next to the bot: picked up on the way
        var tookStar = false
        var reachedGenerator = false
        repeat(2_400) {
            host.clock += 50
            brain.tick()
            host.world(price)
            if (11 in host.stars && dist(host, 4.0 to 2.0) < 1.5) { host.stars.remove(11); tookStar = true }
            if (dist(host, generator) <= 2.0) reachedGenerator = true
        }
        assertThat(tookStar).`as`("a star within the detour range is picked up").isTrue()
        assertThat(reachedGenerator).`as`("after the star the bot is back on its economy trip").isTrue()
        assertThat(first.items.single() in host.owned).`as`("and the piece is bought").isTrue()
    }
}
