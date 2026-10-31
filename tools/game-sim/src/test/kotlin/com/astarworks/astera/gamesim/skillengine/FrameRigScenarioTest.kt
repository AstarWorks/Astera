package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.asin

/**
 * A `rig` riding a frame (ADR-0314, bastion-greatsword-s1 `frame: blade`): the held mesh is placed with the part's `long` axis along the
 * frame's `v` and its `edge` along `w`, grip on the origin. Two things the frame grammar promises by construction:
 *
 *  - (b) the skin-rig hand is the weapon frame origin: the puppet's right hand is pinned onto the frame origin the mesh's grip sits on, every tick (ADR-0315);
 *  - (d) the edge leads: a swing is a turn about the frame's own `s`, so the tip's velocity lies in the blade plane (long x edge) on every
 *        tick the blade moves. Measured from the model's real axes through the whole chain (arm + hand + frame rotation) and the tip's
 *        finite-difference velocity. Ticks with a slow tip (the hand's own translation dominates) and the `reset` window (a slerp back to
 *        rest, not a cut) are left out.
 */
class FrameRigScenarioTest : SkillEngineScenario() {
    private val scale = SkinRigTransforms.PLAYER_SCALE

    // content/frames/bastion-greatsword.yaml, part held_hand
    private val grip = Vec3(-0.0016, 0.0343, -0.0231) * scale
    private val tip = Vec3(-0.0721, 1.5432, -1.04) * scale
    private val long = Vec3(-0.0387, 0.8287, -0.5584).normalize()
    private val edge = Vec3(-0.0315, -0.5596, -0.8282).normalize()

    private class Played(val start: Int, val meshFrames: Map<Int, Frame>, val casterAt: Map<Int, Vec3>, val rec: GatewayRecorder)

    private fun playS1(): Played {
        val text = Files.readString(contentDir.resolve("skills/bastion-greatsword-s1/skill.yaml"))
        val skill = when (val r = compileText("bastion-greatsword-s1/skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("bastion-greatsword-s1 does not compile: ${r.error}")
        }
        val rec = GatewayRecorder()
        rec.casterPosition = com.astarworks.astera.core.domain.skillengine.math.Vector3d(0.0, 0.0, 0.0)
        playProbe(skill, ticks = 60, rec = rec)
        val mesh = rec.calls.first { it.verb == "spawn" && it.payload == "kind=item" }
        val start = rec.writtenPoses.first { it.second == mesh.instance }.first
        val frames = LinkedHashMap<Int, Frame>()
        val caster = LinkedHashMap<Int, Vec3>()
        for ((i, w) in rec.writtenPoses.withIndex()) {
            val (tick, name, pose) = w
            if (name != mesh.instance) continue
            frames[tick] = pose.frame
            caster[tick] = rec.writtenCasterAt[i].let { Vec3(it.x, it.y, it.z) }
        }
        return Played(start, frames, caster, rec)
    }

    /**
     * ADR-0315: the puppet is driven by the engine, one `poseBody` per tick with the frame the table used, its right hand pinned onto the
     * `blade` frame's origin by look-at. Judged on what the Paper puppet draws: the held-item grip of the pushed frame, placed by the pushed
     * anchor, against the mesh's grip point (which sits on the frame origin by construction). Reach is reported per tick: a target the
     * straight arm cannot reach (farther, or nearer, than the arm + grip) is clamped onto the arm's ray and listed.
     */
    @Test
    fun thePuppetGripSitsOnTheBladeFrameOriginEveryTick() {
        val p = playS1()
        val pushed = p.rec.pushedBody.associateBy { it.tick }
        var worst = 0.0
        var worstTick = -1
        val reach = ArrayList<String>()
        for ((tick, frame) in p.meshFrames.entries.sortedBy { it.key }) {
            val push = pushed[tick] ?: throw AssertionError("no puppet pose was pushed on t$tick (the mesh was written there)")
            val anchor = push.anchor ?: throw AssertionError("the caster's puppet was pushed without its anchor on t$tick")
            assertThat(push.frame.rightHandTarget).describedAs("the right hand is pinned on t$tick").isNotNull
            val rig = Frame.fromYawPitch(anchor.feet, anchor.yawDeg, 0.0)
            val hand = rig.toWorld(SkinRigTransforms.compute(push.frame, false).heldItem.translation.let { Vec3(it.x, it.y, it.z) })
            val d = frame.toWorld(grip).distance(hand)
            if (d > worst) { worst = d; worstTick = tick }
            SkinRigTransforms.rightHandReach(push.frame, false)?.let { if (abs(it) > 0.02) reach += "t%d %+.3f".format(tick, it) }
        }
        println("FRAME-RIG puppet grip vs blade origin worst=%.4f at t%d over %d ticks; reach clamped (blocks beyond the arm): %s".format(worst, worstTick, p.meshFrames.size, if (reach.isEmpty()) "none" else reach.joinToString(" ")))
        assertThat(p.meshFrames.size).isGreaterThan(50)
        assertThat(worst).describedAs("the puppet's right-hand grip sits on the blade frame origin (worst at t$worstTick)").isLessThan(0.05)
    }

    @Test
    fun theEdgeLeadsOnEveryMovingTick() {
        val p = playS1()
        val resetAt = 55 // the blade frame's `reset: true` key: a slerp to rest, not a cut
        val minTipSpeed = 0.25 // blocks per tick; below it the hand's own travel is most of the tip's motion
        var checked = 0
        var worst = 0.0
        var worstTick = -1
        val lines = ArrayList<String>()
        var prevTip: Vec3? = null
        var prevGrip: Vec3? = null
        for ((tick, frame) in p.meshFrames.entries.sortedBy { it.key }) {
            val tipW = frame.toWorld(tip)
            val gripW = frame.toWorld(grip)
            val prev = prevTip
            val prevG = prevGrip
            prevTip = tipW
            prevGrip = gripW
            if (prev == null || prevG == null) continue
            val vel = tipW - prev
            val hand = gripW - prevG
            val speed = vel.length()
            val longW = frame.rotation.rotate(long).normalize()
            val edgeW = frame.rotation.rotate(edge).normalize()
            val normal = longW.cross(edgeW).normalize()
            val off = Math.toDegrees(asin(abs(vel.normalize().dot(normal)).coerceAtMost(1.0)))
            val lead = vel.normalize().dot(edgeW)
            // the tip's and the hand's travel in the blade's own axes (long / edge / normal): what an author needs to see which way a roll must go
            lines += "t%d speed=%.2f offplane=%.1f lead=%+.2f tip(l,e,n)=(%+.2f,%+.2f,%+.2f) hand(l,e,n)=(%+.2f,%+.2f,%+.2f) up.n=%+.2f".format(
                tick, speed, off, lead, vel.dot(longW), vel.dot(edgeW), vel.dot(normal), hand.dot(longW), hand.dot(edgeW), hand.dot(normal), normal.y,
            )
            if (speed <= minTipSpeed || tick >= resetAt) continue
            checked++
            if (off > worst) { worst = off; worstTick = tick }
        }
        println("FRAME-RIG blade plane vs tip velocity:\n" + lines.joinToString("\n"))
        assertThat(checked).describedAs("moving ticks judged").isGreaterThan(20)
        assertThat(worst).describedAs("tip velocity leaves the blade plane by at most 15 degrees (worst at t$worstTick)").isLessThan(15.0)
    }
}
