package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class VfxRigPlannerTest {

    private val anchor = Frame(Vec3.ZERO)

    private val blade = VfxRigPart("blade", "iron_block", Vec3(0.0, 1.6, 0.0), Vec3(0.4, 2.6, 0.12))
    private val guard = VfxRigPart("guard", "gold_block", Vec3(0.0, 0.25, 0.0), Vec3(0.95, 0.14, 0.24))
    private val grip = VfxRigPart("grip", "black_concrete", Vec3(0.0, -0.25, 0.0), Vec3(0.1, 0.9, 0.1), rotateEuler = Vec3(0.0, 0.0, 30.0))

    private fun rig(
        swing: List<VfxSwingKey>,
        trails: List<VfxTrail> = emptyList(),
        boards: List<VfxBoard> = emptyList(),
        emitters: List<VfxRigEmitter> = emptyList(),
        hitStop: Boolean = false,
        start: Int = 0,
    ) = VfxRig(
        id = "sword",
        pivot = Vec3(-0.35, 1.2, 0.3),
        tiltYawPitchRoll = Vec3(0.0, 0.0, 20.0),
        restYawPitchRoll = Vec3(0.0, 90.0, 0.0),
        startTick = start,
        parts = listOf(blade, guard, grip),
        swing = swing,
        trails = trails,
        boards = boards,
        emitters = emitters,
        respectHitStop = hitStop,
    )

    private fun definition(rig: VfxRig, duration: Int = 40) =
        VfxDefinition(VfxId("rig-test"), duration, VfxOrient.WORLD, false, 2, emptyList(), listOf(rig))

    private fun plan(rig: VfxRig, freezes: List<VfxFreeze> = emptyList(), scale: Double = 1.0, duration: Int = 40) =
        VfxTimeline.plan(definition(rig, duration), anchor, 1L, scale, freezes)

    private fun slash(ease: Easing = Easing.LINEAR, end: Int = 6, to: Double = -190.0) = listOf(
        VfxSwingKey(0, Vec3(110.0, 0.0, 0.0)),
        VfxSwingKey(2, Vec3(110.0, 0.0, 0.0)),
        VfxSwingKey(end, Vec3(to, 0.0, 0.0), ease),
    )

    private fun trail(start: Int = 2, end: Int = 6, life: Int = 6, style: VfxTrailStyle = VfxTrailStyle.SOFT, step: Double = 30.0) = VfxTrail(
        id = "wind",
        from = Vec3(0.0, 0.9, 0.0),
        to = Vec3(0.0, 2.9, 0.0),
        startT = start,
        endT = end,
        lifeTicks = life,
        style = style,
        stepDeg = step,
        bands = listOf(
            VfxTrailBand(0.0, 0.5, 0x40FFD070, block = "yellow_stained_glass"),
            VfxTrailBand(0.5, 1.0, 0xC8FFFFFF.toInt(), 0x00E0A020, block = "white_stained_glass"),
        ),
        edge = VfxTrailEdge("white_concrete", 0.05),
        darkRim = VfxTrailRim(0xB01A0C00.toInt(), 0.2),
    )

    /** Centre of a block display's box in world space. */
    private fun centre(t: DisplayTransform): Vec3 = t.translation + t.rotation.rotate(t.scale * 0.5)

    private fun alphaOf(el: VfxElementPlan): Int = Math.round((el.kind as VfxElementKind.Quad).alpha * 255).toInt()

    private fun degrees(a: Quaternion, b: Quaternion) = Math.toDegrees(a.angleTo(b))

    // ---- rig rigidity ----------------------------------------------------------------

    @Test
    fun `parts keep a fixed distance to each other and to the pivot at every update`() {
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD)))
        val parts = p.elements.filter { it.elementId.startsWith("sword.") }
        assertThat(parts).hasSize(3)
        val pivot = Vec3(-0.35, 1.2, 0.3)
        val states = parts.associateWith { el -> listOf(el.initial) + el.updates.map { it.transform } }
        val steps = states.values.first().size
        assertThat(states.values.map { it.size }.toSet()).containsExactly(steps)
        assertThat(steps).isGreaterThan(3)
        val bladeEl = parts.first { it.elementId == "sword.blade" }
        val guardEl = parts.first { it.elementId == "sword.guard" }
        val gripEl = parts.first { it.elementId == "sword.grip" }
        for (i in 0 until steps) {
            val b = centre(states.getValue(bladeEl)[i])
            val g = centre(states.getValue(guardEl)[i])
            val r = centre(states.getValue(gripEl)[i])
            assertThat(b.distance(g)).isCloseTo(Vec3(0.0, 1.6, 0.0).distance(Vec3(0.0, 0.25, 0.0)), within(1e-9))
            assertThat(b.distance(r)).isCloseTo(1.6 + 0.25, within(1e-9))
            assertThat(b.distance(pivot)).isCloseTo(1.6, within(1e-9))
            assertThat(g.distance(pivot)).isCloseTo(0.25, within(1e-9))
        }
    }

    @Test
    fun `rig geometry follows the anchor rotation and the scale`() {
        val rotated = VfxTimeline.plan(definition(rig(slash())), Frame.fromYawPitch(Vec3.ZERO, 90.0, 0.0), 1L, 2.0)
        val plain = plan(rig(slash()), scale = 2.0)
        val a = rotated.elements.first { it.elementId == "sword.blade" }.initial
        val b = plain.elements.first { it.elementId == "sword.blade" }.initial
        assertThat(centre(a).distance(Vec3.ZERO)).isCloseTo(centre(b).distance(Vec3.ZERO), within(1e-9))
        assertThat(a.scale.y).isCloseTo(2.6 * 2.0, within(1e-9))
    }

    // ---- chunks ----------------------------------------------------------------------

    @Test
    fun `a slow swing is cut so no chunk turns more than 60 degrees`() {
        val m = VfxRigMotion(rig(slash(Easing.LINEAR, end = 8)), anchor.rotation, 1.0)
        assertThat(m.chunks).isNotEmpty()
        m.chunks.forEach { c ->
            assertThat(Math.toDegrees(c.from.angleTo(c.to))).isLessThanOrEqualTo(VfxRigMotion.MAX_STEP_DEG + 1e-6)
        }
    }

    @Test
    fun `a fast swing is one update per tick and a tick never turns more than 170 degrees`() {
        val m = VfxRigMotion(rig(slash(Easing.EASE_OUT_QUAD, end = 6)), anchor.rotation, 1.0)
        val sweep = m.chunks.filter { it.start >= 2 }
        assertThat(sweep).hasSize(4)
        sweep.forEach { c ->
            assertThat(c.end - c.start).isEqualTo(1)
            assertThat(degrees(c.from, c.to)).isLessThanOrEqualTo(VfxRigMotion.HARD_STEP_DEG)
        }
        // 300 degrees in 3 ticks is representable as three ~100 degree chunks
        val linear = VfxRigMotion(rig(slash(Easing.LINEAR, end = 5, to = -190.0)), anchor.rotation, 1.0)
        assertThat(linear.chunks.filter { it.start >= 2 }).hasSize(3)
    }

    @Test
    fun `a swing that turns more than 170 degrees inside one tick is refused`() {
        assertThatThrownBy { VfxRigMotion(rig(slash(Easing.EASE_OUT_EXPO, end = 5)), anchor.rotation, 1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("slerp")
    }

    // ---- trail -----------------------------------------------------------------------

    @Test
    fun `trail quad count equals sampled intervals times surfaces per interval`() {
        val r = rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail()))
        val m = VfxRigMotion(r, anchor.rotation, 1.0)
        val intervals = VfxRigPlanner.sampleIntervals(m, 2, 6, 30.0)
        assertThat(intervals.size).isGreaterThan(4) // fast swing: more than one sample per tick
        val p = plan(r)
        fun count(id: String) = p.elements.count { it.elementId == id }
        assertThat(count("wind.b0")).isEqualTo(intervals.size)
        assertThat(count("wind.b1")).isEqualTo(intervals.size)
        assertThat(count("wind.edge")).isEqualTo(intervals.size)
        assertThat(count("wind.rim")).isEqualTo(intervals.size)
        // every tick is sampled at least once
        for (k in 2 until 6) assertThat(intervals.any { it.first >= k && it.second <= k + 1 }).isTrue()
    }

    @Test
    fun `a steady swing samples exactly once per tick`() {
        val r = rig(slash(Easing.LINEAR, end = 10), trails = listOf(trail(start = 2, end = 10, step = 90.0)))
        val m = VfxRigMotion(r, anchor.rotation, 1.0)
        assertThat(VfxRigPlanner.sampleIntervals(m, 2, 10, 90.0)).hasSize(8)
    }

    @Test
    fun `soft quads fade monotonically and live exactly life ticks`() {
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail(life = 6))))
        val softs = p.elements.filter { it.elementId == "wind.b1" }
        assertThat(softs).isNotEmpty()
        softs.forEach { q ->
            val alphas = listOf(alphaOf(q)) +
                q.updates.map { requireNotNull(it.style).backgroundArgb ushr 24 }
            assertThat(alphas).isSortedAccordingTo(Comparator.reverseOrder())
            assertThat(alphas.first()).isEqualTo(0xC8)
            assertThat(alphas.last()).isLessThan(alphas.first())
            assertThat(q.despawnTick - q.spawnTick).isEqualTo(6)
            // quad colour travels toward color_to
            val lastRgb = q.updates.last().style!!.backgroundArgb and 0xFFFFFF
            assertThat(lastRgb).isNotEqualTo(0xFFFFFF)
        }
    }

    @Test
    fun `the inner band is fainter than the tip band and the crisp edge shrinks`() {
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail())))
        val inner = alphaOf(p.elements.first { it.elementId == "wind.b0" })
        val outer = alphaOf(p.elements.first { it.elementId == "wind.b1" })
        assertThat(inner).isLessThan(outer)
        val edge = p.elements.first { it.elementId == "wind.edge" }
        assertThat(edge.kind).isInstanceOf(VfxElementKind.Block::class.java)
        val widths = listOf(edge.initial.scale.x) + edge.updates.map { it.transform.scale.x }
        assertThat(widths).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(widths.last()).isLessThan(widths.first())
    }

    @Test
    fun `quads lie on the swept blade line`() {
        val r = rig(slash(Easing.LINEAR, end = 8), trails = listOf(trail(start = 2, end = 8, step = 15.0)))
        val p = plan(r)
        val m = VfxRigMotion(r, anchor.rotation, 1.0)
        val tip = p.elements.filter { it.elementId == "wind.edge" }
        // each edge slab sits at the tip radius (2.9) from the pivot, give or take half its chord
        tip.forEach { e ->
            val c = centre(e.initial)
            assertThat(c.distance(m.pivotWorld)).isBetween(2.7, 3.0)
        }
    }

    @Test
    fun `crisp style builds slabs from band blocks and no soft quads`() {
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail(style = VfxTrailStyle.CRISP))))
        assertThat(p.elements.none { it.kind is VfxElementKind.Quad }).isTrue()
        assertThat(p.elements.any { it.elementId == "wind.k0" }).isTrue()
        val both = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail(style = VfxTrailStyle.BOTH))))
        assertThat(both.elements.any { it.elementId == "wind.b0" }).isTrue()
        assertThat(both.elements.any { it.elementId == "wind.k1" }).isTrue()
    }

    @Test
    fun `lag delays the whole fan`() {
        val base = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail())))
        val lagged = plan(rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail().copy(lagTicks = 3))))
        val a = base.elements.filter { it.elementId == "wind.b1" }.map { it.spawnTick }
        val b = lagged.elements.filter { it.elementId == "wind.b1" }.map { it.spawnTick }
        assertThat(b).isEqualTo(a.map { it + 3 })
    }

    // ---- board + emitter -------------------------------------------------------------

    @Test
    fun `a board is one quad shown for its ticks`() {
        val board = VfxBoard("board", 4, 3, Vec3(0.0, 0.9, 0.0), Vec3(0.0, 2.9, 0.0), 90.0, 0xA0FFFFFF.toInt())
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD), boards = listOf(board)))
        val q = p.elements.single { it.elementId == "board" }
        assertThat(q.despawnTick - q.spawnTick).isEqualTo(3)
        assertThat(alphaOf(q)).isEqualTo(0xA0)
        assertThat(q.updates).allMatch { it.style!!.backgroundArgb == 0xA0FFFFFF.toInt() }
    }

    @Test
    fun `an emitter streams the tip path every tick`() {
        val emitter = VfxRigEmitter("embers", ParticleId("fire-trail"), Vec3(0.0, 2.9, 0.0), 2, 6)
        val p = plan(rig(slash(Easing.EASE_OUT_QUAD), emitters = listOf(emitter)))
        val e = p.elements.single { it.elementId == "sword.embers" }
        assertThat(e.particles).hasSize(5)
        e.particles.drop(1).forEach { em ->
            assertThat(em.pathOffsets).isNotEmpty()
            assertThat(em.pathOffsets.last().distance(em.offset)).isLessThan(1e-9)
        }
    }

    // ---- hit-stop --------------------------------------------------------------------

    @Test
    fun `a freeze window stops the swing and shifts the rest by its length`() {
        val r = rig(slash(Easing.EASE_OUT_QUAD), hitStop = true)
        val free = plan(r).elements.first { it.elementId == "sword.blade" }
        // base = 1: swing tick 4 is effect tick 5; freeze effect ticks 5..7 (3 ticks)
        val frozen = plan(r, listOf(VfxFreeze(5, 3))).elements.first { it.elementId == "sword.blade" }
        assertThat(frozen.updates.none { it.tick in 5..7 }).isTrue()
        val tickAfter = frozen.updates.first { it.tick >= 8 }
        val sameFree = free.updates.first { it.tick == 5 }
        assertThat(tickAfter.tick).isEqualTo(8)
        assertThat(tickAfter.transform.rotation.angleTo(sameFree.transform.rotation)).isLessThan(1e-9)
        // the update that ended at the freeze lasts exactly until it
        val before = frozen.updates.last { it.tick < 5 }
        assertThat(before.tick + before.interpolationTicks).isLessThanOrEqualTo(5)
    }

    @Test
    fun `a freeze holds the trail fade`() {
        val r = rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail(life = 6)), hitStop = true)
        val plain = plan(r).elements.filter { it.elementId == "wind.b1" }
        val frozen = plan(r, listOf(VfxFreeze(9, 4))).elements.filter { it.elementId == "wind.b1" }
        // quads alive across the window live 4 ticks longer; no fade update lands inside it
        val across = frozen.zip(plain).filter { (_, p) -> p.spawnTick < 9 && p.despawnTick > 9 }
        assertThat(across).isNotEmpty()
        across.forEach { (f, p) ->
            assertThat(f.despawnTick - p.despawnTick).isEqualTo(4)
            assertThat(f.updates.none { it.tick in 9..12 }).isTrue()
        }
    }

    @Test
    fun `without respect_hit_stop the freeze is ignored`() {
        val r = rig(slash(Easing.EASE_OUT_QUAD), trails = listOf(trail()), hitStop = false)
        assertThat(plan(r, listOf(VfxFreeze(5, 3)))).isEqualTo(plan(r))
    }
}
