package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.ridingTransform
import com.astarworks.astera.combat.adapter.paper.skillengine.toDisplayWrite
import com.astarworks.astera.combat.adapter.paper.skillengine.Intrinsic
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skillengine.frame.Axes
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

/**
 * ADR-0303 pose core: one frame tree, one basis, right-hand rotations, sizes in blocks, one display write.
 * Real tree, real quaternion maths, and the shipped content cast through the real runtime ([CastSim], [RecordingVisuals]).
 */
class PoseCoreScenarioTest {
    private val eps = Offset.offset(1e-9)

    private fun assertVec(actual: Vec3, expected: Vec3, tolerance: Offset<Double> = eps) {
        assertThat(actual.x).isCloseTo(expected.x, tolerance)
        assertThat(actual.y).isCloseTo(expected.y, tolerance)
        assertThat(actual.z).isCloseTo(expected.z, tolerance)
    }

    private fun f(x: Double, y: Double, z: Double, rx: Double, ry: Double, rz: Double) =
        Frame(Vec3(x, y, z), Quaternion.fromRotationVector(Vec3(rx, ry, rz)))

    @Test
    fun `compose is associative and the identity frame changes nothing`() {
        val a = f(1.0, 2.0, 3.0, 10.0, 70.0, -20.0)
        val b = f(-4.0, 0.5, 2.0, 35.0, -15.0, 80.0)
        val c = f(0.3, -1.0, 6.0, -60.0, 25.0, 5.0)
        val left = a.compose(b).compose(c)
        val right = a.compose(b.compose(c))
        assertVec(left.origin, right.origin)
        val p = Vec3(0.7, -0.2, 1.9)
        assertVec(left.toWorld(p), right.toWorld(p))
        val identity = Frame(Vec3.ZERO, Quaternion.IDENTITY)
        assertVec(a.compose(identity).toWorld(p), a.toWorld(p))
        assertVec(identity.compose(a).toWorld(p), a.toWorld(p))
        // world = parentWorld o local: a point of the child equals the parent's transform of the child's transform
        assertVec(a.compose(b).toWorld(p), a.toWorld(b.toWorld(p)))
    }

    @Test
    fun `axis of a frame is the rotated unit vector and the one basis round-trips`() {
        val frame = f(5.0, 1.0, -2.0, 20.0, 115.0, -40.0)
        val q = frame.rotation
        assertVec(Basis.axis(frame, "left")!!, q.rotate(Vec3(1.0, 0.0, 0.0)))
        assertVec(Basis.axis(frame, "up")!!, q.rotate(Vec3(0.0, 1.0, 0.0)))
        assertVec(Basis.axis(frame, "forward")!!, q.rotate(Vec3(0.0, 0.0, 1.0)))
        assertVec(Basis.axis(frame, "-up")!!, q.rotate(Vec3(0.0, -1.0, 0.0)))
        val tree = InMemoryFrameTree()
        tree.add(FrameNode(FrameId("world"), FrameKind.WORLD, null, Frame(Vec3.ZERO)))
        tree.add(FrameNode(FrameId("piece"), FrameKind.PART, FrameId("world"), frame))
        assertVec(Axes.resolve(tree, "piece.forward")!!, q.rotate(Vec3(0.0, 0.0, 1.0)))
        // basis(x, y) is the rotation whose columns are x and its y-part perpendicular to x
        val back = Basis.basis(q.rotate(Vec3(1.0, 0.0, 0.0)), q.rotate(Vec3(0.0, 1.0, 0.0)))!!
        assertVec(back.rotate(Vec3(0.0, 0.0, 1.0)), q.rotate(Vec3(0.0, 0.0, 1.0)))
    }

    @Test
    fun `a positive spin about up turns forward toward left (right-hand rule)`() {
        val turned = Quaternion.fromRotationVector(Vec3(0.0, 90.0, 0.0)).rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(turned, Vec3(1.0, 0.0, 0.0))
        val half = Quaternion.fromRotationVector(Vec3(0.0, 45.0, 0.0)).rotate(Vec3(0.0, 0.0, 1.0))
        assertThat(half.x).isGreaterThan(0.0)
        assertThat(half.z).isGreaterThan(0.0)
        // a rotation vector has axis * degrees: x about left lifts forward toward up? (+X rotates +Z toward -Y)
        val pitched = Quaternion.fromRotationVector(Vec3(90.0, 0.0, 0.0)).rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(pitched, Vec3(0.0, -1.0, 0.0))
    }

    @Test
    fun `three spawned frames with different rotations each carry their own child (founder invariant)`() {
        val tree = InMemoryFrameTree()
        tree.add(FrameNode(FrameId("world"), FrameKind.WORLD, null, Frame(Vec3.ZERO)))
        tree.add(FrameNode(FrameId("volley"), FrameKind.PART, FrameId("world"), f(10.0, 64.0, -3.0, 0.0, 33.0, 0.0)))
        val spins = listOf(0.0, 120.0, 240.0)
        val childLocal = Vec3(0.0, 0.0, 1.5) // a point on the blade, along that blade's own forward
        spins.forEachIndexed { i, deg ->
            val base = Frame(Vec3(0.0, 0.0, 0.0), Quaternion.fromRotationVector(Vec3(0.0, deg, 0.0)))
            tree.add(FrameNode(FrameId("blade$i"), FrameKind.PART, FrameId("volley"), base))
            tree.add(FrameNode(FrameId("tip$i"), FrameKind.POINT, FrameId("blade$i"), Frame(childLocal)))
        }
        val volley = tree.worldOf(FrameId("volley")).getOrNull()!!
        // each blade's origin moves by its own eased law (here ease_out over 10 ticks, a different distance per blade)
        for (tick in 0..10) {
            val k = tick / 10.0
            val eased = 1.0 - (1.0 - k) * (1.0 - k)
            spins.forEachIndexed { i, deg ->
                val travel = Vec3(0.0, 0.0, 4.0 * (i + 1) * eased)
                tree.setLocal(FrameId("blade$i"), Frame(travel, Quaternion.fromRotationVector(Vec3(0.0, deg, 0.0))))
            }
            val seen = HashSet<String>()
            spins.forEachIndexed { i, deg ->
                val expectedBlade = volley.compose(Frame(Vec3(0.0, 0.0, 4.0 * (i + 1) * eased), Quaternion.fromRotationVector(Vec3(0.0, deg, 0.0))))
                val tip = tree.worldOf(FrameId("tip$i")).getOrNull()!!.origin
                assertVec(tip, expectedBlade.toWorld(childLocal))
                seen += "%.6f,%.6f".format(tip.x, tip.z)
            }
            if (tick > 0) assertThat(seen).describedAs("tick $tick: every child sits at its own place").hasSize(3)
        }
        // blades keep their own facing: blade1 faces 120 degrees about up from the volley frame
        val faces = (0..2).map { Basis.axis(tree.worldOf(FrameId("blade$it")).getOrNull()!!, "forward")!! }
        assertThat(faces[0].dot(faces[1])).isCloseTo(Math.cos(Math.toRadians(120.0)), Offset.offset(1e-9))
        assertThat(faces[1].dot(faces[2])).isCloseTo(Math.cos(Math.toRadians(120.0)), Offset.offset(1e-9))
    }

    @Test
    fun `a riding display write adds the offset from the feet it was computed against minus the seat`() {
        val player = PlayerId(UUID(0, 7))
        val feet = Vec3(100.0, 64.0, 100.0)
        val world = Frame(Vec3(101.0, 65.5, 99.0), Quaternion.fromRotationVector(Vec3(0.0, 30.0, 0.0)))
        val write = toDisplayWrite(world, Vec3(2.0, 2.0, 2.0), Intrinsic.ITEM_FIXED, DisplayBillboard.FIXED, 1, DisplayWrite.Anchor(player, feet))
        assertThat(write.anchor).isNotNull
        val riding = ridingTransform(write, seatHeight = 0.6)
        // translation = intrinsic centre (zero for items) + (at - feet) - (0, seat, 0)
        assertVec(riding.translation, Vec3(1.0, 1.5 - 0.6, -1.0))
        assertVec(riding.scale, write.transform.scale)
        assertThat(riding.rotation).isEqualTo(write.transform.rotation)
    }

    @Test
    fun `rendered size in blocks equals the declared size and every display tick is one write`() {
        val origin = Vec3(37.5, -60.0, -12.5)
        val dummies = (1..4).associate { simVictim(it) to ahead(origin, 30.0, 2.0 * it) }
        val ids = CastSim(dummies, origin = origin, yawDeg = 30.0).catalog.ids().filter { '@' !in it }.sorted()
        val bad = ArrayList<String>()
        var plates = 0
        var meshes = 0
        var lines = 0
        for (id in ids) {
            lateinit var sim: CastSim
            val rec = RecordingVisuals(now = { sim.now })
            sim = CastSim(dummies, origin = origin, yawDeg = 30.0, visualGateway = rec, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            runCatching { sim.cast(id, 120) }
            for (fr in rec.frames) {
                when (fr.kind) {
                    "plate" -> {
                        plates++
                        // the client draws width = scale.x * px * 0.025; the pose's size.x is that width in blocks
                        if (abs(fr.renderedW - fr.authored) > 1e-6) bad += "$id t${fr.tick} plate ${fr.note}: draws ${fr.renderedW} blocks, declared ${fr.authored}"
                    }
                    "mesh" -> {
                        meshes++
                        if (abs(fr.cols[0].length() - fr.authored) > 1e-6) bad += "$id t${fr.tick} mesh ${fr.note}: scale ${fr.cols[0].length()} for declared ${fr.authored}"
                    }
                    "line" -> {
                        lines++
                        // text quad: scale.y 4 per block of thickness
                        if (abs(fr.cols[1].length() / Intrinsic.SOLID_SCALE_Y - fr.authored) > 1e-6) bad += "$id t${fr.tick} line thickness ${fr.cols[1].length() / Intrinsic.SOLID_SCALE_Y} for declared ${fr.authored}"
                    }
                }
            }
            // one write per display per tick: the single matrix the client interpolates, never a split position / rotation pair
            val dup = rec.calls.groupBy { it.handle to it.tick }.filter { (_, v) -> v.count { !it.spawn } > 1 }
            for ((k, v) in dup) bad += "$id display ${k.first} tick ${k.second}: ${v.size} writes"
            for (c in rec.calls) {
                val q = c.write.transform.rotation
                if (abs(q.lengthSquared() - 1.0) > 1e-9) bad += "$id display ${c.handle}: left rotation not unit"
            }
        }
        assertTrue(plates > 100 && meshes > 100 && lines > 0, "recorded plates=$plates meshes=$meshes lines=$lines")
        assertTrue(bad.isEmpty(), "size / write violations:\n" + bad.take(30).joinToString("\n"))
    }

    @Test
    fun `bastion s1 emits follow the blade instead of the caster feet`() {
        val origin = Vec3(37.5, -60.0, -12.5)
        val bursts = ArrayList<ParticleBurst>()
        val particles = object : ISkillParticleGateway {
            override fun emit(burst: ParticleBurst) { bursts += burst }
        }
        lateinit var sim: CastSim
        val rec = RecordingVisuals(now = { sim.now })
        sim = CastSim(mapOf(simVictim(1) to ahead(origin, 0.0, 3.0)), origin = origin, particleGateway = particles, visualGateway = rec)
        sim.cast("bastion-greatsword-s1", 40)
        val trail = bursts.filter { it.particle == "bastion-s1-p-trail" }
        assertThat(trail).describedAs("the trail particles of the swing").isNotEmpty
        val distances = trail.map { (it.at - origin).length() }
        assertThat(distances.max() - distances.min()).describedAs("emit distance from the caster feet varies as the blade sweeps").isGreaterThan(1.0)
        assertThat(trail.map { it.at.y }.max()).describedAs("the sweep rises well above the feet").isGreaterThan(origin.y + 0.8)
        // every emit of the swing leaves the caster's own spot: none is collapsed onto the feet
        assertThat(bursts.filter { it.particle.startsWith("bastion-s1-p-") }.count { (it.at - origin).length() < 0.05 }).isEqualTo(0)
    }
}
