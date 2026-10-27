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
        val dummies = (1..4).associate { simVictim(it) to Vec3(0.0, 0.0, 2.0 * it) }
        val skillsDir = Coin2CastWiring.collaborators().skillsDir
        val problems = ArrayList<String>()
        var cast = 0
        var damaged = 0
        val ids = CastSim(dummies).catalog.ids().filter { '@' !in it }.sorted()
        for (id in ids) {
            val hasDamage = Files.walk(skillsDir.resolve(id)).use { s ->
                s.filter { it.toString().endsWith(".yaml") }.anyMatch { Regex("""op:\s*damage\b""").containsMatchIn(Files.readString(it)) }
            }
            val sim = CastSim(dummies, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            try {
                sim.cast(id, 260)
                cast++
                if (sim.dealt.isNotEmpty()) damaged++
                if (hasDamage && sim.dealt.isEmpty()) problems += "$id: has a damage op but hurt nobody"
            } catch (e: Throwable) {
                problems += "$id: ${e::class.simpleName}: ${e.message?.take(300)}"
            }
        }
        println("cast smoke: ${ids.size} skills, $cast cast clean, $damaged dealt damage")
        assertThat(ids).hasSizeGreaterThanOrEqualTo(149)
        assertThat(problems).describedAs("cast smoke problems").isEmpty()
    }
}
