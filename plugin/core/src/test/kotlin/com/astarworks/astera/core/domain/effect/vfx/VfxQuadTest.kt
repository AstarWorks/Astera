package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.application.config.VfxYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class VfxQuadTest {

    private val anchor = Frame(Vec3.ZERO)

    private fun def(vararg elements: VfxElement, duration: Int = 40, sample: Int = 2) =
        VfxDefinition(VfxId("q"), duration, VfxOrient.WORLD, false, sample, elements.toList())

    private fun emitter(
        emit: VfxEmitter,
        kind: VfxElementKind.Quad = VfxElementKind.Quad(0xFFAA00),
        keyframes: List<VfxKeyframe> = emptyList(),
    ) = VfxElement(id = "e", kind = kind, billboard = DisplayBillboard.CENTER, keyframes = keyframes, emit = emit)

    private fun plan(d: VfxDefinition, seed: Long = 7L, scale: Double = 1.0) = VfxTimeline.plan(d, anchor, seed, scale)

    // ---- calibration -----------------------------------------------------------

    @Test
    fun `a solid quad of one block scales the single-space text box by 8 x 4 and centres it`() {
        val t = VfxQuad.transform(VfxElementKind.Quad(), Quaternion.IDENTITY, Vec3(1.0, 1.0, 1.0))
        assertThat(t.scale.x).isCloseTo(8.0, within(1e-9))
        assertThat(t.scale.y).isCloseTo(4.0, within(1e-9))
        // box centre sits 0.1 * scale.y below the origin, so the translation lifts it back
        assertThat(t.translation.y).isCloseTo(0.4, within(1e-9))
        assertThat(t.translation.x).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `glyph quads use a 5x scale and keep the same centring rule`() {
        val t = VfxQuad.transform(VfxElementKind.Quad(glyph = "✦"), Quaternion.IDENTITY, Vec3(0.2, 0.2, 1.0))
        assertThat(t.scale.x).isCloseTo(1.0, within(1e-9))
        assertThat(t.translation.y).isCloseTo(0.1, within(1e-9))
    }

    @Test
    fun `style maps alpha to the background byte for solids and clamps glyph text opacity`() {
        val solid = VfxQuad.style(VfxElementKind.Quad(), 0x112233, 0.5)
        assertThat(solid.backgroundArgb).isEqualTo((128 shl 24) or 0x112233)
        assertThat(solid.textRgb).isNull()
        val glyph = VfxQuad.style(VfxElementKind.Quad(glyph = "●"), 0x112233, 0.0)
        assertThat(glyph.textOpacity).isEqualTo(VfxQuad.MIN_TEXT_OPACITY)
        assertThat(glyph.backgroundArgb).isZero()
        assertThat(glyph.textRgb).isEqualTo(0x112233)
    }

    // ---- keyframe colour / alpha -----------------------------------------------

    @Test
    fun `keyframe colour and alpha interpolate with the segment ease and ride the updates`() {
        val el = VfxElement(
            id = "q",
            kind = VfxElementKind.Quad(0xFF0000, 1.0),
            billboard = DisplayBillboard.CENTER,
            keyframes = listOf(
                VfxKeyframe(0, translate = Vec3(0.0, 1.0, 0.0)),
                VfxKeyframe(10, color = 0x0000FF, alpha = 0.0, ease = Easing.LINEAR),
            ),
        )
        val p = plan(def(el)).elements.single()
        assertThat((p.kind as VfxElementKind.Quad).colorRgb).isEqualTo(0xFF0000)
        assertThat(p.updates).isNotEmpty()
        assertThat(p.updates).allSatisfy { assertThat(it.style).isNotNull() }
        val alphas = p.updates.map { (it.style!!.backgroundArgb ushr 24) }
        assertThat(alphas).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(alphas.first()).isGreaterThan(alphas.last())
        assertThat(p.updates.all { it.position != null }).isTrue()
    }

    // ---- emitter ---------------------------------------------------------------

    @Test
    fun `emitter plans are deterministic per seed and differ across seeds`() {
        val e = emitter(VfxEmitter(count = 10, speedMin = 0.1, speedMax = 0.3, spreadDeg = 40.0, lifeMin = 10, lifeMax = 20, gravity = 0.02))
        assertThat(plan(def(e), seed = 3)).isEqualTo(plan(def(e), seed = 3))
        assertThat(plan(def(e), seed = 3)).isNotEqualTo(plan(def(e), seed = 4))
    }

    @Test
    fun `an emitter burst plans one single-quad slot per particle within life bounds and the cap`() {
        val e = emitter(VfxEmitter(count = 12, speedMin = 0.1, speedMax = 0.2, lifeMin = 8, lifeMax = 14))
        val plan = plan(def(e))
        assertThat(plan.elements).hasSize(12)
        assertThat(plan.displayCount).isEqualTo(12)
        assertThat(plan.elements.map { it.slot }).containsExactlyInAnyOrderElementsOf(0 until 12)
        assertThat(plan.elements).allSatisfy {
            assertThat(it.despawnTick - it.spawnTick).isBetween(8, 14)
            assertThat(it.spawnTick).isZero()
        }
        assertThatThrownBy { VfxEmitter(count = VfxEmitter.MAX_COUNT + 1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rate per tick staggers spawns`() {
        val e = emitter(VfxEmitter(count = 6, ratePerTick = 2, lifeMin = 5, lifeMax = 5))
        assertThat(plan(def(e)).elements.map { it.spawnTick }.sorted()).containsExactly(0, 0, 1, 1, 2, 2)
    }

    @Test
    fun `gravity pulls the trajectory down in world axes and drag shortens it`() {
        val path = VfxEmitterPlanner.trajectory(Vec3.ZERO, Vec3(0.0, 0.5, 0.0), gravity = 0.1, drag = 0.0, ticks = 10)
        assertThat(path).hasSize(11)
        // v_n = 0.5 - 0.1 n ; apex after 5 ticks, back below the start after 10
        assertThat(path[5].y).isGreaterThan(path[1].y)
        assertThat(path[10].y).isLessThan(path[5].y)
        val dragged = VfxEmitterPlanner.trajectory(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), gravity = 0.0, drag = 0.5, ticks = 20)
        assertThat(dragged.last().x).isCloseTo(1.0, within(1e-3)) // sum of 0.5^k -> 1
    }

    @Test
    fun `emitter particles move through entity positions and carry gradient styles`() {
        val e = emitter(
            VfxEmitter(
                count = 4, speedMin = 0.2, speedMax = 0.2, direction = VfxEmitDirection.Up, gravity = 0.02,
                lifeMin = 12, lifeMax = 12, colorOverLife = listOf(0xFFD24A, 0xD02010), alphaOverLife = listOf(1.0, 0.0),
            ),
            keyframes = listOf(VfxKeyframe(0, translate = Vec3(0.0, 1.0, 2.0))),
        )
        val p = plan(def(e)).elements.first()
        assertThat(p.spawnOffset).isEqualTo(Vec3(0.0, 1.0, 2.0))
        assertThat(p.updates).isNotEmpty()
        assertThat(p.updates.map { it.tick }).isSorted()
        assertThat(p.updates.first().tick).isEqualTo(p.spawnTick + VfxTimeline.ANIMATION_DELAY_TICKS)
        assertThat(p.updates.last().position!!.y).isGreaterThan(1.0)
        val alphas = p.updates.map { it.style!!.backgroundArgb ushr 24 }
        assertThat(alphas.first()).isGreaterThan(alphas.last())
        // initial transform holds no world position, only the centring shift
        assertThat(p.initial.translation.x).isCloseTo(0.0, within(1e-9))
    }

    // ---- config ----------------------------------------------------------------

    @Test
    fun `yaml quad with emit materializes and defaults the billboard to center`() {
        val cfg = VfxYamlConfig(
            id = "y", durationTicks = 20,
            elements = listOf(
                com.astarworks.astera.core.application.config.VfxElementYaml(
                    id = "e",
                    kind = com.astarworks.astera.core.application.config.VfxKindYaml.Quad(color = "#ff8800", alpha = 0.8),
                    emit = com.astarworks.astera.core.application.config.VfxEmitYaml(count = 5, direction = "custom[0, 1, 0.5]", speed = listOf(0.1, 0.2)),
                ),
            ),
        )
        val d = (cfg.toDefinition() as Result.Success).value
        val el = d.elements.single()
        assertThat(el.billboard).isEqualTo(DisplayBillboard.CENTER)
        assertThat(el.emit!!.direction).isEqualTo(VfxEmitDirection.Custom(Vec3(0.0, 1.0, 0.5)))
        assertThat((el.kind as VfxElementKind.Quad).colorRgb).isEqualTo(0xFF8800)
    }
}
