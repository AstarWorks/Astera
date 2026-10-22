package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class VfxTimelineTest {

    private val origin = Frame(Vec3.ZERO)
    private val forward = Vec3(0.0, 0.0, 1.0)

    private fun element(
        id: String = "e",
        kind: VfxElementKind = VfxElementKind.Item("stick"),
        keyframes: List<VfxKeyframe>,
        start: Int = 0,
        end: Int? = null,
        count: Int = 1,
        formation: VfxFormation? = null,
        jitter: VfxJitter? = null,
        oscillate: VfxOscillate? = null,
        group: List<VfxGroupKeyframe> = emptyList(),
        slotLifetime: Int? = null,
    ) = VfxElement(
        id = id, kind = kind, startTick = start, endTick = end, count = count,
        formation = formation, jitter = jitter, keyframes = keyframes, oscillate = oscillate, groupKeyframes = group, slotLifetimeTicks = slotLifetime,
    )

    private fun definition(vararg elements: VfxElement, duration: Int = 30, sample: Int = 2, follow: Boolean = false) =
        VfxDefinition(VfxId("test"), duration, VfxOrient.WORLD, follow, sample, elements.toList())

    private fun plan(def: VfxDefinition, anchor: Frame = origin, seed: Long = 1L, scale: Double = 1.0) =
        VfxTimeline.plan(def, anchor, seed, scale)

    private fun assertVec(actual: Vec3, x: Double, y: Double, z: Double, eps: Double = 1e-6) {
        assertThat(actual.x).isCloseTo(x, within(eps))
        assertThat(actual.y).isCloseTo(y, within(eps))
        assertThat(actual.z).isCloseTo(z, within(eps))
    }

    private fun kf(
        t: Int,
        translate: Vec3? = null,
        rotate: Vec3? = null,
        scale: Vec3? = null,
        ease: Easing = Easing.LINEAR,
        block: String? = null,
        text: String? = null,
    ) = VfxKeyframe(t, translate, rotate, scale, ease, block, text)

    // ---- keyframes -------------------------------------------------------------

    @Test
    fun `absent keyframe fields carry over from the previous keyframe`() {
        val p = plan(
            definition(
                element(
                    keyframes = listOf(
                        kf(0, scale = Vec3(1.0, 2.0, 3.0), translate = Vec3(1.0, 0.0, 0.0)),
                        kf(4, translate = Vec3(1.0, 4.0, 0.0)),
                    ),
                ),
            ),
        ).elements.single()

        assertVec(p.initial.scale, 1.0, 2.0, 3.0)
        assertThat(p.updates).isNotEmpty()
        p.updates.forEach { assertVec(it.transform.scale, 1.0, 2.0, 3.0) }
        assertVec(p.updates.last().transform.translation, 1.0, 4.0, 0.0)
    }

    @Test
    fun `the first keyframe defaults to identity and unit scale`() {
        val p = plan(definition(element(keyframes = listOf(kf(0))))).elements.single()
        assertVec(p.initial.translation, 0.0, 0.0, 0.0)
        assertVec(p.initial.scale, 1.0, 1.0, 1.0)
        assertThat(p.initial.rotation.angleTo(Quaternion.IDENTITY)).isCloseTo(0.0, within(1e-9))
        assertThat(p.updates).isEmpty()
    }

    @Test
    fun `a segment is split into sample_ticks chunks that sample the ease at each chunk end`() {
        val p = plan(
            definition(
                element(keyframes = listOf(kf(0, translate = Vec3.ZERO), kf(4, translate = Vec3(10.0, 0.0, 0.0), ease = Easing.EASE_IN_QUAD))),
                sample = 2,
            ),
        ).elements.single()

        // keyframe clock starts one tick after the spawn
        assertThat(p.updates.map { it.tick }).containsExactly(1, 3)
        assertThat(p.updates.map { it.interpolationTicks }).containsExactly(2, 2)
        assertThat(p.updates[0].transform.translation.x).isCloseTo(10.0 * 0.25, within(1e-9)) // in_quad(0.5)
        assertThat(p.updates[1].transform.translation.x).isCloseTo(10.0, within(1e-9))
    }

    @Test
    fun `an odd segment length ends on a shorter last chunk`() {
        val p = plan(
            definition(element(keyframes = listOf(kf(0), kf(3, translate = Vec3(3.0, 0.0, 0.0)))), sample = 2),
        ).elements.single()
        assertThat(p.updates.map { it.interpolationTicks }.sum()).isEqualTo(3)
        assertThat(p.updates.last().transform.translation.x).isCloseTo(3.0, within(1e-9))
    }

    @Test
    fun `sample_ticks 1 yields one update per tick`() {
        val p = plan(
            definition(element(keyframes = listOf(kf(0), kf(4, translate = Vec3(4.0, 0.0, 0.0)))), sample = 1),
        ).elements.single()
        assertThat(p.updates.map { it.tick }).containsExactly(1, 2, 3, 4)
        assertThat(p.updates.map { it.transform.translation.x }).containsExactly(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `the ease belongs to the segment ending at its keyframe`() {
        val p = plan(
            definition(
                element(
                    keyframes = listOf(
                        kf(0),
                        kf(2, translate = Vec3(2.0, 0.0, 0.0), ease = Easing.EASE_OUT_CUBIC),
                        kf(4, translate = Vec3(4.0, 0.0, 0.0)),
                    ),
                ),
                sample = 1,
            ),
        ).elements.single()
        val xs = p.updates.map { it.transform.translation.x }
        assertThat(xs[0]).isCloseTo(2.0 * Easing.EASE_OUT_CUBIC.apply(0.5), within(1e-9))
        assertThat(xs[1]).isCloseTo(2.0, within(1e-9))
        assertThat(xs[2]).isCloseTo(3.0, within(1e-9)) // second segment is linear
    }

    @Test
    fun `no update lands on the spawn tick and all updates precede the despawn`() {
        val p = plan(
            definition(
                element(start = 5, end = 12, keyframes = listOf(kf(0), kf(20, translate = Vec3(9.0, 0.0, 0.0)))),
                sample = 1,
            ),
        ).elements.single()
        assertThat(p.spawnTick).isEqualTo(5)
        assertThat(p.despawnTick).isEqualTo(12)
        assertThat(p.updates.map { it.tick }).doesNotContain(5).isSorted()
        assertThat(p.updates.map { it.tick }).allMatch { it in 6 until 12 }
        assertThat(p.updates.map { it.tick }).doesNotHaveDuplicates()
    }

    @Test
    fun `a keyframe clock starting after zero holds the first keyframe`() {
        val p = plan(
            definition(element(keyframes = listOf(kf(3, translate = Vec3(1.0, 0.0, 0.0)), kf(5, translate = Vec3(3.0, 0.0, 0.0)))), sample = 1),
        ).elements.single()
        assertVec(p.initial.translation, 1.0, 0.0, 0.0)
        assertThat(p.updates.map { it.tick }).containsExactly(4, 5)
    }

    @Test
    fun `a long spin is split so each chunk turns at most 170 degrees`() {
        val p = plan(
            definition(
                element(keyframes = listOf(kf(0, rotate = Vec3.ZERO), kf(4, rotate = Vec3(0.0, 0.0, 360.0)))),
                sample = 4,
            ),
        ).elements.single()
        assertThat(p.updates).hasSize(4)
        assertThat(p.updates.map { it.interpolationTicks }).containsOnly(1)
    }

    @Test
    fun `a gentle rotation is not split`() {
        val p = plan(
            definition(element(keyframes = listOf(kf(0), kf(4, rotate = Vec3(0.0, 0.0, 90.0)))), sample = 4),
        ).elements.single()
        assertThat(p.updates).hasSize(1)
    }

    // ---- content swaps ---------------------------------------------------------

    @Test
    fun `block swap keyframes become updates that carry the new block`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Block("white_concrete"),
                    keyframes = listOf(kf(0), kf(4, block = "white_stained_glass"), kf(8, block = "glass")),
                ),
                sample = 2,
            ),
        ).elements.single()
        val swaps = p.updates.filter { it.block != null }
        assertThat(swaps.map { it.tick to it.block }).containsExactly(5 to "white_stained_glass", 9 to "glass")
        assertThat((p.kind as VfxElementKind.Block).blockKey).isEqualTo("white_concrete")
    }

    @Test
    fun `a swap on the first keyframe replaces the spawn content`() {
        val p = plan(
            definition(
                element(kind = VfxElementKind.Block("stone"), keyframes = listOf(kf(0, block = "red_concrete"), kf(2))),
            ),
        ).elements.single()
        assertThat((p.kind as VfxElementKind.Block).blockKey).isEqualTo("red_concrete")
    }

    @Test
    fun `text swap keyframes carry the new text`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Text("<red>A"),
                    keyframes = listOf(kf(0), kf(3, text = "<red>B")),
                ),
            ),
        ).elements.single()
        assertThat(p.updates.filter { it.text != null }.map { it.tick to it.text }).containsExactly(4 to "<red>B")
    }

    @Test
    fun `swap keyframes are only legal for the matching kind`() {
        assertThatThrownBy { element(kind = VfxElementKind.Item("stick"), keyframes = listOf(kf(0, block = "stone"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- oscillation -----------------------------------------------------------

    @Test
    fun `oscillate adds a per tick cosine on the chosen axis`() {
        val p = plan(
            definition(
                element(
                    end = 7,
                    keyframes = listOf(kf(0, scale = Vec3(1.0, 1.0, 1.0))),
                    oscillate = VfxOscillate(VfxOscillateAxis.SCALE_XZ, 0.1, 2),
                ),
            ),
        ).elements.single()

        assertThat(p.updates.map { it.tick }).containsExactly(1, 2, 3, 4, 5, 6)
        assertThat(p.updates.map { it.interpolationTicks }).containsOnly(1)
        // update at tick k targets keyframe time k: cos(pi * k) = +-1 for period 2
        p.updates.forEachIndexed { i, u ->
            val sign = if ((i + 1) % 2 == 0) 1.0 else -1.0
            assertThat(u.transform.scale.x).isCloseTo(1.0 + sign * 0.1, within(1e-9))
            assertThat(u.transform.scale.z).isCloseTo(1.0 + sign * 0.1, within(1e-9))
            assertThat(u.transform.scale.y).isCloseTo(1.0, within(1e-9))
        }
    }

    // ---- formation -------------------------------------------------------------

    private fun arcFormation(align: VfxAlign, stagger: Int = 0, reverse: Boolean = false) = VfxFormation(
        curve = Curve.Arc(2.0, 0.0, 90.0), align = align, staggerTicks = stagger, reverse = reverse,
    )

    @Test
    fun `slots sit on the curve and stagger their start`() {
        val plan = plan(definition(element(count = 2, formation = arcFormation(VfxAlign.NONE, stagger = 3), start = 1, keyframes = listOf(kf(0)))))
        val (a, b) = plan.elements
        assertThat(a.spawnTick).isEqualTo(1)
        assertThat(b.spawnTick).isEqualTo(4)
        assertVec(a.initial.translation, 0.0, 0.0, 2.0) // arc angle 0 = +Z
        assertVec(b.initial.translation, 2.0, 0.0, 0.0) // arc angle 90 = +X (left)
    }

    @Test
    fun `reverse flips the stagger order along the curve`() {
        val plan = plan(definition(element(count = 2, formation = arcFormation(VfxAlign.NONE, stagger = 3, reverse = true), keyframes = listOf(kf(0)))))
        val bySlot = plan.elements.associateBy { it.slot }
        assertThat(bySlot.getValue(0).spawnTick).isEqualTo(3)
        assertThat(bySlot.getValue(1).spawnTick).isEqualTo(0)
        assertThat(plan.elements.map { it.spawnTick }).isSorted()
    }

    @Test
    fun `tangent alignment points local forward along the curve`() {
        val plan = plan(definition(element(count = 2, formation = arcFormation(VfxAlign.TANGENT), keyframes = listOf(kf(0)))))
        val bySlot = plan.elements.associateBy { it.slot }
        assertVec(bySlot.getValue(0).initial.rotation.rotate(forward), 1.0, 0.0, 0.0) // arc starts heading +X
        assertVec(bySlot.getValue(1).initial.rotation.rotate(forward), 0.0, 0.0, -1.0)
    }

    @Test
    fun `outward alignment points local forward away from the centre`() {
        val plan = plan(definition(element(count = 2, formation = arcFormation(VfxAlign.OUTWARD), keyframes = listOf(kf(0)))))
        val bySlot = plan.elements.associateBy { it.slot }
        assertVec(bySlot.getValue(0).initial.rotation.rotate(forward), 0.0, 0.0, 1.0)
        assertVec(bySlot.getValue(1).initial.rotation.rotate(forward), 1.0, 0.0, 0.0)
    }

    @Test
    fun `none and anchor alignment keep the anchor orientation`() {
        for (align in listOf(VfxAlign.NONE, VfxAlign.ANCHOR)) {
            val p = plan(definition(element(count = 2, formation = arcFormation(align), keyframes = listOf(kf(0))))).elements
            p.forEach { assertThat(it.initial.rotation.angleTo(Quaternion.IDENTITY)).isCloseTo(0.0, within(1e-9)) }
        }
    }

    @Test
    fun `tilt rotates the curve plane and anchor alignment follows it but none does not`() {
        val tilted = VfxFormation(Curve.Arc(2.0, 90.0, 90.0), tiltYawPitchRoll = Vec3(0.0, 0.0, 90.0), align = VfxAlign.ANCHOR)
        val p = plan(definition(element(formation = tilted, keyframes = listOf(kf(0))))).elements.single()
        // arc point (2,0,0) rolled 90 deg about Z becomes vertical
        assertThat(Math.abs(p.initial.translation.y)).isCloseTo(2.0, within(1e-6))
        assertThat(p.initial.rotation.angleTo(Quaternion.IDENTITY)).isCloseTo(Math.PI / 2, within(1e-6))

        val none = plan(definition(element(formation = tilted.copy(align = VfxAlign.NONE), keyframes = listOf(kf(0))))).elements.single()
        assertThat(none.initial.rotation.angleTo(Quaternion.IDENTITY)).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `a closed circle does not duplicate its start slot`() {
        val circle = VfxFormation(Curve.Arc(2.0, 0.0, 360.0))
        val slots = plan(definition(element(count = 4, formation = circle, keyframes = listOf(kf(0))))).elements.sortedBy { it.slot }
        assertVec(slots[0].initial.translation, 0.0, 0.0, 2.0)
        assertVec(slots[1].initial.translation, 2.0, 0.0, 0.0)
        assertVec(slots[2].initial.translation, 0.0, 0.0, -2.0)
        assertThat(slots[3].initial.translation.x).isCloseTo(-2.0, within(1e-6))
    }

    @Test
    fun `the formation offset moves every slot`() {
        val f = VfxFormation(Curve.Line(Vec3.ZERO, Vec3(0.0, 0.0, 2.0)), offset = Vec3(0.0, 1.5, 3.0))
        val p = plan(definition(element(count = 2, formation = f, keyframes = listOf(kf(0))))).elements.sortedBy { it.slot }
        assertVec(p[0].initial.translation, 0.0, 1.5, 3.0)
        assertVec(p[1].initial.translation, 0.0, 1.5, 5.0)
    }

    @Test
    fun `slots that would start after the effect ends are dropped and lifetimes are capped`() {
        val p = plan(
            definition(
                element(count = 4, formation = arcFormation(VfxAlign.NONE, stagger = 4), end = 10, keyframes = listOf(kf(0))),
                duration = 10,
            ),
        ).elements
        assertThat(p.map { it.spawnTick }).containsExactly(0, 4, 8)
        assertThat(p.map { it.despawnTick }).containsExactly(10, 10, 10)
    }

    @Test
    fun `end_tick is absolute for every staggered slot`() {
        val p = plan(
            definition(element(count = 2, start = 2, end = 8, formation = arcFormation(VfxAlign.NONE, stagger = 3), keyframes = listOf(kf(0)))),
        ).elements.sortedBy { it.slot }
        assertThat(p.map { it.spawnTick }).containsExactly(2, 5)
        assertThat(p.map { it.despawnTick }).containsOnly(8)
    }

    @Test
    fun `slot_lifetime_ticks keeps staggered copies equally long-lived`() {
        val p = plan(
            definition(element(count = 2, start = 2, slotLifetime = 6, formation = arcFormation(VfxAlign.NONE, stagger = 3), keyframes = listOf(kf(0)))),
        ).elements.sortedBy { it.slot }
        assertThat(p.map { it.despawnTick - it.spawnTick }).containsOnly(6)
    }

    // ---- local frame, anchor, centering ----------------------------------------

    @Test
    fun `keyframe translation is local to the anchor orientation`() {
        val facingWest = Frame.fromYawPitch(Vec3.ZERO, 90.0, 0.0)
        val p = plan(definition(element(keyframes = listOf(kf(0, translate = Vec3(0.0, 0.0, 3.0))))), anchor = facingWest).elements.single()
        assertVec(p.initial.translation, -3.0, 0.0, 0.0)
    }

    @Test
    fun `block displays rotate and scale about their centre`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Block("stone"),
                    keyframes = listOf(kf(0, translate = Vec3(0.0, 1.0, 2.0), rotate = Vec3(30.0, 20.0, 45.0), scale = Vec3(2.0, 4.0, 6.0))),
                ),
            ),
        ).elements.single()
        val t = p.initial
        val center = t.translation + t.rotation.rotate(Vec3(t.scale.x * 0.5, t.scale.y * 0.5, t.scale.z * 0.5))
        assertVec(center, 0.0, 1.0, 2.0)
    }

    @Test
    fun `block centering with identity rotation is half the scale`() {
        val p = plan(definition(element(kind = VfxElementKind.Block("stone"), keyframes = listOf(kf(0, scale = Vec3(2.0, 4.0, 6.0)))))).elements.single()
        assertVec(p.initial.translation, -1.0, -2.0, -3.0)
    }

    @Test
    fun `item and text displays are not offset for centring`() {
        val p = plan(definition(element(kind = VfxElementKind.Text("x"), keyframes = listOf(kf(0, scale = Vec3(2.0, 4.0, 6.0)))))).elements.single()
        assertVec(p.initial.translation, 0.0, 0.0, 0.0)
    }

    @Test
    fun `the scale argument multiplies lengths but not rotations`() {
        val def = definition(
            element(
                kind = VfxElementKind.Block("stone"),
                formation = VfxFormation(Curve.Line(Vec3.ZERO, Vec3(0.0, 0.0, 2.0)), offset = Vec3(0.0, 1.0, 0.0)),
                keyframes = listOf(kf(0, translate = Vec3(1.0, 0.0, 0.0), scale = Vec3(1.0, 1.0, 1.0), rotate = Vec3(0.0, 0.0, 30.0))),
            ),
        )
        val one = plan(def, scale = 1.0).elements.single().initial
        val two = plan(def, scale = 2.0).elements.single().initial
        assertVec(two.scale, 2.0, 2.0, 2.0)
        assertVec(two.translation, one.translation.x * 2, one.translation.y * 2, one.translation.z * 2)
        assertThat(two.rotation.angleTo(one.rotation)).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `plan origin is the anchor origin and follow is carried over`() {
        val anchor = Frame(Vec3(5.0, 6.0, 7.0))
        val p = plan(definition(element(keyframes = listOf(kf(0))), follow = true), anchor = anchor)
        assertThat(p.origin).isEqualTo(Vec3(5.0, 6.0, 7.0))
        assertThat(p.follow).isTrue()
        assertVec(p.elements.single().initial.translation, 0.0, 0.0, 0.0) // offsets only
    }

    // ---- jitter ----------------------------------------------------------------

    private fun jittered() = definition(
        element(
            count = 5,
            formation = VfxFormation(Curve.Line(Vec3.ZERO, Vec3(4.0, 0.0, 0.0))),
            jitter = VfxJitter(position = 0.3, rotationDeg = 15.0, scale = 0.25),
            keyframes = listOf(kf(0, scale = Vec3(1.0, 1.0, 1.0))),
        ),
    )

    @Test
    fun `jitter is deterministic per seed and differs between seeds`() {
        val a = plan(jittered(), seed = 42L)
        val b = plan(jittered(), seed = 42L)
        val c = plan(jittered(), seed = 43L)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `jitter stays inside its bounds and differs between slots`() {
        val slots = plan(jittered(), seed = 7L).elements.sortedBy { it.slot }
        slots.forEachIndexed { i, s ->
            val ideal = Vec3(4.0 * i / 4, 0.0, 0.0)
            assertThat(Math.abs(s.initial.translation.x - ideal.x)).isLessThanOrEqualTo(0.3 + 1e-9)
            assertThat(Math.abs(s.initial.translation.y)).isLessThanOrEqualTo(0.3 + 1e-9)
            assertThat(s.initial.scale.x).isBetween(0.75 - 1e-9, 1.25 + 1e-9)
        }
        assertThat(slots.map { it.initial.scale.x }.toSet().size).isGreaterThan(1)
    }

    // ---- particles -------------------------------------------------------------

    @Test
    fun `particle elements emit at their position every n ticks and spawn no entity`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Particle(ParticleId("hit-spark"), everyTicks = 2),
                    end = 7,
                    keyframes = listOf(kf(0, translate = Vec3.ZERO), kf(6, translate = Vec3(0.0, 0.0, 6.0))),
                ),
            ),
        )
        val e = p.elements.single()
        assertThat(p.displayCount).isEqualTo(0)
        assertThat(e.updates).isEmpty()
        assertThat(e.particles.map { it.tick }).containsExactly(0, 2, 4, 6)
        e.particles.forEachIndexed { i, em -> assertThat(em.offset.z).isCloseTo(2.0 * i, within(1e-9)) }
    }

    @Test
    fun `along_path particle elements emit the whole streak between emissions`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Particle(
                        ParticleId("streak"),
                        everyTicks = 1,
                        alongPath = true,
                        stepBlocks = 0.5,
                        trailTo = Vec3(0.0, 2.0, 0.0),
                    ),
                    end = 5,
                    keyframes = listOf(kf(0, translate = Vec3.ZERO), kf(4, translate = Vec3(0.0, 0.0, 4.0))),
                ),
            ),
        )
        val e = p.elements.single().particles
        assertThat(e[0].pathOffsets).isEmpty()
        // 1 block per tick, 0.5 spacing -> two points per tick, the last equal to the emission position
        assertThat(e[2].pathOffsets).hasSize(2)
        assertVec(e[2].pathOffsets.last(), 0.0, 0.0, e[2].offset.z)
        assertThat(e[2].pathOffsets.first().z).isCloseTo(e[1].offset.z + 0.5, within(1e-6))
        // direction of travel and the plan-relative trail target
        assertVec(e[1].tangent!!, 0.0, 0.0, 1.0)
        assertVec(e[1].trailTarget!!, 0.0, 2.0, 0.0)
    }

    @Test
    fun `without along_path no path points are planned and a still element has no tangent`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Particle(ParticleId("still")),
                    end = 3,
                    keyframes = listOf(kf(0, translate = Vec3(1.0, 0.0, 0.0))),
                ),
            ),
        )
        p.elements.single().particles.forEach {
            assertThat(it.pathOffsets).isEmpty()
            assertThat(it.tangent).isNull()
            assertThat(it.trailTarget).isNull()
        }
    }

    @Test
    fun `formation particle elements carry the formation centre for outward velocity`() {
        val p = plan(
            definition(
                element(
                    kind = VfxElementKind.Particle(ParticleId("ring-sparks")),
                    end = 2,
                    count = 2,
                    formation = VfxFormation(Curve.Arc(2.0, 0.0, 180.0), offset = Vec3(0.0, 1.0, 0.0)),
                    keyframes = listOf(kf(0)),
                ),
            ),
        )
        p.elements.forEach { el -> el.particles.forEach { assertVec(it.center!!, 0.0, 1.0, 0.0) } }
    }

    // ---- group keyframes -------------------------------------------------------

    @Test
    fun `group rotation swings the whole formation about the anchor`() {
        val e = plan(
            definition(
                element(
                    count = 2,
                    formation = VfxFormation(Curve.Line(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0)), offset = Vec3(2.0, 0.0, 0.0)),
                    keyframes = listOf(kf(0)),
                    group = listOf(VfxGroupKeyframe(0), VfxGroupKeyframe(10, rotateEuler = Vec3(90.0, 0.0, 0.0))),
                ),
            ),
        ).elements.first()
        assertVec(e.initial.translation, 2.0, 0.0, 0.0)
        assertThat(e.updates).isNotEmpty()
        // After 90 degrees about Y the offset lies on the Z axis, radius preserved.
        val end = e.updates.last().transform.translation
        assertThat(end.x).isCloseTo(0.0, within(1e-6))
        assertThat(kotlin.math.abs(end.z)).isCloseTo(2.0, within(1e-6))
        // Samples stay on the circle of radius 2 (rotation, not a lerp).
        e.updates.forEach { assertThat(it.transform.translation.length()).isCloseTo(2.0, within(1e-6)) }
        // The rotation is applied to the display orientation too.
        assertThat(e.updates.last().transform.rotation.angleTo(e.initial.rotation)).isCloseTo(Math.PI / 2, within(1e-6))
    }

    @Test
    fun `group translate and scale move and grow the formation, element keyframes stay local`() {
        val e = plan(
            definition(
                element(
                    formation = VfxFormation(Curve.Line(Vec3.ZERO, Vec3.ZERO), offset = Vec3(1.0, 0.0, 0.0)),
                    keyframes = listOf(kf(0, scale = Vec3(1.0, 1.0, 1.0))),
                    group = listOf(VfxGroupKeyframe(0), VfxGroupKeyframe(4, translate = Vec3(0.0, 3.0, 0.0), scale = 2.0)),
                ),
            ),
        ).elements.first()
        val last = e.updates.last().transform
        assertVec(last.translation, 2.0, 3.0, 0.0)
        assertVec(last.scale, 2.0, 2.0, 2.0)
    }

    @Test
    fun `fast group spins are chunked below the shortest-path limit`() {
        val e = plan(
            definition(
                element(
                    formation = VfxFormation(Curve.Line(Vec3.ZERO, Vec3.ZERO), offset = Vec3(1.0, 0.0, 0.0)),
                    keyframes = listOf(kf(0)),
                    group = listOf(VfxGroupKeyframe(0), VfxGroupKeyframe(8, rotateEuler = Vec3(720.0, 0.0, 0.0))),
                ),
                sample = 8,
            ),
        ).elements.first()
        assertThat(e.updates.size).isGreaterThanOrEqualTo(5)
        e.updates.forEach { assertThat(it.interpolationTicks).isLessThanOrEqualTo(2) }
    }

    @Test
    fun `a ring formation travels forward along local +Z with a group translate`() {
        val facingEast = Frame.fromYawPitch(Vec3.ZERO, -90.0, 0.0)
        for (anchor in listOf(origin, facingEast)) {
            val plan = plan(
                definition(
                    element(
                        count = 6,
                        formation = VfxFormation(Curve.Arc(1.5, 0.0, 360.0)),
                        keyframes = listOf(kf(0)),
                        group = listOf(VfxGroupKeyframe(0), VfxGroupKeyframe(10, translate = Vec3(0.0, 0.0, 8.0))),
                    ),
                ),
                anchor = anchor,
            ).elements
            val fwd = anchor.forward()
            for (slot in plan) {
                val start = slot.initial.translation
                val end = slot.updates.last().transform.translation
                val delta = end - start
                assertVec(delta, fwd.x * 8.0, fwd.y * 8.0, fwd.z * 8.0, 1e-6)
            }
        }
    }

    @Test
    fun `group keyframes must be strictly increasing`() {
        assertThatThrownBy { element(keyframes = listOf(kf(0)), group = listOf(VfxGroupKeyframe(2), VfxGroupKeyframe(2))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- validation + orient ---------------------------------------------------

    @Test
    fun `definitions reject unsorted keyframes, duplicate ids and excessive duration`() {
        assertThatThrownBy { element(keyframes = listOf(kf(3), kf(3))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { definition(element(id = "a", keyframes = listOf(kf(0))), element(id = "a", keyframes = listOf(kf(0)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { definition(element(keyframes = listOf(kf(0))), duration = 201) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { definition(element(start = 30, keyframes = listOf(kf(0))), duration = 30) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `orient yaw ignores pitch and look follows it`() {
        val dir = Vec3(0.0, -0.5, 1.0)
        val yaw = VfxOrient.YAW.frame(Vec3.ZERO, dir, null)
        assertVec(yaw.forward(), 0.0, 0.0, 1.0)
        val look = VfxOrient.LOOK.frame(Vec3.ZERO, dir, null)
        assertVec(look.forward(), dir.normalize().x, dir.normalize().y, dir.normalize().z)
        assertThat(VfxOrient.WORLD.frame(Vec3.ZERO, dir, null).rotation.angleTo(Quaternion.IDENTITY)).isCloseTo(0.0, within(1e-9))
        assertVec(VfxOrient.TO_TARGET.frame(Vec3.ZERO, dir, Vec3(1.0, 0.0, 0.0)).forward(), 1.0, 0.0, 0.0)
        assertVec(VfxOrient.TO_TARGET.frame(Vec3.ZERO, Vec3(0.0, 0.0, 1.0), null).forward(), 0.0, 0.0, 1.0)
    }
}
