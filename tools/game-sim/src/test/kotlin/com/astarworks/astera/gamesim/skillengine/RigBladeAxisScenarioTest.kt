package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * The bastion greatsword s1 rig draws the main-hand item with the thirdperson_righthand display, so the blade the player sees must lie
 * on the line the v1 particle emitters baked from the same swing: the sheath emitters (`*_p_sheath_0..4_at`, five points evenly spaced
 * root -> tip, caster-local keys per tick) are the v1 truth. The rig's tip / root, carried by the drawn mesh's world frame, must land
 * on the emitters at the same ticks. Fails when the part axes, the rest or the mesh frame are wrong (the 1.9 block error that
 * the fixed-display part produced).
 */
class RigBladeAxisScenarioTest : SkillEngineScenario() {
    private data class Emitter(val group: String, val index: Int, val keys: Map<Int, Vec3>)

    private fun dist(a: Vec3, b: Vec3): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    @Test
    fun bastionS1BladeLiesOnTheBakedParticleLine() {
        val file = contentDir.resolve("skills/bastion-greatsword-s1/skill.yaml")
        val text = Files.readString(file)
        val skill = when (val r = compileText("bastion-greatsword-s1/skill.yaml", text)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("bastion-greatsword-s1 does not compile: ${r.error}")
        }
        val rec = GatewayRecorder()
        playProbe(skill, ticks = 60, rec = rec)
        val meshName = rec.calls.first { it.verb == "spawn" && it.payload == "kind=item" }.instance
        val frames = rec.writtenPoses.filter { it.second == meshName }.associate { it.first to it.third.frame }
        assertThat(frames).isNotEmpty

        // rest-free blade points of the rig payload (the frame file's held_hand part, lowered into the skill)
        val tip = Vec3(0.0, 1.4503, -0.9786)
        val root = Vec3(0.0, 0.0731, -0.2251)

        val emitterLine = Regex("""id: (\w+)_sheath_([0-4])_at, op: frame, anchor: \w+, offset: [^m]*motion: \[\{law: keys, keys: \[(.*)\]\}\]""")
        val keyRe = Regex("""\{t: (\d+), offset: \{left: ([-\d.]+), up: ([-\d.]+), forward: ([-\d.]+)\}\}""")
        val emitters = text.lineSequence().mapNotNull { emitterLine.find(it) }.map { m ->
            Emitter(
                m.groupValues[1], m.groupValues[2].toInt(),
                keyRe.findAll(m.groupValues[3]).associate { k -> k.groupValues[1].toInt() to Vec3(k.groupValues[2].toDouble(), k.groupValues[3].toDouble(), k.groupValues[4].toDouble()) },
            )
        }.groupBy { it.group }
        assertThat(emitters).isNotEmpty

        // the sim's caster stands where its dashes left it (`teleport` moves it): the caster-follow emitter frames ride with it
        val moves = rec.calls.filter { it.verb == "move" && it.payload.startsWith("to=") }
            .map { c -> c.tick to c.payload.removePrefix("to=").split(',').map { it.toDouble() } }
        fun casterAt(tick: Int): Vec3 {
            val p = moves.lastOrNull { it.first <= tick }?.second ?: listOf(0.0, 0.0, 0.0)
            return Vec3(p[0], p[1] + 1.0, p[2])
        }
        fun emitterWorld(tick: Int, local: Vec3): Vec3 = casterAt(tick) + local

        // The emitter group runs from the loop's start tick; the tick whose rig pose reproduces it is the contract (an off-by-one here shows
        // as a block-sized error). Tip: emitter 4 IS the v1 blade tip. Root: emitter 0 starts the line a little off the authored root.
        var worstTip = 0.0
        var worstRoot = 0.0
        val report = ArrayList<String>()
        for ((group, five) in emitters) {
            val first = five.single { it.index == 0 }
            val last = five.single { it.index == 4 }
            var bestTip = Double.MAX_VALUE
            var bestRoot = 0.0
            var bestStart = -1
            for (start in 0..40) {
                var mt = 0.0
                var mr = 0.0
                // t = 0 is the dash's first step, applied in the same sim tick after the rig pose is written: not comparable
                for (t in first.keys.keys.sorted().filter { it >= 1 }) {
                    val f = frames[start + t] ?: continue
                    val a = first.keys[t] ?: continue
                    val b = last.keys[t] ?: continue
                    mt = maxOf(mt, dist(f.toWorld(tip), emitterWorld(start + t, b)))
                    mr = maxOf(mr, dist(f.toWorld(root), emitterWorld(start + t, a)))
                }
                if (mt < bestTip) { bestTip = mt; bestRoot = mr; bestStart = start }
            }
            report += "$group start=$bestStart tipMax=%.4f rootMax=%.4f".format(bestTip, bestRoot)
            worstTip = maxOf(worstTip, bestTip)
            worstRoot = maxOf(worstRoot, bestRoot)
        }
        println("RIG-AXIS max distance (blocks): tip=%.4f root=%.4f; %s".format(worstTip, worstRoot, report.joinToString("; ")))
        assertThat(worstTip).describedAs("rig blade tip vs the v1 baked sheath line end").isLessThan(0.01)
        assertThat(worstRoot).describedAs("rig blade root vs the v1 baked sheath line start").isLessThan(0.2)
    }
}
