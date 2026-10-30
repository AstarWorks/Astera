package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs

/**
 * Caster-moving ops never leave the caster inside a body (the iaido bug: the dash ran 4 blocks into a dummy 4.2 blocks ahead and the
 * first-person camera sat in its skin). Every shipped skill with a `dash` is cast with a husk-sized dummy straight ahead at several
 * distances; no position the caster is moved to may overlap the dummy's hitbox, a plain dash stops in front of it, a `pass_through`
 * dash ends behind it.
 */
class DashClearanceScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 77L)

    private fun shipped(id: String): CompiledSkill {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        return when (val r = compileText(file.fileName.toString(), Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("$id did not load: ${r.error.message}")
        }
    }

    private fun dashSkills(): List<String> =
        Files.list(contentDir.resolve("skills")).use { dirs ->
            dirs.filter { Files.exists(it.resolve("skill.yaml")) && Files.readString(it.resolve("skill.yaml")).contains("op: dash") }
                .map { it.fileName.toString() }.sorted().toList()
        }

    /** Feet positions the caster was moved to, in tick order. */
    private fun moves(skill: String, dummyZ: Double): List<Vec3> {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, dummyZ), 180.0, HitBox(Vec3(-0.3, 0.0, dummyZ - 0.3), Vec3(0.3, 1.95, dummyZ + 0.3)), 1.74))
        val calls = play(shipped(skill), seed = 1L, events = emptyList(), ticks = null, rec = rec)
        val re = Regex("to=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)")
        return calls.filter { it.verb == "move" }.mapNotNull { re.find(it.payload)?.destructured }.map { (x, y, z) -> Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    }

    /** The caster body (0.6 x 1.8) at feet [p] overlaps the dummy box by more than rounding. */
    private fun overlaps(p: Vec3, dummyZ: Double): Boolean {
        val dx = 0.6 - abs(p.x)
        val dz = (0.3 + 0.3) - abs(p.z - dummyZ)
        val dy = minOf(p.y + 1.8, 1.95) - maxOf(p.y, 0.0)
        return dx > 1.0e-3 && dz > 1.0e-3 && dy > 1.0e-3
    }

    @Test
    fun noDashEndsInsideADummyAhead() {
        val skills = dashSkills()
        assertThat(skills).`as`("shipped dash skills").contains("katana-iaido", "iron-blade-lunge", "spear-charge")
        for (skill in skills) for (dz in listOf(1.2, 2.0, 3.1, 4.2, 6.0, 9.0)) {
            val m = moves(skill, dz)
            println("dash-clearance $skill dummy z=$dz moves=${m.map { "%.2f".format(it.z) }}")
            m.forEach { assertThat(overlaps(it, dz)).`as`("$skill with a dummy at z=$dz moved the caster to $it, inside it").isFalse() }
        }
    }

    @Test
    fun iaidoEndsBehindTheDummyItPasses() {
        val dummyZ = 4.2
        val end = moves("katana-iaido", dummyZ).last()
        assertThat(end.z).`as`("iaido end z").isGreaterThan(dummyZ + 0.6)
        val lunge = moves("iron-blade-lunge", 2.0).last()
        assertThat(lunge.z).`as`("lunge end z (dummy at 2.0)").isGreaterThan(2.0 + 0.6)
    }

    @Test
    fun plainDashStopsInFrontOfTheDummy() {
        // the same iaido path with the pass-through flag removed is a plain dash: it ends at the front edge of the dummy
        val src = Files.readString(contentDir.resolve("skills/katana-iaido/skill.yaml")).replace(", pass_through: true", "")
        val skill = (compileText("iaido-plain.yaml", src) as Result.Success).value
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 4.2), 180.0, HitBox(Vec3(-0.3, 0.0, 3.9), Vec3(0.3, 1.95, 4.5)), 1.74))
        val calls = play(skill, seed = 1L, events = emptyList(), ticks = 40, rec = rec)
        val end = calls.filter { it.verb == "move" && it.payload.startsWith("to=") }.last().payload
        val z = Regex("to=-?[0-9.]+,-?[0-9.]+,(-?[0-9.]+)").find(end)!!.groupValues[1].toDouble()
        assertThat(z).`as`("plain dash end z").isBetween(3.9 - 0.3 - 0.05, 3.9 - 0.3 + 0.001)
    }
}
