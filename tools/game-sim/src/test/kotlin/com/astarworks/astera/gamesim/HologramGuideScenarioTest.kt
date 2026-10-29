package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-16: the hologram guide (ADR-0295) of carry-tnt points each player at their current objective, switches when the
 * objective state changes (loose TNT -> enemy base for the carrier, -> the carrier for the enemy) and is removed for
 * everyone when the match ends.
 */
class HologramGuideScenarioTest {

    // Stage `siege-isles-tnt`: the TNT pedestal in the middle, the red base at x=-43, the blue base at x=+43.
    private val tntSpawn = Vec3(0.0, 65.0, -6.0)
    private val blueBase = Vec3(43.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    @Test
    fun `carry tnt - the guide follows the objective state and is gone at match end`() {
        scenario("hologram-guide-carry-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            val guides = sim.hologramGuide

            // The guide shows only while the navigation compass is held (every mode gets one, not only Annihilation).
            advance(45)
            assertThat(sim.matchCompass.slotOf(runner.id)).`as`("carry-tnt hands out the compass too").isEqualTo(8)
            assertThat(guides.guideOf(runner.id)).`as`("compass not held: no guide").isNull()
            sim.matchCompass.select(runner.id, true)
            sim.matchCompass.select(guard.id, true)
            advance(25)

            // Loose TNT: both teams are pointed at the pedestal.
            val toTnt = guides.guideOf(runner.id)
            assertThat(toTnt).`as`("runner has a guide while the TNT lies loose").isNotNull()
            assertThat(toTnt!!.target.x).`as`("pointed at the pedestal, not a base").isBetween(-15.0, 15.0)
            assertThat(guides.guideOf(guard.id)?.target?.x).isBetween(-15.0, 15.0)
            assertThat(toTnt.label).isNotBlank()

            // Runner picks the TNT up: the guide now points at the blue base, the guard's at the carrier.
            runner.moveTo(tntSpawn)
            // The shield wears down with the runner standing there; once it is gone a right-click takes the TNT.
            advance(20 * 30)
            clickNearestMarker(runner, matchId)
            advance(60)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("runner carries the TNT").isNotNull()
            val toBase = guides.guideOf(runner.id)
            assertThat(toBase!!.target.x).`as`("carrier is sent to the enemy base").isGreaterThan(20.0)
            assertThat(toBase.label).`as`("the label changed with the objective").isNotEqualTo(toTnt.label)
            val onCarrier = guides.guideOf(guard.id)
            assertThat(onCarrier!!.target.x).`as`("the enemy is pointed at the carrier").isBetween(-15.0, 15.0)
            assertThat(onCarrier.label).isNotEqualTo(toTnt.label)
            runner.moveTo(Vec3(blueBase.x - 20.0, 65.0, 0.0))
            advance(25)
            assertThat(guides.guideOf(guard.id)!!.target.x).`as`("the enemy's guide follows the carrier").isGreaterThan(15.0)

            // Match over: no guide entity is left for anyone.
            assertThat(guides.liveCount()).isEqualTo(2)
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.Cancelled("hologram-guide-test"))
            advance(40)
            assertThat(guides.guideOf(runner.id)).isNull()
            assertThat(guides.guideOf(guard.id)).isNull()
            assertThat(guides.liveCount()).`as`("no guide leaks past the match").isZero()
        }
    }
}
