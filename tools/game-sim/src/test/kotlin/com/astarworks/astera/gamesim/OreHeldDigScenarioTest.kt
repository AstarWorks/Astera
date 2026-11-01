package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreDigHold
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder 2026-11-01: "holding the button on iron ore does nothing". A node never starts vanilla cracking, so the server gets one dig-start
 * per click; [OreDigHold] must turn a held button (arm swings + crosshair on the node) into hits at the node's hit cadence until it breaks.
 * Clicking repeatedly must not beat holding, releasing or looking away stops the hits, and a locked node only refuses.
 */
class OreHeldDigScenarioTest {

    private val config = AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 4000,
        engagePhaseTicks = 200,
        centerPhaseTicks = 200,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    @Test
    fun `holding the dig breaks an iron node at the hit cadence, clicking is no faster, release and looking away stop it, a locked node only refuses`() {
        scenario("ore-held-dig", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val hold = sim.services.getOrThrow<OreDigHold>()
            val sight = sim.oreDigSight
            fun cell(x: Int, y: Int, z: Int) = Vec3(origin.x + x, y.toDouble(), origin.z + z)
            fun ix(c: Vec3) = c.x.toInt()
            fun iz(c: Vec3) = c.z.toInt()
            val ironA = cell(19, 61, -28)
            val ironB = cell(15, 61, 30)
            val ironC = cell(-34, 61, -2)
            val gold = cell(17, 56, 21) // phase 2: closed during the build phase
            val stars = mutableListOf<StarsCollected>()
            sim.services.getOrThrow<IDomainEventBus>().subscribe(StarsCollected::class) { stars += it }
            val stone = ItemId("stone-pickaxe")
            for (p in listOf(red, blue, yellow)) sight.held[p.id] = stone
            fun crack(c: Vec3) = sim.oreNodes.crackAt(ix(c), 61, iz(c))
            fun ironOf(id: com.astarworks.astera.core.domain.player.PlayerId) = checkNotNull(sim.server.view(id)).givenItems.count { it == "ore-iron" }
            advance(25)

            // 1. Hold: the click lands hit 1, then the player keeps swinging every tick with the crosshair on the node.
            sight.aimAt(red.id, ix(ironA), 61, iz(ironA))
            val first = hold.begin(red.id, worldId, ironA)
            assertThat(first).isInstanceOf(OreNodeService.Outcome.Hit::class.java)
            first as OreNodeService.Outcome.Hit
            val hitsNeeded = Math.ceilDiv(first.maxHp, first.maxHp - first.hp)
            assertThat(hitsNeeded).`as`("stone pickaxe on an 800 HP iron node").isEqualTo(8)
            var ticks = 0
            while (ironOf(red.id) == 0 && ticks < 200) {
                hold.swung(red.id)
                advance(1)
                ticks++
            }
            assertThat(ironOf(red.id)).`as`("one drop set, given once").isEqualTo(3)
            assertThat(ticks).`as`("hit cadence is the node's 6-tick cooldown").isEqualTo((hitsNeeded - 1) * OreNodeService.HIT_COOLDOWN_TICKS.toInt())
            assertThat(sim.oreNodes.bursts).hasSize(1)
            assertThat(stars.size).`as`("one star call per break at most").isLessThanOrEqualTo(1)
            advance(30)
            assertThat(ironOf(red.id)).`as`("the hold ended with the break: nothing more arrives").isEqualTo(3)

            // 2. Clicking every tick is no faster than holding (a fresh node, same tool).
            sight.aimAt(blue.id, ix(ironB), 61, iz(ironB))
            var clicks = 0
            while (ironOf(blue.id) == 0 && clicks < 200) {
                hold.begin(blue.id, worldId, ironB)
                advance(1)
                clicks++
            }
            assertThat(ironOf(blue.id)).isEqualTo(3)
            assertThat(clicks).`as`("click spam takes as long as holding").isGreaterThanOrEqualTo(ticks)

            // 3. Release (no more swings) stops the hits; so does looking away.
            sight.aimAt(yellow.id, ix(ironC), 61, iz(ironC))
            hold.begin(yellow.id, worldId, ironC)
            repeat(10) { hold.swung(yellow.id); advance(1) }
            val afterHold = crack(ironC)
            assertThat(afterHold).isNotNull
            advance(60) // the button is up: no swings
            val released = crack(ironC)
            advance(60)
            assertThat(crack(ironC)).`as`("no hits after release").isEqualTo(released)
            assertThat(released!!.first).`as`("the hold landed hits while it lasted").isLessThan(first.maxHp - 2 * (first.maxHp - first.hp))
            hold.begin(yellow.id, worldId, ironC)
            val hpBeforeLookAway = crack(ironC)!!.first
            sight.lookAway(yellow.id)
            repeat(30) { hold.swung(yellow.id); advance(1) }
            assertThat(crack(ironC)!!.first).`as`("looking away stops the hits even while swinging").isGreaterThanOrEqualTo(hpBeforeLookAway - (first.maxHp - first.hp))

            // 4. Locked node: the lock message once, no hits, no hold.
            sight.aimAt(red.id, ix(gold), 56, iz(gold))
            val barsBefore = checkNotNull(sim.server.view(red.id)).actionBars.size
            assertThat(hold.begin(red.id, worldId, gold)).isInstanceOf(OreNodeService.Outcome.PhaseLocked::class.java)
            repeat(30) { hold.swung(red.id); advance(1) }
            assertThat(checkNotNull(sim.server.view(red.id)).actionBars.size - barsBefore).`as`("the lock message shows once").isEqualTo(1)
            assertThat(sim.oreNodes.crackAt(ix(gold), 56, iz(gold))).isNull()
        }
    }
}
