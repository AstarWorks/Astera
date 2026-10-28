package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FrameOrient
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import com.astarworks.astera.core.domain.skillengine.frame.OrientMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * v1 vfx `orient` on the real frame tree: a frame under a caster keeps its own axes (world / yaw / look / to_target)
 * whatever the caster's rotation is, which is how v1 builds the anchor frame (VfxOrient.frame).
 */
class FrameOrientScenarioTest {

    private val tol = Offset.offset(1e-9)

    private fun tree(casterYawDeg: Double, aimPitchDeg: Double): InMemoryFrameTree {
        val t = InMemoryFrameTree()
        val yaw = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(casterYawDeg))
        val pitch = Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(aimPitchDeg))
        t.add(FrameNode(FrameId("world"), FrameKind.PART, null, Frame(Vec3(0.0, 0.0, 0.0))))
        t.add(FrameNode(FrameId("caster"), FrameKind.PART, FrameId("world"), Frame(Vec3(10.0, 0.0, 0.0), yaw)))
        t.add(FrameNode(FrameId("aim"), FrameKind.PART, FrameId("caster"), Frame(Vec3(0.0, 1.6, 0.0), pitch)))
        return t
    }

    private fun add(t: InMemoryFrameTree, id: String, orient: FrameOrient?) =
        t.add(FrameNode(FrameId(id), FrameKind.PART, FrameId("caster"), Frame(Vec3(0.0, 0.0, 3.0)), orient))

    private fun fwd(t: InMemoryFrameTree, id: String) = t.worldOf(FrameId(id)).getOrNull()!!.forward()

    @Test
    fun `orient modes keep their own axes under a rotated, pitched caster`() {
        val t = tree(90.0, -30.0)
        add(t, "inherit", null)
        add(t, "level", FrameOrient(OrientMode.WORLD))
        add(t, "yaw", FrameOrient(OrientMode.YAW))
        add(t, "look", FrameOrient(OrientMode.LOOK))
        val aim = fwd(t, "aim")

        // no orient: inherits the caster's yaw (forward +z rotated 90 degrees about y -> +x)
        assertThat(fwd(t, "inherit").x).isCloseTo(1.0, tol)
        // world: identity axes
        assertThat(fwd(t, "level").z).isCloseTo(1.0, tol)
        // yaw: the aim heading, level
        assertThat(fwd(t, "yaw").y).isCloseTo(0.0, tol)
        assertThat(fwd(t, "yaw").x).isCloseTo(1.0, tol)
        // look: the full aim direction, pitch included
        assertThat(fwd(t, "look").y).isCloseTo(aim.y, tol)
        assertThat(fwd(t, "look").x).isCloseTo(aim.x, tol)
        // the origin is still the parent's: 3 blocks along the caster's own forward
        assertThat(t.worldOf(FrameId("level")).getOrNull()!!.origin.x).isCloseTo(13.0, tol)
        assertThat(t.worldOf(FrameId("yaw")).getOrNull()!!.origin.x).isCloseTo(13.0, tol)
    }

    @Test
    fun `to_target faces away from the caster and falls back to look when they coincide`() {
        val t = tree(0.0, 0.0)
        add(t, "away", FrameOrient(OrientMode.TO_TARGET))
        t.setLocal(FrameId("away"), Frame(Vec3(3.0, 0.0, 0.0)))
        assertThat(fwd(t, "away").x).isCloseTo(1.0, tol) // caster -> anchor, not the caster's forward
        t.setLocal(FrameId("away"), Frame(Vec3(0.0, 0.0, 0.0)))
        assertThat(fwd(t, "away").z).isCloseTo(1.0, tol) // coincident: the look rotation
    }
}
