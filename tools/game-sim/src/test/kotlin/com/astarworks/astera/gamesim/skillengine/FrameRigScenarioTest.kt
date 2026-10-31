package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.BonePose
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.asin

/**
 * A `rig` riding a frame (ADR-0314, bastion-greatsword-s1 `frame: blade`, revolver-shot `frame: gun`): the held mesh is placed with the
 * part's `long` axis along the frame's `v` and its `edge` along `w`, grip on the origin. Things the frame grammar promises by construction:
 *
 *  - (b) the skin-rig hand is the weapon frame origin: the puppet's right hand is pinned onto the frame origin the mesh's grip sits on, every
 *        tick (ADR-0315), with a body clip (s1) and without one (the revolver's rest body, aimed by the frame alone);
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

    // content/frames/revolver.yaml, part held_hand: the grip is the part origin
    private val gunGrip = Vec3(0.0, 0.0, 0.0)

    private class Played(val start: Int, val meshFrames: Map<Int, Frame>, val casterAt: Map<Int, Vec3>, val rec: GatewayRecorder)

    /**
     * [faithful] = the recorder answers the caster cells as Paper does (feet at the origin, eye 1.62 up, `caster.eye` pitched); off, every
     * caster cell is the caster position itself (eye = feet), which is all a hand-rooted frame needs but puts an eye-rooted one on the floor.
     */
    private fun play(skillId: String, ticks: Int, faithful: Boolean = false): Played {
        val text = Files.readString(contentDir.resolve("skills/$skillId/skill.yaml"))
        val skill = when (val r = compileText("$skillId/skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("$skillId does not compile: ${r.error}")
        }
        val rec = GatewayRecorder()
        rec.faithfulPose = faithful
        // feet at the origin either way: the faithful recorder stands the caster at casterPosition - (0, 1, 0)
        rec.casterPosition = com.astarworks.astera.core.domain.skillengine.math.Vector3d(0.0, if (faithful) 1.0 else 0.0, 0.0)
        playProbe(skill, ticks = ticks, rec = rec)
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

    private class GripReport(val worst: Double, val worstTick: Int, val judged: Int, val reach: List<String>)

    /**
     * Judged on what the Paper puppet draws: the held-item grip of the pushed frame, placed by the pushed anchor, against the mesh's
     * [gripPoint] (which sits on the frame origin by construction), on every tick the mesh is written before its removal. Reach is
     * reported per tick: a target the straight arm cannot reach (farther, or nearer, than the arm + grip) is clamped onto the arm's ray.
     */
    private fun gripOnOrigin(p: Played, gripPoint: Vec3, judge: (tick: Int, push: GatewayRecorder.PushedBody) -> Unit = { _, _ -> }): GripReport {
        val pushed = p.rec.pushedBody.associateBy { it.tick }
        val mesh = p.rec.calls.first { it.verb == "spawn" && it.payload == "kind=item" }
        // the rig's last tick writes the rest pose and removes the mesh in the same tick: nothing is drawn from it, no body is owed to it
        val removedAt = p.rec.calls.firstOrNull { it.verb == "remove" && it.instance == mesh.instance }?.tick ?: Int.MAX_VALUE
        var worst = 0.0
        var worstTick = -1
        var judged = 0
        val reach = ArrayList<String>()
        for ((tick, frame) in p.meshFrames.entries.sortedBy { it.key }) {
            if (tick >= removedAt) continue
            judged++
            val push = pushed[tick] ?: throw AssertionError("no puppet pose was pushed on t$tick (the mesh was written there)")
            val anchor = push.anchor ?: throw AssertionError("the caster's puppet was pushed without its anchor on t$tick")
            assertThat(push.frame.rightHandTarget).describedAs("the right hand is pinned on t$tick").isNotNull
            val rig = Frame.fromYawPitch(anchor.feet, anchor.yawDeg, 0.0)
            val hand = rig.toWorld(SkinRigTransforms.compute(push.frame, false).heldItem.translation.let { Vec3(it.x, it.y, it.z) })
            val d = frame.toWorld(gripPoint).distance(hand)
            if (d > worst) { worst = d; worstTick = tick }
            SkinRigTransforms.rightHandReach(push.frame, false)?.let { if (abs(it) > 0.02) reach += "t%d %+.3f".format(tick, it) }
            judge(tick, push)
        }
        return GripReport(worst, worstTick, judged, reach)
    }

    /**
     * ADR-0315: the puppet is driven by the engine, one `poseBody` per tick with the frame the table used, its right hand pinned onto the
     * `blade` frame's origin by look-at.
     */
    @Test
    fun thePuppetGripSitsOnTheBladeFrameOriginEveryTick() {
        val r = gripOnOrigin(play("bastion-greatsword-s1", 60), grip)
        println("FRAME-RIG puppet grip vs blade origin worst=%.4f at t%d over %d ticks; reach clamped (blocks beyond the arm): %s".format(r.worst, r.worstTick, r.judged, if (r.reach.isEmpty()) "none" else r.reach.joinToString(" ")))
        assertThat(r.judged).isGreaterThan(50)
        assertThat(r.worst).describedAs("the puppet's right-hand grip sits on the blade frame origin (worst at t${r.worstTick})").isLessThan(0.05)
    }

    /**
     * ADR-0315, a frame rig without `anim:`: revolver-shot's `gun` frame is rooted in the eye / aim basis (aim, right, right x aim) at an aimed point (v 0.57,
     * w -0.51, s 0.30: straight out from the right shoulder, 17 degrees down), the body is the rest pose and the right arm alone is turned
     * onto the frame origin. Every tick of the rig: the pushed body is the rest pose apart from the pin, the pin sits where the frame says
     * (the recoil `move:` keys swing it up at most 0.05 along the arm's sphere), the rigid arm reaches it exactly (no clamp beyond 0.02),
     * and the mesh grip is on it. The rig outlives the cast's end on the hit (detach, tail 10): the pose does not twitch off on the tick the
     * bolt lands.
     */
    @Test
    fun thePuppetGripSitsOnTheRevolverGunFrameOriginEveryTick() {
        val p = play("revolver-shot", 20, faithful = true)
        val feet = p.rec.pushedBody.first().anchor!!.feet
        var restBodies = 0
        val r = gripOnOrigin(p, gunGrip) { tick, push ->
            if (push.frame.root == BonePose.REST && push.frame.bones.values.all { it == BonePose.REST }) restBodies++
            val t = push.frame.rightHandTarget!!
            // rig frame: +X the player's left, +Y up, +Z front; the caster looks along +Z at yaw 0 with its eye 1.62 above the feet
            assertThat(t.x).describedAs("pin right of the eye line on t$tick (s 0.30)").isCloseTo(-0.30, org.assertj.core.data.Offset.offset(0.02))
            assertThat(t.y).describedAs("pin below the eye on t$tick (w -0.51..-0.46)").isBetween(1.62 - 0.51 - 0.02, 1.62 - 0.46 + 0.02)
            assertThat(t.z).describedAs("pin ahead on t$tick (v 0.57..0.59)").isBetween(0.57 - 0.02, 0.59 + 0.02)
        }
        println("FRAME-RIG revolver: puppet grip vs gun origin worst=%.4f at t%d over %d ticks (feet %s); rest bodies %d; reach clamped: %s".format(
            r.worst, r.worstTick, r.judged, feet, restBodies, if (r.reach.isEmpty()) "none" else r.reach.joinToString(" "),
        ))
        assertThat(r.judged).describedAs("ticks the gun mesh was drawn and judged").isEqualTo(10)
        assertThat(restBodies).describedAs("every pushed body is the rest pose plus the pin").isEqualTo(r.judged)
        assertThat(r.reach).describedAs("the aimed point is within the straight arm's reach on every tick").isEmpty()
        assertThat(r.worst).describedAs("the puppet's right-hand grip sits on the gun frame origin (worst at t${r.worstTick})").isLessThan(0.05)
        // the body is put up without a clip and taken down with the rig, on the same gateway the clip bodies use
        val body = p.rec.calls.filter { it.verb == "effect" && it.payload.startsWith("body") }.map { "${it.tick}:${it.payload}" }
        assertThat(body).containsExactly("0:body=skill-engine-rest-body", "10:body_stop=skill-engine-rest-body")
    }

    @Test
    fun theEdgeLeadsOnEveryMovingTick() {
        val p = play("bastion-greatsword-s1", 60)
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
