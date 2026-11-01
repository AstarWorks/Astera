package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs

/**
 * The `blink` op (ADR-0303 3.1): the shipped trial skill `shadow-step` moves the caster 8 blocks forward in one tick; whatever stands in
 * the way (a husk-sized dummy, a wall) stops it short, and the caster never ends overlapping a body.
 */
class BlinkScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 77L)

    private fun shipped(id: String): CompiledSkill {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        return when (val r = compileText(file.fileName.toString(), Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("$id did not load: ${r.error.message}")
        }
    }

    private fun moves(skill: CompiledSkill, dummyZ: Double? = null, wallZ: Double? = null): List<Vec3> {
        val rec = GatewayRecorder().also { it.faithfulPose = true }
        if (dummyZ != null) rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, dummyZ), 180.0, HitBox(Vec3(-0.3, 0.0, dummyZ - 0.3), Vec3(0.3, 1.95, dummyZ + 0.3)), 1.74))
        if (wallZ != null) rec.wall = { from, to -> if (to.z >= wallZ && from.z < wallZ) BlockHit(Vec3(from.x, from.y, wallZ), Vec3(0.0, 0.0, -1.0), "stone") else null }
        val calls = play(skill, seed = 1L, events = emptyList(), ticks = 8, rec = rec)
        val re = Regex("to=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)")
        return calls.filter { it.verb == "move" }.mapNotNull { re.find(it.payload)?.destructured }.map { (x, y, z) -> Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    }

    private fun overlaps(p: Vec3, dummyZ: Double): Boolean {
        val dx = 0.6 - abs(p.x)
        val dz = 0.6 - abs(p.z - dummyZ)
        val dy = minOf(p.y + 1.8, 1.95) - maxOf(p.y, 0.0)
        return dx > 1.0e-3 && dz > 1.0e-3 && dy > 1.0e-3
    }

    @Test
    fun blinkCoversTheDistanceInOneStepWhenNothingIsInTheWay() {
        val m = moves(shipped("shadow-step"))
        assertThat(m).hasSize(1)
        assertThat(m.single().z).isBetween(7.99, 8.01)
    }

    @Test
    fun blinkNeverEndsInsideADummyAhead() {
        val skill = shipped("shadow-step")
        for (dz in listOf(1.2, 2.0, 3.1, 5.0, 7.0, 8.0, 9.0)) {
            val m = moves(skill, dummyZ = dz)
            println("blink dummy z=$dz end=${m.map { "%.2f".format(it.z) }}")
            m.forEach { assertThat(overlaps(it, dz)).`as`("blink with a dummy at z=$dz landed at $it, inside it").isFalse() }
        }
        // a dummy in the line stops the blink in front of it
        assertThat(moves(skill, dummyZ = 5.0).single().z).isLessThan(5.0 - 0.3 - 0.3 + 0.001)
    }

    @Test
    fun blinkStopsShortOfAWall() {
        val end = moves(shipped("shadow-step"), wallZ = 5.0).single()
        assertThat(end.z).`as`("blink end z with a wall at 5").isBetween(4.0, 5.0 - 0.3)
    }

    @Test
    fun blinkIsCutToMax() {
        val src = Files.readString(contentDir.resolve("skills/shadow-step/skill.yaml")).replace("default: 8.0", "default: 15.0")
        val end = moves((compileText("far.yaml", src) as Result.Success).value).single()
        assertThat(end.z).`as`("distance 15 with max 12").isBetween(11.99, 12.01)
    }

    @Test
    fun maxAboveTheCapIsALoadError() {
        val src = Files.readString(contentDir.resolve("skills/shadow-step/skill.yaml")).replace("max: 12", "max: 40")
        val r = compileText("cap.yaml", src)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error.message).contains("blocks")
    }
}
