package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.stage.StagePillar
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WP10: Annihilation pillars take damage from skills. Three teams, one player each.
 * Red burns blue's pillars (Build immunity, tier opening, the Tier 1 + 2 cascade into
 * Phase 3, elimination), cannot hurt its own, then takes yellow's too and wins.
 */
class AnnihilationPillarScenarioTest {

    private val config = AnnihilationConfig(
        pillarHp = 40,
        buildPhaseTicks = 200,
        engagePhaseTicks = 100_000,
        tier3PhaseTicks = 100_000,
    )

    @Test
    fun `red destroys blue then yellow pillars with skills and wins`() {
        scenario("annihilation-pillar-siege", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")

            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            lifecycle.entry(matchId, yellow.id, TeamId("yellow"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(40)
            assertThat(eventsOf<MatchStarted>().map { it.matchId }).contains(matchId)

            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val targets = sim.services.getOrThrow<IStructureTargets>()
            val pillars = stage.pillars.associateBy { it.team.value to it.tier }
            val world = WorldId("world")

            fun pillar(team: String, tier: NexusPillarTier): StagePillar = checkNotNull(pillars[team to tier])
            fun id(team: String, tier: NexusPillarTier) =
                AnnihilationPillarTargets.idOf("${matchId.value}-$team-${tier.ordinalValue}")
            fun hp(team: String, tier: NexusPillarTier): Double = checkNotNull(targets.hitPoints(id(team, tier)))
            fun destroyed() = eventsOf<NexusDestroyed>()

            // The arena's pillar blocks stand in the world from the match start.
            pillars.values.forEach { assertThat(sim.world.blockAt(world, it.position)).isEqualTo(it.block) }
            // Tier 3 carries its stage hp override, the other tiers the mode's pillar hp.
            assertThat(hp("blue", NexusPillarTier.TIER_1)).isEqualTo(40.0)
            assertThat(hp("blue", NexusPillarTier.TIER_3)).isEqualTo(800.0)

            // blue / yellow stay out of the line of fire.
            blue.position = Vec3(0.0, groundY, 0.0)
            yellow.position = Vec3(0.0, groundY, 1.0)

            /** Red stands 5 blocks from [p] and burns it with flame-burst until [done]. */
            fun siege(p: StagePillar, maxCasts: Int = 120, done: () -> Boolean) {
                red.position = Vec3(p.centre.x, groundY, p.centre.z + 5.0)
                red.lookAt(p.centre)
                var casts = 0
                while (!done() && casts++ < maxCasts) {
                    red.cast("flame-burst")
                    advance(85)
                }
                assertThat(done()).`as`("pillar at ${p.position} fell within $maxCasts casts").isTrue()
            }

            // --- Phase 1 (Build): pillars are immune --------------------------------
            red.position = Vec3(pillar("blue", NexusPillarTier.TIER_1).centre.x, groundY, pillar("blue", NexusPillarTier.TIER_1).centre.z + 5.0)
            red.holds("fire-staff")
            red.lookAt(pillar("blue", NexusPillarTier.TIER_1).centre)
            red.cast("flame-burst")
            advance(60)
            assertThat(hp("blue", NexusPillarTier.TIER_1)).`as`("Build phase is immune").isEqualTo(40.0)

            advance(200) // Build ends -> Phase 2 opens Tier 1

            // --- own pillar: a team cannot damage itself ----------------------------
            val ownT1 = pillar("red", NexusPillarTier.TIER_1)
            red.position = Vec3(ownT1.centre.x, groundY, ownT1.centre.z - 5.0)
            red.lookAt(ownT1.centre)
            repeat(3) { red.cast("flame-burst"); advance(85) }
            assertThat(hp("red", NexusPillarTier.TIER_1)).`as`("ally damage is ignored").isEqualTo(40.0)
            assertThat(destroyed()).isEmpty()

            // --- blue falls: Tier 1 -> its Tier 2 opens -> cascade -> Phase 3 -> Tier 3 -
            val blueT1 = pillar("blue", NexusPillarTier.TIER_1)
            siege(blueT1) { destroyed().any { it.owningTeam.value == "blue" && it.tier == NexusPillarTier.TIER_1 } }
            assertThat(sim.world.blockAt(world, blueT1.position)).`as`("destroyed pillar block is gone").isNull()
            assertThat(destroyed().first().attackerId).isEqualTo(red.id)

            val blueT2 = pillar("blue", NexusPillarTier.TIER_2)
            siege(blueT2) { destroyed().any { it.owningTeam.value == "blue" && it.tier == NexusPillarTier.TIER_2 } }
            assertThat(eventsOf<TeamEliminated>()).isEmpty()
            advance(40) // the mode tick notices the lower-tier cascade -> Phase 3 -> Tier 3 opens

            val blueT3 = pillar("blue", NexusPillarTier.TIER_3)
            siege(blueT3, maxCasts = 200) { eventsOf<TeamEliminated>().any { it.teamId.value == "blue" } }
            assertThat(eventsOf<MatchEnded>()).`as`("two teams still stand").isEmpty()

            // --- yellow falls too: red is the last team with pillars ---------------
            for (tier in NexusPillarTier.ordered()) {
                val p = pillar("yellow", tier)
                siege(p, maxCasts = 200) {
                    destroyed().any { it.owningTeam.value == "yellow" && it.tier == tier }
                }
                advance(40)
            }

            assertThat(eventsOf<TeamEliminated>().map { it.teamId.value }).containsExactly("blue", "yellow")
            val ended = eventsOf<MatchEnded>().single()
            assertThat(ended.matchId).isEqualTo(matchId)
            assertThat(ended.winningTeam).isEqualTo(TeamId("red"))
            assertThat(ended.reason).isEqualTo(EndReason.TeamVictory(TeamId("red")))
            // Red's own pillars were never touched.
            assertThat(destroyed().none { it.owningTeam.value == "red" }).isTrue()
        }
    }

    @Test
    fun `a solo annihilation playtest does not end by itself`() {
        scenario("annihilation-solo-playtest", config) {
            val solo = player("solo", team = "red")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId: MatchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, solo.id, TeamId("red"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(600)
            assertThat(eventsOf<MatchStarted>()).isNotEmpty()
            assertThat(eventsOf<MatchEnded>()).`as`("empty enemy teams do not end the match").isEmpty()
        }
    }
}
