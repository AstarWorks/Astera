package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.gamesim.SimClips
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * A held rig (`mount: caster.hand_r`, no `of:`) draws the caster's main-hand item with the thirdperson_righthand display, so its grip must sit
 * on the hand of the caster's pose at the same tick: at t+0 and at every later tick. Fails when the pivot, the scale units or the rotation
 * composition of the held rig drift from the skin-rig hand.
 */
class RigAttachScenarioTest : SkillEngineScenario() {
    private fun dist(a: Vec3, b: Vec3): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    private fun gripToHand(skillId: String, anim: String?, grip: Vec3, ticks: Int): List<Pair<Int, Double>> {
        val text = Files.readString(contentDir.resolve("skills/$skillId/skill.yaml"))
        val skill = when (val r = compileText("$skillId/skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("$skillId does not compile: ${r.error}")
        }
        val rec = GatewayRecorder()
        rec.casterPosition = com.astarworks.astera.core.domain.skillengine.math.Vector3d(0.0, 0.0, 0.0)
        playProbe(skill, ticks = ticks, rec = rec)
        val mesh = rec.calls.first { it.verb == "spawn" && it.payload == "kind=item" }
        val start = rec.writtenPoses.first { it.second == mesh.instance }.first
        val playback = anim?.let { SkinRigPlayback(SimClips.clip(it) ?: error("clip $it"), 1.0, rec.lastBodyHolds) }
        val slim = anim != null
        val out = ArrayList<Pair<Int, Double>>()
        for ((i, w) in rec.writtenPoses.withIndex()) {
            val (tick, name, pose) = w
            if (name != mesh.instance) continue
            val c = rec.writtenCasterAt[i]
            val s = (tick - start - 1).coerceAtLeast(0)
            val rigFrame = playback?.frameAt(s) ?: RigFrame.REST
            val hand = SkinRigTransforms.compute(rigFrame, slim).heldItem.translation.let { Vec3(it.x + c.x, it.y + c.y, it.z + c.z) }
            out += tick to dist(pose.frame.toWorld(grip.scale(SkinRigTransforms.PLAYER_SCALE)), hand)
        }
        return out
    }

    private fun Vec3.scale(k: Double) = Vec3(x * k, y * k, z * k)

    @Test
    fun greatswordCleaveGripSitsOnTheHand() {
        val d = gripToHand("greatsword-cleave", null, Vec3(0.0, -0.0307, 0.0215), 20)
        println("ATTACH greatsword-cleave " + d.joinToString { "t${it.first}=%.3f".format(it.second) })
        assertThat(d).isNotEmpty
        assertThat(d.maxOf { it.second }).isLessThan(0.08)
    }

    @Test
    fun bastionGreatswordS1GripSitsOnTheHand() {
        val d = gripToHand("bastion-greatsword-s1", "bastion-s1-body", Vec3(-0.0016, 0.0343, -0.0231), 60)
        println("ATTACH bastion-greatsword-s1 " + d.joinToString { "t${it.first}=%.3f".format(it.second) })
        assertThat(d).isNotEmpty
        assertThat(d.maxOf { it.second }).isLessThan(0.08)
    }
}
