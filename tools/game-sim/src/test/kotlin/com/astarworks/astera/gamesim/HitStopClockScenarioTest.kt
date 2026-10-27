package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxElement
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxKeyframe
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Element-level `respect_hit_stop`: the keyframe clock stops for the freeze window, a plain element keeps running. */
class HitStopClockScenarioTest {

    private fun element(id: String, respect: Boolean) = VfxElement(
        id = id,
        kind = VfxElementKind.Block("minecraft:stone"),
        keyframes = listOf(
            VfxKeyframe(0, translate = Vec3.ZERO),
            VfxKeyframe(10, translate = Vec3(10.0, 0.0, 0.0)),
        ),
        respectHitStop = respect,
    )

    @Test
    fun `a respecting element pauses for the window while the others keep running`() {
        val def = VfxDefinition(
            id = VfxId("hit-stop-clock"),
            durationTicks = 30,
            sampleTicks = 2,
            elements = listOf(element("live", false), element("held", true)),
        )
        val plan = VfxTimeline.plan(def, Frame(Vec3.ZERO), seed = 1L, freezes = listOf(VfxFreeze(5, 4)))
        val live = plan.elements.single { it.elementId == "live" }.updates
        val held = plan.elements.single { it.elementId == "held" }.updates

        assertThat(live.map { it.tick }).containsExactly(1, 3, 5, 7, 9)
        // Steps 1 and 3 run, the clock stops at tick 5 for four ticks, the rest resumes at 9.
        assertThat(held.map { it.tick }).containsExactly(1, 3, 9, 11, 13)
        assertThat(held.none { it.tick in 5..8 }).isTrue()
        // Same motion, only delayed: the final pose matches.
        assertThat(held.last().transform.translation.x).isEqualTo(live.last().transform.translation.x)
        assertThat(held.last().tick - live.last().tick).isEqualTo(4)
    }
}
