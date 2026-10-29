package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * The rig is an anchor instance (ADR-0303 10.5): the bastion greatsword s1 rig draws the main-hand item with the thirdperson_righthand
 * display and publishes the part's model points as `rig.tip` / `rig.root`. A particle anchored at `rig.tip` (and the five-sample segment
 * `rig.root -> rig.tip`) must be emitted where the drawn blade's tip (root) is at the same tick: the mesh frame carries the same part
 * points. Fails when the part axes, the rest, the mount or the anchor's points are wrong.
 */
class RigBladeAxisScenarioTest : SkillEngineScenario() {
    private fun Vec3.scale(k: Double) = Vec3(x * k, y * k, z * k)

    private fun dist(a: Vec3, b: Vec3): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    @Test
    fun particlesAtRigTipAndRootFollowTheDrawnBlade() {
        val text = Files.readString(contentDir.resolve("skills/bastion-greatsword-s1/skill.yaml"))
        val skill = when (val r = compileText("bastion-greatsword-s1/skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("bastion-greatsword-s1 does not compile: ${r.error}")
        }
        val rec = GatewayRecorder()
        playProbe(skill, ticks = 60, rec = rec)
        val meshName = rec.calls.first { it.verb == "spawn" && it.payload == "kind=item" }.instance
        val frames = rec.writtenPoses.filter { it.second == meshName }.associate { it.first to it.third.frame }
        assertThat(frames).isNotEmpty

        // points of the frame file's held_hand part (content/frames/bastion-greatsword.yaml)
        val tip = Vec3(-0.0721, 1.5432, -1.04).scale(SkinRigTransforms.PLAYER_SCALE)
        val root = Vec3(-0.0124, 0.2653, -0.1788).scale(SkinRigTransforms.PLAYER_SCALE)

        // swing 1 emits at ticks 4..19 (blade1 group); compare each tick's emissions with the mesh frame of the same tick
        var worstTip = 0.0
        var worstRoot = 0.0
        var checked = 0
        for (tick in 5..18) {
            val frame = frames[tick] ?: continue
            val at = rec.emittedAt.filter { it.first == tick }.map { it.third }
            if (at.isEmpty()) continue
            worstTip = maxOf(worstTip, at.minOf { dist(it, frame.toWorld(tip)) })
            worstRoot = maxOf(worstRoot, at.minOf { dist(it, frame.toWorld(root)) })
            checked++
        }
        assertThat(checked).describedAs("ticks with rig-anchored emissions").isGreaterThan(8)
        println("RIG-ANCHOR max distance (blocks): tip=%.4f root=%.4f over %d ticks".format(worstTip, worstRoot, checked))
        assertThat(worstTip).describedAs("an emission at rig.tip vs the drawn blade tip").isLessThan(0.01)
        assertThat(worstRoot).describedAs("an emission at rig.root vs the drawn blade root").isLessThan(0.01)
    }
}
