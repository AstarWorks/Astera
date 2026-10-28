package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * SKL-09 (ADR-0301): every shipped skill is cast once by the real runner against a row of dummies ahead of the caster.
 * No exception, no op diagnostics; a skill with a `damage` op must have hurt a dummy. The fake ports record damage only.
 */
class ShippedSkillsCastSmokeScenarioTest {

    @Test
    fun `every shipped skill casts once without a diagnostic and damage skills hurt a dummy`() {
        runSmoke(Vec3.ZERO, 0.0)
    }

    /** A caster far from the world origin (y -60 like the e2e superflat) turned 30 degrees: world and caster-local coordinates must not be mixed up. */
    @Test
    fun `every shipped skill casts the same at a non zero caster origin and keeps its frames near the caster`() {
        runSmoke(Vec3(37.5, -60.0, -12.5), 30.0)
    }

    private fun runSmoke(origin: Vec3, yaw: Double) {
        val dummies = (1..4).associate { simVictim(it) to ahead(origin, yaw, 2.0 * it) }
        val skillsDir = Coin2CastWiring.collaborators().skillsDir
        val problems = ArrayList<String>()
        var cast = 0
        var damaged = 0
        val ids = CastSim(dummies, origin = origin, yawDeg = yaw).catalog.ids().filter { '@' !in it }.sorted()
        for (id in ids) {
            val hasDamage = Files.walk(skillsDir.resolve(id)).use { s ->
                s.filter { it.toString().endsWith(".yaml") }.anyMatch { Regex("""op:\s*damage\b""").containsMatchIn(Files.readString(it)) }
            }
            val sim = CastSim(dummies, origin = origin, yawDeg = yaw, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            try {
                sim.cast(id, 260)
                cast++
                if (sim.dealt.isNotEmpty()) damaged++
                val reactive = Files.walk(skillsDir.resolve(id)).use { s -> s.filter { it.toString().endsWith(".yaml") }.anyMatch { Regex("""op:\s*window\b""").containsMatchIn(Files.readString(it)) } }
                if (hasDamage && !reactive && sim.dealt.isEmpty()) problems += "$id: has a damage op but hurt nobody"
                if (sim.farthest.second > 30.0) problems += "$id: a frame ${"%.1f".format(sim.farthest.second)} blocks from the caster (${sim.farthest.first}); a world point was probably used as a parent-local offset"
            } catch (e: Throwable) {
                problems += "$id: ${e::class.simpleName}: ${e.message?.take(300)}"
            }
        }
        println("cast smoke: ${ids.size} skills, $cast cast clean, $damaged dealt damage")
        assertThat(ids).hasSizeGreaterThanOrEqualTo(149)
        assertThat(problems).describedAs("cast smoke problems").isEmpty()
    }
}
