package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A cast in its detach tail still resolves anchors (sword-slash's blade trail samples `/windup/rig.root` .. `rig.tip` for 7 ticks, the rig and the sweep fx
 * are detached): the live-value request of a run in its tail must survive until the run finalizes. Regression: `retain` once used the live casts only, the
 * tail threw "cast N is not registered with the live-value source", the cast was aborted and the whole detached swing was cut.
 */
class DetachTailLiveRequestScenarioTest {
    @Test
    fun `sword-slash tail rows run to the end without a fault`() {
        scenario("detach-tail-live-request", AnnihilationConfig(buildPhaseTicks = 200, engagePhaseTicks = 100_000, tier3PhaseTicks = 100_000)) {
            val red = player("red-1", team = "red")
            red.position = Vec3(0.0, groundY, 0.0)
            red.holds("knight-sword")
            red.leftClick()
            // wind-up 3 + strike 2 = cast end at tick 5, tail 9: the detached rows run until tick 14
            advance(24)
            assertThat(sim.skillEngine.problems).describedAs("no cast aborted, no table fault (detached tail included)").isEmpty()
        }
    }
}
