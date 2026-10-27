package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerLabelGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression: the opposing team's names and positions must not leak through the vanilla tab list
 * or nameplates seen through walls. ADR-0206 used to label every participant for every viewer,
 * so enemies stayed listed and named. Now enemies are concealed, teammates stay labelled.
 */
class EnemyConcealedFromTabScenarioTest {

    @Test
    fun `opposing team is concealed from each viewer while teammates stay labelled`() {
        scenario("enemy-concealed") {
            val r1 = player("r1", team = "red", at = Vec3(0.0, groundY, 0.0))
            val r2 = player("r2", team = "red", at = Vec3(2.0, groundY, 0.0))
            val b1 = player("b1", team = "blue", at = Vec3(0.0, groundY, 10.0))
            val b2 = player("b2", team = "blue", at = Vec3(2.0, groundY, 10.0))
            startMatch("carry-tnt", mapOf(r1 to "red", r2 to "red", b1 to "blue", b2 to "blue"))
            advance(40)

            val labels = sim.services.getOrThrow<IPlayerLabelGateway>() as InMemoryPlayerLabelGateway
            val red = setOf(r1.id, r2.id)
            val blue = setOf(b1.id, b2.id)
            for (viewer in red) {
                val seen = labels.labelsOf(viewer)
                for (id in red) assertThat(seen.getValue(id).concealed).`as`("teammate $id").isFalse()
                for (id in blue) assertThat(seen.getValue(id).concealed).`as`("enemy $id").isTrue()
            }
            for (viewer in blue) {
                val seen = labels.labelsOf(viewer)
                for (id in blue) assertThat(seen.getValue(id).concealed).`as`("teammate $id").isFalse()
                for (id in red) assertThat(seen.getValue(id).concealed).`as`("enemy $id").isTrue()
            }
        }
    }
}
