package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Parity with Paper: every generator and star cache authored in `content/generators/` for the stage (same [IGeneratorRegistry] the
 * match wiring reads) is a live generator of the sim match at its content position and with all its content spots (cache spots included),
 * origin-shifted exactly as `MatchStarLifecycle.onStarted` does. Nothing in the expectation is hard-coded.
 */
class GeneratorParityScenarioTest {

    @Test
    fun `every content generator and star cache of the stage is live in the sim match at its content position and spots`() {
        MatchDriver.boot(MatchConfig(stage = "annihilation-arena", seed = 21)).use { driver ->
            driver.step(20)
            val stage = checkNotNull(driver.sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val origin = stage.origin
            val source = (stage.templateId ?: stage.id).value
            val authored = driver.sim.services.getOrThrow<IGeneratorRegistry>().all().filter { it.matchStageId == source }
            assertThat(authored).`as`("content generators of $source").isNotEmpty()
            assertThat(authored.any { it.cache != null }).`as`("star caches are among them").isTrue()
            assertThat(authored.any { it.cache == null }).`as`("timer generators are among them").isTrue()

            val live = driver.sim.services.getOrThrow<IGeneratorRepository>().listForMatch(driver.matchId).associateBy { it.id.value }
            val problems = ArrayList<String>()
            for (c in authored) {
                val g = live[c.id]
                if (g == null) { problems += "${c.id}: not instantiated in the sim match"; continue }
                if (g.position.x != c.position.x + origin.x || g.position.y != c.position.y || g.position.z != c.position.z + origin.z) {
                    problems += "${c.id}: position ${g.position} != content ${c.position}"
                }
                val expectedSpots = c.spots.map { Triple(it.x + origin.x, it.y, it.z + origin.z) }
                val gotSpots = g.spots.map { Triple(it.x, it.y, it.z) }
                if (gotSpots != expectedSpots) problems += "${c.id}: spots ${gotSpots.size} != content ${expectedSpots.size}"
                if ((g.cache != null) != (c.cache != null)) problems += "${c.id}: cache mode differs from content"
            }
            assertThat(problems).`as`("content generators / caches missing or different in the sim").isEmpty()
        }
    }
}
