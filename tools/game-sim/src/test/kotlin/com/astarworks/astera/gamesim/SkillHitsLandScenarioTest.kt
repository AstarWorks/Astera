package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 dev report "nobody dies, pillars take no damage": in an active Annihilation match a real weapon skill
 * (knight-sword left click -> sword-slash through the skill engine) lands on an enemy player until the kill, lands on
 * an enemy pillar once its tier is open, and a second hit inside the Astera 20-tick i-frame is refused (and only that).
 */
class SkillHitsLandScenarioTest {

    private val config = AnnihilationConfig(buildPhaseTicks = 200, engagePhaseTicks = 100_000, tier3PhaseTicks = 100_000)

    @Test
    fun `sword skill kills an enemy player and drops an enemy pillar, an i-frame refuses only the second hit`() {
        scenario("skill-hits-land", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", at = Vec3(0.0, groundY, 2.2), team = "blue")
            val yellow = player("yellow-1", at = Vec3(0.0, groundY, 1.0), team = "yellow")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            lifecycle.entry(matchId, yellow.id, TeamId("yellow"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            // Build ends -> Tier 1 opens; spawn protection is long over.
            advance(260)
            yellow.position = Vec3(500.0, groundY, 500.0)
            red.position = Vec3(0.0, groundY, 0.0)
            blue.position = Vec3(0.0, groundY, 2.2)

            // --- player kill ---------------------------------------------------------------------------------
            red.holds("knight-sword")
            red.faces(blue)
            red.leftClick()
            advance(1)
            val afterFirst = hp(blue)
            assertThat(afterFirst).`as`("first sword-slash lands").isLessThan(100.0)
            fun hitsOnBlue() = eventsOf<DamageDealt>().count { it.target == blue.id }
            assertThat(hitsOnBlue()).isEqualTo(1)

            // Inside the 20-tick window (sword cooldown is 10): the cast fires, the hit is refused, HP stays.
            advance(10)
            red.faces(blue)
            red.leftClick()
            advance(1)
            assertThat(hp(blue)).`as`("second hit inside the i-frame is refused").isEqualTo(afterFirst)
            assertThat(hitsOnBlue()).isEqualTo(1)

            // Once the window is over hits land again until blue dies.
            for (i in 0 until 20 * 12) {
                if (eventsOf<PlayerKilled>().isNotEmpty()) break
                red.faces(blue)
                red.leftClick()
                advance(1)
            }
            assertThat(eventsOf<PlayerKilled>().map { it.victim to it.killer }).contains(blue.id to red.id)
            assertThat(hitsOnBlue()).isGreaterThan(1)

            // --- pillar HP -----------------------------------------------------------------------------------
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val pillar = stage.pillars.first { it.team.value == "blue" && it.tier == NexusPillarTier.TIER_1 }
            val targets = sim.services.getOrThrow<IStructureTargets>()
            val id = AnnihilationPillarTargets.idOf("${matchId.value}-blue-${pillar.tier.ordinalValue}")
            val before = checkNotNull(targets.hitPoints(id))
            red.position = Vec3(pillar.centre.x, pillar.position.y, pillar.centre.z + 2.2)
            red.lookAt(pillar.centre)
            advance(40)
            red.leftClick()
            advance(2)
            assertThat(checkNotNull(targets.hitPoints(id))).`as`("a weapon skill hit takes pillar HP").isLessThan(before)
        }
    }
}
