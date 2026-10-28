package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Dash strikes raise the cast event `dash_hit` (source = the dash op id), which presentation handlers hang the hit sound on. */
class DashHitEventScenarioTest {
    @Test
    fun `a dash strike raises dash_hit so the presentation hit sound plays`() {
        val sim = CastSim(victims = mapOf(simVictim(1) to ahead(Vec3.ZERO, 0.0, 5.0)), blockAtZ = 8.0, floorY = 0.0)
        sim.cast("spear-charge", 40)
        assertThat(sim.sounds.map { it.second }).contains("astera:weapons/blade-hit")
    }
}
