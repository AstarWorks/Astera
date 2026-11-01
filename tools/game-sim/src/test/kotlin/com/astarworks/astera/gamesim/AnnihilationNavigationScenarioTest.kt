package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * ADR-0319 navigation: the compass and the objective action bar of Annihilation point at the right place. Played on the real
 * `content/` (annihilation-arena ore nodes, loaned tools by Norma level): the nearest ore node the player can mine right now,
 * the Mid right after a phase opened it, and the own pillar while it is hit.
 */
class AnnihilationNavigationScenarioTest {

    private val config = com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 400,
        engagePhaseTicks = 400,
        centerPhaseTicks = 2_000,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    /** A node of the real ore file in world coordinates: x/z are stage relative, block centre. */
    private data class Node(val block: String, val pos: Vec3, val minLevel: Int)

    private fun ScenarioScope.nodes(): List<Node> {
        val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
        val file = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena"))
        return file.nodes.flatMap { g -> g.positions.map { Node(g.block, Vec3(origin.x + it.x + 0.5, it.y, origin.z + it.z + 0.5), g.minNormaLevel) } }
    }

    private fun flat(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.z - b.z)

    private fun ScenarioScope.levelUp(matchId: MatchId, who: SimEntity) {
        val stars = sim.services.getOrThrow<IMatchStarStore>()
        stars.add(matchId, who.id, 25)
        sim.eventBus.publish(StarsCollected(matchId, who.id, 25, stars.get(matchId, who.id)))
        advance(2)
        normaCheckAtHome(matchId, who)
    }

    private fun ScenarioScope.bars(who: SimEntity): List<String> =
        sim.hud.changes(who.id, HudRegion.ACTION_BAR)
            .mapNotNull { (it.layout as? HudLayout.ActionBar)?.component }
            .map { SimHudRecorder.describe(it) }

    private fun ScenarioScope.compassAt(who: SimEntity): Vec3 {
        sim.matchCompass.select(who.id, true)
        advance(25)
        return checkNotNull(sim.hologramGuide.guideOf(who.id)).target
    }

    @Test
    fun `a level 1 player in phase 1 is pointed at the nearest base ore node`() {
        scenario("annihilation-nav-mine-p1", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)
            advance(30)

            val target = compassAt(red)
            val open = nodes().filter { it.minLevel == 1 }
            val expected = open.minBy { flat(red.position, it.pos) }
            assertThat(flat(target, expected.pos)).`as`("compass -> nearest iron-tier node ${expected.pos}, got $target").isLessThan(0.01)
            val line = bars(red).last { it.startsWith("astera.objective.hint.annihilation.mine{") }
            assertThat(line).contains("ore=iron").contains("area=base")
            assertThat(matchId).isNotNull()
        }
    }

    @Test
    fun `a level 2 player right after phase 2 opens is pointed at the nearest gold node of the Mid`() {
        scenario("annihilation-nav-mine-p2", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)
            advance(10)
            levelUp(matchId, red)
            assertThat(advanceUntil(600) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            advance(5)

            val target = compassAt(red)
            val mid = nodes().filter { it.block in setOf("astera:ore_gold_node", "astera:ore_heartwood_node", "astera:ore_silt_node") }
            val expected = mid.minBy { flat(red.position, it.pos) }
            assertThat(flat(target, expected.pos)).`as`("compass -> nearest gold-tier Mid node ${expected.pos}, got $target").isLessThan(0.01)
            val line = bars(red).last { it.startsWith("astera.objective.hint.annihilation.mid-opened{") }
            assertThat(line).contains("kind=diamond / gold")
        }
    }

    @Test
    fun `right after phase 3 starts the objective is Mid opened with the emerald and the compass at the Mid`() {
        scenario("annihilation-nav-mid-p3", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)
            assertThat(advanceUntil(1_200) { clock.current(matchId)?.phase == NexusPhase.Phase3Center }).isTrue()
            advance(5)

            val target = compassAt(red)
            val gated = nodes().filter { it.block.let { b -> b != "astera:ore_iron_node" && b != "astera:ore_timber_node" && b != "astera:ore_clay_node" } }
            val centre = Vec3(gated.map { it.pos.x }.average(), 0.0, gated.map { it.pos.z }.average())
            assertThat(flat(target, centre)).`as`("a level 1 player is sent to the Mid centre $centre, got $target").isLessThan(0.01)
            val line = bars(red).last { it.startsWith("astera.objective.hint.annihilation.mid-opened{") }
            assertThat(line).contains("kind=emerald")

            // The window is short: well after it the player (level 1, enemy pillars open) is back to mining.
            advance(500)
            assertThat(bars(red).last()).startsWith("astera.objective.hint.annihilation.mine{")
        }
    }

    @Test
    fun `an attacked own pillar overrides the ore objective and the compass turns to it`() {
        scenario("annihilation-nav-defend", config.copy(buildPhaseTicks = 100, engagePhaseTicks = 2_000)) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val clock = sim.services.getOrThrow<IMatchPhaseClock>()
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)
            assertThat(advanceUntil(300) { clock.current(matchId)?.phase == NexusPhase.Phase2Engage }).isTrue()
            advance(450)
            assertThat(bars(red).last()).`as`("window over, level 1: back to the ore").startsWith("astera.objective.hint.annihilation.mine{")

            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val pillar = stage.pillars.first { it.team.value == "red" && it.tier == NexusPillarTier.TIER_1 }
            val id = com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets.idOf("${matchId.value}-red-1")
            val from = Vec3(pillar.centre.x, pillar.position.y, pillar.centre.z + 5.0)
            sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IStructureTargets>().damage(
                id, blue.id, 100.0,
                com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageSource(
                    com.astarworks.astera.core.application.port.outbound.IStructureTargets.DamageKind.TOOL_SKILL, pillar.affinity, from,
                ),
            )
            val target = compassAt(red)
            assertThat(bars(red).last()).startsWith("astera.objective.hint.annihilation.defend")
            assertThat(flat(target, pillar.centre)).`as`("compass -> own pillar").isLessThan(0.01)
        }
    }
}
