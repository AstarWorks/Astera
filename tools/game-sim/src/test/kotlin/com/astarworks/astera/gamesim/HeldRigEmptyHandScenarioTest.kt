package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The held rig mesh comes from the item captured at cast start (the triggering item, else the skill's bound item), never from the live main hand.
 * Regression: an empty-handed forced bore (admin / bot cast) aborted at windup.do[0] with "mesh main_hand (empty main hand)".
 */
class HeldRigEmptyHandScenarioTest {
    @Test
    fun `held rig spawns with the cast item although the main hand is emptied before the windup`() {
        scenario("held-rig-empty-hand", AnnihilationConfig(buildPhaseTicks = 200, engagePhaseTicks = 100_000, tier3PhaseTicks = 100_000)) {
            val red = player("red-1", team = "red")
            red.position = Vec3(0.0, groundY, 0.0)
            red.holds("stone-pickaxe")
            sim.cast(red, "pickaxe-bore-stone")
            red.heldItem = null
            advance(30)
            assertThat(sim.skillEngine.problems).describedAs("the rig is not aborted by an empty hand").isEmpty()
            assertThat(sim.skillEngine.visuals.heldMeshItems).containsExactly("stone-pickaxe")
        }
    }
}
