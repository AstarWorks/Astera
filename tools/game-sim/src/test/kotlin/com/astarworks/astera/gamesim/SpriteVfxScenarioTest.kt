package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.vfx.SpriteGlyphs
import com.astarworks.astera.core.domain.effect.vfx.SpriteId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0239: custom particle sprites end to end -- shipped `content/sprites` metadata, the `vfx-demo-sprites`
 * skill and `demo-sprites` choreography, real wiring, sim display gateway. A sprite is a text display whose
 * glyph is swapped frame by frame; a swap landing inside an interpolation step keeps the motion going.
 */
class SpriteVfxScenarioTest {

    private fun frame(id: String, k: Int) = SpriteGlyphs.text(SpriteId(id), k)

    /** The rising flame is tinted, which tells it apart from the untinted flame in the row. */
    private fun risingFrame(k: Int) = SpriteGlyphs.text(SpriteId("flame"), k, RISING_TINT)

    @Test
    fun `the sprite demo shows every sprite as a glyph text display and flips its frames every frame_ticks`() {
        scenario("vfx-demo-sprites") {
            val caster = player("caster", team = "red")
            val start = sim.scheduler.currentTick()

            caster.cast("vfx-demo-sprites")

            // 13 sprites side by side + the rising flame, all text displays showing frame 0 of their own font
            assertThat(sim.displays.spawned).hasSize(14)
            assertThat(sim.displays.spawned.map { it.spec.kind }).allMatch { it is DisplayKind.Text }
            assertThat(sim.displays.spawned.map { it.lines.single() }).allMatch { it.contains("<font:astera:vfx/") }
            val spark = sim.displays.spawned.single { it.lines.single() == frame("spark", 0) }

            advance(14)
            // spark: 6 frames, frame_ticks 2, looping; element tick u lands at spawn + 1 + u
            val sparkSwaps = sim.displays.swaps.filter { it.handle == spark.handle }
            assertThat(sparkSwaps).allMatch { it.kind == "text" }
            assertThat(sparkSwaps.map { it.tick - start to it.value }).containsExactly(
                3L to frame("spark", 1),
                5L to frame("spark", 2),
                7L to frame("spark", 3),
                9L to frame("spark", 4),
                11L to frame("spark", 5),
                13L to frame("spark", 0),
            )

            advance(100)
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.recorder.effects("display_spawn")).hasSize(14)
            assertThat(sim.recorder.effects("display_remove")).hasSize(14)
        }
    }

    @Test
    fun `a flipbook swap inside an interpolation step keeps the display moving`() {
        scenario("vfx-demo-sprites-rising") {
            val caster = player("caster", team = "red")
            val start = sim.scheduler.currentTick()

            caster.cast("vfx-demo-sprites")
            val rising = sim.displays.spawned.single { it.lines.single() == risingFrame(0) }

            advance(30)
            val swaps = sim.displays.swaps.filter { it.handle == rising.handle && it.tick - start <= 24 }
            val updates = sim.displays.updates.filter { it.handle == rising.handle && it.tick - start <= 24 }
            // frame_ticks 3: u = 3, 6, ..., 21 -> ticks 4, 7, ..., 22
            assertThat(swaps.map { it.tick - start }).containsExactly(4L, 7L, 10L, 13L, 16L, 19L, 22L)
            assertThat(swaps.map { it.value }).containsExactly(
                risingFrame(1), risingFrame(2), risingFrame(3), risingFrame(4),
                risingFrame(5), risingFrame(0), risingFrame(1),
            )
            // every swap tick also carries a transform update that still interpolates (none freezes the rise)
            for (swap in swaps) {
                val u = updates.filter { it.tick == swap.tick }
                assertThat(u).`as`("update at swap tick ${swap.tick - start}").hasSize(1)
                assertThat(u.single().interpolationTicks).`as`("interpolation at swap tick ${swap.tick - start}").isGreaterThan(0)
            }
            // u = 3 falls inside the 2..4 sample step: the remaining tick, aimed at the step end
            assertThat(updates.single { it.tick - start == 4L }.interpolationTicks).isEqualTo(1)
            // while rising, no update stops the motion
            assertThat(updates.filter { it.tick - start < 25 }).allMatch { it.interpolationTicks > 0 }
        }
    }

    private companion object {
        const val RISING_TINT: Int = 0xFFB060
    }
}
