package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A steal-jewelry match that ends by banking the fifth jewel still shows both players the result screen and verdict. */
class JewelryResultScenarioTest {

    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.spotOf(region: String): Vec3 {
        val r = checkNotNull(checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles-jewelry"))).region(region)) { "no region $region" }
        return Vec3((r.min.x + r.max.x) / 2.0, (r.min.y + 1.0).coerceAtMost(r.max.y), (r.min.z + r.max.z) / 2.0)
    }

    private fun ScenarioScope.chat(who: SimEntity): String = checkNotNull(sim.server.view(who.id)).messages.joinToString("\n")

    @Test
    fun `fifth banked jewel ends the match and both players get the summary and verdict`() {
        scenario("jewelry-result") {
            val red = player("red-1", at = nowhere)
            val blue = player("blue-1", at = nowhere)
            startMatch("steal-jewelry", mapOf(red to "red", blue to "blue"), stage = "siege-isles-jewelry")
            advance(40)
            repeat(5) {
                red.moveTo(spotOf("jewelry-blue")); advance(110)
                red.moveTo(nowhere); advance(5)
            }
            red.moveTo(spotOf("base-red")); advance(40)
            advance(10)
            assertThat(eventsOf<MatchEnded>()).hasSize(1)
            assertThat(eventsOf<MatchSummaryReady>()).`as`("MatchSummaryReady").hasSize(1)
            for (p in listOf(red, blue)) {
                val text = chat(p)
                assertThat(text).`as`("chat of ${p.id}").contains("Match Summary").contains("Score").contains("You:")
            }
        }
    }
}
