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

/**
 * `op: pull` on the shipped pulling skills: a victim hit at any distance rests about 1.5 blocks in front of the caster, never past it.
 * The recorded launch impulse is integrated here with its own tick loop of the vanilla living-entity motion (not the engine's solver).
 */
class PullLandingScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 31L)

    private fun shipped(id: String): CompiledSkill {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        return when (val r = compileText(file.fileName.toString(), Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("$id did not load: ${r.error.message}")
        }
    }

    /** Where the victim rests (z, the caster stands at 0 and the victim starts at [startZ]) after being launched with the recorded impulse. */
    private fun restingZ(skill: String, startZ: Double): Double {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, startZ), 0.0, HitBox(Vec3(-0.3, 0.0, startZ - 0.3), Vec3(0.3, 1.8, startZ + 0.3)), 1.62))
        play(shipped(skill), seed = 1L, events = emptyList(), ticks = 40, rec = rec)
        val imp = rec.calls.single { it.verb == "effect" && it.instance == foe.toString() && it.payload.startsWith("impulse") }.payload
        fun axis(k: String) = Regex("$k=(-?[0-9.]+)").find(imp)!!.groupValues[1].toDouble()
        var vz = axis("z")
        var vy = axis("y")
        assertThat(axis("x")).isZero()
        var z = startZ
        var y = 0.0
        var ground = true
        repeat(300) {
            val drag = if (ground) 0.546 else 0.91
            z += vz
            y += vy
            ground = y <= 0.0
            if (ground) { y = 0.0; vy = 0.0 } else vy = (vy - 0.08) * 0.98
            vz *= drag
            if (kotlin.math.abs(vz) < 0.003) vz = 0.0
        }
        return z
    }

    @Test
    fun harpoonLandsInFrontOfTheCasterAtEveryRange() {
        for (start in listOf(3.0, 5.0, 12.0, 30.0)) {
            val rested = restingZ("harpoon-throw", start)
            println("pull-landing harpoon-throw start=$start rests z=$rested")
            assertThat(rested).`as`("harpoon at $start").isBetween(1.1, 1.9)
        }
    }

    @Test
    fun grappleShotLandsInFrontOfTheCaster() {
        for (start in listOf(3.0, 5.0, 12.0)) {
            val rested = restingZ("grapple-shot", start)
            println("pull-landing grapple-shot start=$start rests z=$rested")
            assertThat(rested).`as`("grapple at $start").isBetween(1.1, 1.9)
        }
    }

    @Test
    fun scythePullLandsInFrontOfTheCaster() {
        val rested = restingZ("scythe-pull", 3.0)
        println("pull-landing scythe-pull start=3.0 rests z=$rested")
        assertThat(rested).`as`("scythe at 3").isBetween(1.1, 1.9)
    }
}
